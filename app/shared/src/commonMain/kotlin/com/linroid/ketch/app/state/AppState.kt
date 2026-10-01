package com.linroid.ketch.app.state

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import com.linroid.ketch.api.Destination
import com.linroid.ketch.api.DownloadPriority
import com.linroid.ketch.api.DownloadRequest
import com.linroid.ketch.api.DownloadSchedule
import com.linroid.ketch.api.DownloadState
import com.linroid.ketch.api.DownloadTask
import com.linroid.ketch.api.KetchApi
import com.linroid.ketch.api.KetchError
import com.linroid.ketch.api.ResolvedSource
import com.linroid.ketch.api.SpeedLimit
import com.linroid.ketch.api.isDirectory
import com.linroid.ketch.api.isName
import com.linroid.ketch.api.log.KetchLogger
import com.linroid.ketch.api.log.describeCauses
import com.linroid.ketch.api.log.redactUrl
import com.linroid.ketch.app.feedback.ActivityEvent
import com.linroid.ketch.app.feedback.AppMessage
import com.linroid.ketch.app.feedback.MessageAction
import com.linroid.ketch.app.feedback.MessageCenter
import com.linroid.ketch.app.feedback.MessageLevel
import com.linroid.ketch.app.feedback.ToastMode
import com.linroid.ketch.app.instance.DiscoveredServer
import com.linroid.ketch.app.instance.EmbeddedInstance
import com.linroid.ketch.app.instance.InstanceEntry
import com.linroid.ketch.app.instance.InstanceManager
import com.linroid.ketch.app.instance.LanServerDiscovery
import com.linroid.ketch.app.instance.RemoteInstance
import com.linroid.ketch.app.instance.ServerState
import com.linroid.ketch.app.platform.DroppedFile
import com.linroid.ketch.app.platform.localDeviceNoun
import com.linroid.ketch.app.util.displayName
import com.linroid.ketch.app.util.extractFilename
import com.linroid.ketch.app.util.formatBytes
import com.linroid.ketch.app.util.toCopy
import com.linroid.ketch.app.util.transferSummary
import com.linroid.ketch.config.IntakePreferences
import com.linroid.ketch.remote.ConnectionState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds

sealed interface DiscoveryState {
  data object Idle : DiscoveryState
  data class Discovering(
    val servers: List<DiscoveredServer> = emptyList(),
  ) : DiscoveryState
  data class Finished(
    val servers: List<DiscoveredServer>,
  ) : DiscoveryState
  data class Error(val message: String) : DiscoveryState
}

sealed interface ResolveState {
  data object Idle : ResolveState
  data object Resolving : ResolveState
  data class Resolved(val result: ResolvedSource) : ResolveState
  data class Error(
    val message: String,
    val cause: Throwable? = null,
  ) : ResolveState
}

/** Id of this device in [TaskKey]s: [LOCAL_DEVICE_ID] for the embedded one, else `host:port`. */
val InstanceEntry.deviceId: String
  get() = when (this) {
    is RemoteInstance -> "${remoteConfig.host}:${remoteConfig.port}"
    else -> LOCAL_DEVICE_ID
  }

/**
 * App state shared by every screen: the devices, the active device's tasks, the commands that act
 * on them and the requests that open the app's surfaces.
 *
 * Commands run in [scope], which outlives the UI (see [AppController]), report failures through
 * [messages] and register their Undo with [pendingOps]. Like the Compose state it holds, it is
 * meant to be used from the main thread.
 *
 * @param scope runs the commands; it should use a `SupervisorJob` and the main dispatcher.
 * @param speedMode speed mode of the embedded device, owned by the host, such as the service whose
 *   notification switches it; `null` when the host keeps none.
 * @param clock current time of the task list and the speed history.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AppState(
  val instanceManager: InstanceManager,
  private val scope: CoroutineScope,
  val appSettings: AppSettingsController = AppSettingsController(),
  val aiSettings: AiSettingsController = AiSettingsController(),
  private val incoming: IncomingDownloads = IncomingDownloads(),
  val messages: MessageCenter = MessageCenter(),
  val speedMode: SpeedModeController? = null,
  clock: Clock = Clock.System,
) {
  private val log = KetchLogger("AppState")
  private val lanServerDiscovery = LanServerDiscovery()

  /** Undo for removals, clearing, discarding progress, moves and bulk pauses. */
  val pendingOps: PendingOps = PendingOps(scope)

  /** AI discovery searches and their results. */
  val aiDiscover: AiDiscoverController = AiDiscoverController(aiSettings, scope)

  val activeApi: StateFlow<KetchApi> =
    instanceManager.activeApi
  val activeInstance: StateFlow<InstanceEntry?> =
    instanceManager.activeInstance
  val instances: StateFlow<List<InstanceEntry>> =
    instanceManager.instances
  val serverState: StateFlow<ServerState> =
    instanceManager.serverState

  val connectionState: StateFlow<ConnectionState?> =
    activeInstance.flatMapLatest { instance ->
      when (instance) {
        is RemoteInstance -> instance.connectionState
        else -> MutableStateFlow(null)
      }
    }.stateIn(
      scope,
      SharingStarted.WhileSubscribed(5000),
      null
    )

  /** Tasks of the active device, without the ones a pending operation hides. */
  val tasks: StateFlow<List<DownloadTask>> =
    activeInstance.flatMapLatest { entry -> entry?.let(::visibleTasksOf) ?: flowOf(emptyList()) }
      .stateIn(scope, SharingStarted.Eagerly, emptyList())

  private val pendingCounts = mutableMapOf<Pair<TaskKey, String>, Int>()
  private val pendingState = MutableStateFlow<Set<Pair<TaskKey, String>>>(emptySet())

  /** Task commands in flight, as task and command label, for the touched control's spinner. */
  val pending: StateFlow<Set<Pair<TaskKey, String>>> = pendingState.asStateFlow()

  // UI state
  private val filterState = FlowState(StatusFilter.All)
  private val searchState = FlowState("")
  private val arrangementState = FlowState(DEFAULT_ARRANGEMENT)
  private val frozenState = FlowState(false)

  /** Status tab of the Downloads list. */
  var statusFilter: StatusFilter
    get() = filterState.value
    set(value) {
      filterState.value = value
    }

  /** What is typed in the Downloads search field. */
  var searchQuery: String
    get() = searchState.value
    set(value) {
      searchState.value = value
    }

  /** Sort order and grouping of the Downloads list; newest first and ungrouped until changed. */
  var listArrangement: ListArrangement
    get() = arrangementState.value
    set(value) {
      arrangementState.value = value
    }

  /**
   * Whether the pointer is over the Downloads list, a row has focus or a menu is open, which
   * holds the list's order still.
   */
  var listFrozen: Boolean
    get() = frozenState.value
    set(value) {
      frozenState.value = value
    }

  var showAddDialog by mutableStateOf(false)
  var showInstanceSelector by mutableStateOf(false)
  var showAddRemoteDialog by mutableStateOf(false)

  private var latestError by mutableStateOf<AppMessage?>(null)

  /** The newest error on screen, as the error banner showed it before toasts replaced it. */
  @Deprecated("Read the Error messages of messages instead.")
  val errorMessage: String?
    get() = latestError?.let { listOfNotNull(it.title, it.detail).joinToString(": ") }

  /** State of the current AI discovery search. */
  val aiDiscoverState: AiDiscoverState get() = aiDiscover.state

  /** The opened file the add dialog shows, or null when the user types a URL. */
  var openedDownload by mutableStateOf<IncomingDownload.Ready?>(null)
    private set

  /** Links handed to the app that the add dialog shows, or `null`. */
  private var openedLinks: IncomingDownload.Links? = null

  /** What the add sheet was opened with, or `null` while it is closed. */
  var intakeRequest by mutableStateOf<IntakeRequest?>(null)
    private set

  /** Settings page to show, or `null` while Settings is closed. */
  var settingsRequest by mutableStateOf<SettingsTarget?>(null)
    private set

  /** A Discover search the shell should navigate to, cleared with [discoverRequestHandled]. */
  var discoverRequest by mutableStateOf<DiscoverRequest?>(null)
    private set

  /** Task shown in the inspector, or `null`. */
  var inspectedTask by mutableStateOf<TaskKey?>(null)
    private set

  /** Whether the docked inspector is shown; remembered between launches. */
  var inspectorOpen by mutableStateOf(appSettings.ui.inspectorOpen)
    private set

  /** Selected rows of the task list. */
  var selectedKeys by mutableStateOf(emptySet<TaskKey>())

  private val focusSearch = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

  /** Emits when the search field should take focus, such as on ⌘F. */
  val focusSearchRequests: SharedFlow<Unit> = focusSearch.asSharedFlow()

  private val showDownloadsRequests = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

  /** Emits when the shell should show the Downloads list, such as after adding from Discover. */
  val downloadsRequests: SharedFlow<Unit> = showDownloadsRequests.asSharedFlow()

  private val settingsCache = mutableMapOf<InstanceEntry, InstanceSettingsController>()

  /** Download, network and torrent settings of the active device. */
  var instanceSettings by mutableStateOf(settingsForActive())
    private set

  var discoveryState by mutableStateOf<DiscoveryState>(
    DiscoveryState.Idle
  )
    private set
  private var discoveryJob: Job? = null
  var switchingInstance by
    mutableStateOf<InstanceEntry?>(null)
  var unauthorizedInstance by
    mutableStateOf<RemoteInstance?>(null)
  var resolveState by mutableStateOf<ResolveState>(
    ResolveState.Idle
  )
    private set

  /** A dropped `.torrent` file added in place of a typed URL. */
  var droppedFile by mutableStateOf<DroppedFile?>(null)
    private set

  /** The URL or file whose resolution [resolveState] reflects. */
  private var resolving: Any? = null

  /** Speed mode of the embedded device; full speed when the host keeps no [speedMode]. */
  private val localMode: StateFlow<SpeedMode> = speedMode?.mode ?: MutableStateFlow(SpeedMode.Full)

  // Built on the main thread, which owns the settings cache; the models collect them elsewhere.
  private val listSources: StateFlow<List<TaskListSource>> =
    combine(activeInstance, snapshotFlow { aiSettings.available }) { entry, discover ->
      listOfNotNull(entry?.let { listSourceOf(it, discover) })
    }.stateIn(scope, SharingStarted.Eagerly, emptyList())

  // The embedded device is watched even while another one shows, so its speed mode keeps
  // recording its speed.
  private val pulseSources: StateFlow<List<PulseSource>> =
    combine(instances, activeInstance) { entries, active ->
      entries.filter { it is EmbeddedInstance && it != active } + listOfNotNull(active)
    }.distinctUntilChanged()
      .map { entries -> entries.map(::pulseSourceOf) }
      .stateIn(scope, SharingStarted.Eagerly, emptyList())

  /** Rows of the active device's tasks, and the tab, search and order the Downloads list shows. */
  val taskList: TaskListModel = TaskListModel(
    sources = listSources,
    scope = scope,
    filter = filterState.flow,
    query = searchState.flow,
    arrangement = arrangementState.flow,
    frozen = frozenState.flow,
    clock = clock,
  )

  /** Speed of each task once a second, for the inspector's Activity chart. */
  val speedHistory: SpeedHistoryStore = SpeedHistoryStore(taskList.rows, scope, clock)

  /** Speed, counts, speed limit, free space and health of the active device. */
  val pulse: PulseModel = PulseModel(
    sources = pulseSources,
    pulseScope = activeInstance.map { PulseScope.Device(it?.deviceId ?: LOCAL_DEVICE_ID) },
    mode = combine(activeInstance, localMode) { entry, mode ->
      if (entry is RemoteInstance) SpeedMode.Full else mode
    },
    scope = scope,
  )

  init {
    scope.launch {
      // Opened files, then links handed to the app, show one at a time in the add dialog, and
      // wait for a backend if none is connected. Pending ones survive a recreated UI and show
      // again.
      combine(incoming.pending, incoming.pendingLinks, activeInstance) { files, links, entry ->
        Triple(files.firstOrNull(), links.firstOrNull(), entry != null)
      }.collect { (file, links, connected) ->
        // What the dialog showed leaves the queue once the dialog closes on it.
        if (openedDownload != file) openedDownload = null
        if (openedLinks != links) openedLinks = null
        when {
          file == null && links == null -> Unit
          !connected -> showAddRemoteDialog = true
          openedDownload != null || openedLinks != null -> Unit
          file != null -> {
            openedDownload = file
            openIntake(intakeRequest ?: IntakeRequest())
            resolveDroppedFile(DroppedFile(file.label) { file.content })
          }
          links != null -> {
            openedLinks = links
            openIntake(IntakeRequest(text = links.urls.joinToString("\n")))
          }
        }
      }
    }
    scope.launch {
      incoming.failures.collect {
        postError("Couldn't open ${it.label}", detail = it.message)
      }
    }
    scope.launch {
      messages.active.collect { active ->
        latestError = active.lastOrNull { it.level == MessageLevel.Error }
      }
    }
    scope.launch {
      activeInstance.collect {
        instanceSettings = settingsForActive()
        instanceSettings.loadDownload()
      }
    }
    scope.launch {
      instances.collect { entries -> settingsCache.keys.retainAll(entries.toSet()) }
    }
    scope.launch {
      // A speed mode changes the embedded device's limit; read it back for the speed readout.
      localMode.drop(1).collect {
        instances.value.firstOrNull { it is EmbeddedInstance }?.let(::settingsFor)?.loadDownload()
      }
    }
  }

  /**
   * Settings controller of [entry], built once per device, so Settings can edit any connected
   * device without switching to it.
   */
  fun settingsFor(entry: InstanceEntry): InstanceSettingsController =
    settingsCache.getOrPut(entry) {
      InstanceSettingsController(
        api = entry.instance,
        local = appSettings.takeIf { entry is EmbeddedInstance },
        scope = scope,
        applyTorrent = instanceManager::applyTorrentSettings,
      )
    }

  private fun settingsForActive(): InstanceSettingsController =
    activeInstance.value?.let(::settingsFor)
      ?: InstanceSettingsController(activeApi.value, local = null, scope = scope)

  /** Reads the active device's settings again; call it when the window regains focus. */
  fun onWindowFocused() {
    instanceSettings.loadDownload()
  }

  /**
   * Opens the add sheet with [request]; asks for a device first when none is connected. Another
   * request starts a fresh form, without the link or file the last one resolved.
   */
  fun openIntake(request: IntakeRequest = IntakeRequest()) {
    if (activeInstance.value == null) {
      showAddRemoteDialog = true
      return
    }
    if (request != intakeRequest) resetResolveState()
    intakeRequest = request
    showAddDialog = true
  }

  /**
   * Handle "New Task" action. If no backend is available,
   * show the add-remote-server dialog instead.
   */
  fun requestAddDownload() {
    openIntake()
  }

  /** Closes the add dialog; the next opened download or links, if any are waiting, then show. */
  fun closeAddDialog() {
    resetResolveState()
    showAddDialog = false
    intakeRequest = null
    openedDownload?.let(incoming::complete)
    openedLinks?.let(incoming::complete)
  }

  /** Opens Settings at [target]. */
  fun openSettings(target: SettingsTarget = SettingsTarget(SettingsTarget.Page.General)) {
    settingsRequest = target
  }

  /** Closes Settings. */
  fun closeSettings() {
    settingsRequest = null
  }

  /** Fills Discover with [request], starts the search and asks the shell to show Discover. */
  fun openDiscover(request: DiscoverRequest) {
    aiDiscover.discover(request)
    discoverRequest = request
  }

  /** Marks [discoverRequest] as shown. */
  fun discoverRequestHandled() {
    discoverRequest = null
  }

  /** Shows [key] in the inspector, or clears it with `null`. */
  fun inspect(key: TaskKey?) {
    inspectedTask = key
  }

  /** Shows or hides the docked inspector and remembers the choice. */
  fun updateInspectorOpen(open: Boolean) {
    inspectorOpen = open
    appSettings.saveUi { it.copy(inspectorOpen = open) }
  }

  /** Asks the search field to take focus. */
  fun requestSearchFocus() {
    focusSearch.tryEmit(Unit)
  }

  /** Asks the shell to show the Downloads list on the [filter] tab. */
  fun showDownloads(filter: StatusFilter = StatusFilter.All) {
    statusFilter = filter
    showDownloadsRequests.tryEmit(Unit)
  }

  fun resolveUrl(url: String) {
    resolving = url
    resolveState = ResolveState.Resolving
    scope.launch {
      runCatching {
        activeApi.value.resolve(url)
      }.onSuccess { result ->
        if (resolving == url) {
          resolveState = ResolveState.Resolved(result)
        }
      }.onFailure { e ->
        if (e is CancellationException) throw e
        if (resolving == url) {
          resolveState = ResolveState.Error(
            message = e.message ?: "Failed to resolve URL",
            cause = e,
          )
        }
      }
    }
  }

  /**
   * Adds dropped files: the first `.torrent` file opens the add dialog, which resolves it on the
   * active device; link lists such as `.txt` files open the add sheet with their text.
   */
  fun addDroppedFiles(files: List<DroppedFile>) {
    val torrent = files.firstOrNull {
      it.name.endsWith(".torrent", ignoreCase = true)
    }
    if (torrent != null) {
      requestAddDownload()
      if (showAddDialog) resolveDroppedFile(torrent)
      return
    }
    val linkLists = files.filter {
      it.name.substringAfterLast('.', "").lowercase() in LINK_LIST_EXTENSIONS
    }
    if (linkLists.isEmpty()) {
      postError("Only .torrent files and link lists can be dropped to add downloads")
      return
    }
    scope.launch {
      val text = linkLists.mapNotNull { file ->
        try {
          file.readBytes(MAX_LINK_LIST_BYTES).decodeToString()
        } catch (e: CancellationException) {
          throw e
        } catch (e: Exception) {
          postError("Couldn't read ${file.name}", detail = e.message, cause = e)
          null
        }
      }.joinToString("\n").trim()
      if (text.isNotEmpty()) openIntake(IntakeRequest(text = text))
    }
  }

  /** Opens the add sheet for text dropped on the app, such as a link dragged from a browser. */
  fun addDroppedText(text: String) {
    val trimmed = text.trim()
    if (trimmed.isNotEmpty()) openIntake(IntakeRequest(text = trimmed))
  }

  /** Reads [file] and resolves its content; also retries a failed attempt. */
  fun resolveDroppedFile(file: DroppedFile) {
    droppedFile = file
    resolving = file
    resolveState = ResolveState.Resolving
    scope.launch {
      runCatching {
        val content = file.readBytes(MAX_DROPPED_FILE_BYTES)
        activeApi.value.resolveContent(content, file.name)
      }.onSuccess { result ->
        if (resolving === file) {
          resolveState = ResolveState.Resolved(result)
        }
      }.onFailure { e ->
        if (e is CancellationException) throw e
        if (resolving === file) {
          resolveState = ResolveState.Error(
            message = when (e) {
              is KetchError.SourceError -> "${file.name} is not a valid torrent file"
              is KetchError.Unsupported -> "${file.name} is not a supported file"
              else -> e.message ?: "Failed to read ${file.name}"
            },
            cause = e,
          )
        }
      }
    }
  }

  /** Clears the resolved URL or dropped file. */
  fun resetResolveState() {
    resolving = null
    droppedFile = null
    resolveState = ResolveState.Idle
  }

  fun startDownload(
    url: String,
    fileName: String,
    speedLimit: SpeedLimit,
    priority: DownloadPriority,
    schedule: DownloadSchedule = DownloadSchedule.Immediate,
    resolvedUrl: ResolvedSource? = null,
    selectedFileIds: Set<String> = emptySet(),
  ) {
    val entry = activeInstance.value
    val request = try {
      DownloadRequest(
        url = url,
        destination = fileName.ifBlank { null }
          ?.let { Destination(it) },
        speedLimit = speedLimit,
        priority = priority,
        schedule = schedule,
        resolvedSource = resolvedUrl,
        selectedFileIds = selectedFileIds,
      )
    } catch (e: IllegalArgumentException) {
      postError("Couldn't add the download", detail = e.message, cause = e)
      return
    }
    scope.launch { addRequests(entry, listOf(request)) }
  }

  /**
   * Adds [urls] to [target] at once with the options last used on that device (folder, priority
   * and connections from [com.linroid.ketch.config.UiPreferences.intake]). The toast offers
   * Options and, for 8 seconds, Undo, which removes the new tasks and their files.
   */
  fun quickAdd(urls: List<String>, target: InstanceEntry? = activeInstance.value): Job =
    scope.launch {
      val entry = target ?: run {
        showAddRemoteDialog = true
        return@launch
      }
      val defaults = appSettings.ui.intake[entry.deviceId] ?: IntakePreferences()
      val destination = defaults.folder?.let { folderDestination(entry, it) }
      val requests = urls.mapNotNull { url ->
        try {
          DownloadRequest(
            url = url,
            destination = destination,
            priority = defaults.priority,
            connections = defaults.connections.coerceAtLeast(0),
          )
        } catch (e: IllegalArgumentException) {
          postError("Couldn't add $url", detail = e.message, cause = e)
          null
        }
      }
      if (requests.isEmpty()) return@launch
      if (statusFilter != StatusFilter.All) statusFilter = StatusFilter.All
      addRequests(entry, requests, offerOptions = true)
    }

  fun switchInstance(instance: InstanceEntry) {
    if (instance == activeInstance.value ||
      switchingInstance != null
    ) return
    switchingInstance = instance
    scope.launch {
      try {
        instanceManager.switchTo(instance)
        showInstanceSelector = false
        appSettings.saveUi { it.copy(lastDeviceId = instance.deviceId) }
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        log.w { "Couldn't switch to ${instance.label}: ${e.describeCauses()}" }
        postError("Couldn't switch to ${instance.label}", detail = e.message, cause = e)
      } finally {
        switchingInstance = null
      }
    }
  }

  fun addRemoteServer(
    host: String,
    port: Int,
    token: String?,
  ) {
    try {
      instanceManager.addRemote(host, port, token)
    } catch (e: Exception) {
      log.w { "Couldn't add $host:$port: ${e.describeCauses()}" }
      postError("Couldn't add $host:$port", detail = e.message, cause = e)
    }
  }

  fun discoverRemoteServers(port: Int = 8642) {
    if (discoveryState is DiscoveryState.Discovering) return
    discoveryState = DiscoveryState.Discovering()
    discoveryJob = scope.launch {
      try {
        val servers = withContext(Dispatchers.Default) {
          lanServerDiscovery.discover(port)
        }
        discoveryState = DiscoveryState.Finished(servers)
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        discoveryState = DiscoveryState.Error(
          e.message ?: "Failed to discover LAN servers"
        )
      }
    }
  }

  fun stopDiscovery() {
    discoveryJob?.cancel()
    discoveryJob = null
    val current = discoveryState
    discoveryState = DiscoveryState.Finished(
      servers = if (current is DiscoveryState.Discovering) {
        current.servers
      } else {
        emptyList()
      }
    )
  }

  fun resetDiscovery() {
    discoveryJob?.cancel()
    discoveryJob = null
    discoveryState = DiscoveryState.Idle
  }

  fun removeInstance(instance: InstanceEntry) {
    scope.launch {
      try {
        instanceManager.removeInstance(instance)
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        log.w { "Couldn't remove ${instance.label}: ${e.describeCauses()}" }
        postError("Couldn't remove ${instance.label}", detail = e.message, cause = e)
      }
    }
  }

  fun reconnectWithToken(
    instance: RemoteInstance,
    token: String,
  ) {
    unauthorizedInstance = null
    scope.launch {
      try {
        instanceManager.reconnectWithToken(instance, token)
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        log.w { "Couldn't reconnect to ${instance.label}: ${e.describeCauses()}" }
        postError("Couldn't reconnect to ${instance.label}", detail = e.message, cause = e)
      }
    }
  }

  /**
   * Runs [block] on [task] in the app scope, so leaving the screen never cancels it.
   *
   * While it runs, [pending] holds the task with [label]. A failure is logged and posted as one
   * Error message naming the device, with a Try again button. Cancelling the command posts
   * nothing; a `CancellationException` thrown by the call itself, such as from a closed client,
   * is a failure.
   *
   * @param label what the command does, in lower case, such as "set speed limit"; the error
   *   reads "Couldn't {label} on {device}".
   */
  fun runTaskCommand(
    task: DownloadTask,
    label: String,
    block: suspend DownloadTask.() -> Unit,
  ): Job {
    val device = deviceOf(task)
    val key = TaskKey(device?.deviceId ?: LOCAL_DEVICE_ID, task.taskId)
    return scope.launch {
      trackPending(key, label) {
        catchingUnlessCancelled { task.block() }.onFailure { e ->
          val deviceName = nameOf(device)
          log.w { "Couldn't $label on $deviceName: taskId=${task.taskId} ${e.describeCauses()}" }
          postError(
            title = "Couldn't $label on $deviceName",
            detail = e.message,
            cause = e,
            taskKey = key,
            actions = listOf(MessageAction("Try again") { runTaskCommand(task, label, block) }),
          )
        }
      }
    }
  }

  /**
   * Pauses every queued task, then every downloading one, on each of [targets], so the queue
   * cannot start a task that was waiting. Scheduled tasks keep their schedule. Undo resumes
   * exactly the tasks this paused.
   */
  fun pauseAll(targets: List<InstanceEntry> = listOfNotNull(activeInstance.value)): Job =
    scope.launch {
      val results = supervisorScope {
        targets.map { entry -> async { entry to pauseDevice(entry) } }.awaitAll()
      }
      val paused = results.flatMap { (_, result) -> result.paused }
      val failures = results.flatMap { (_, result) -> result.failures }
      val scheduled = targets.flatMap { visibleTasks(it) }
        .mapNotNull { (it.state.value as? DownloadState.Scheduled)?.schedule }
      if (paused.isEmpty()) {
        // Nothing to undo, so no operation is registered for ⌘Z to land on.
        if (failures.isEmpty()) {
          messages.post(MessageLevel.Info, "Nothing to pause")
        } else {
          reportFailures("pause", failures)
        }
        return@launch
      }
      val op = pendingOps.register(label = "Pause All", undo = {
        reportFailures("resume", runEach(paused) { it.resume() })
      })
      val title = buildString {
        append("Paused ${downloads(paused.size)}")
        if (targets.size > 1) append(" on ${targets.size} devices")
        scheduledNote(scheduled)?.let { append(" · ").append(it) }
        if (failures.isNotEmpty()) append(" · ${failures.size} failed")
      }
      messages.post(
        level = if (failures.isEmpty()) MessageLevel.Success else MessageLevel.Warning,
        title = title,
        detail = failures.firstOrNull()?.second?.message,
        actions = listOf(undoAction(op)),
        cause = failures.firstOrNull()?.second,
      )
    }

  /** Resumes every paused task on each of [targets]. */
  fun resumeAll(targets: List<InstanceEntry> = listOfNotNull(activeInstance.value)): Job =
    scope.launch {
      val tasks = targets.flatMap { visibleTasks(it) }
        .filter { it.state.value is DownloadState.Paused }
      if (tasks.isEmpty()) return@launch
      val results = runEach(tasks) { it.resume() }
      reportBatch("Resumed", "resume", results, devices = targets.size)
    }

  /**
   * Retries every failed task on each of [targets]. Tasks whose progress cannot be reused (the
   * file changed, the saved progress is damaged, or the server refused to resume) start over.
   */
  fun retryFailed(targets: List<InstanceEntry> = listOfNotNull(activeInstance.value)): Job =
    scope.launch {
      val tasks = targets.flatMap { entry ->
        visibleTasks(entry).filter { it.state.value is DownloadState.Failed }.map { entry to it }
      }
      if (tasks.isEmpty()) return@launch
      val results = runEach(tasks) { (entry, task) ->
        val failed = task.state.value as? DownloadState.Failed
        if (failed != null && failed.error.needsFreshStart()) {
          restart(entry, task)
        } else {
          task.resume()
        }
      }
      reportBatch("Retrying", "retry", results.map { (pair, error) -> pair.second to error })
    }

  /**
   * Removes the finished tasks of each of [targets] from the list, keeping their files. The rows
   * hide at once and are removed when the Undo window ends.
   */
  fun clearCompleted(targets: List<InstanceEntry> = listOfNotNull(activeInstance.value)) {
    val tasks = targets.flatMap { entry ->
      visibleTasks(entry).filter { it.state.value is DownloadState.Completed }
    }
    if (tasks.isEmpty()) return
    deferRemoval(tasks, deleteFiles = false, label = "Clear Finished") { count ->
      "Cleared $count finished ${if (count == 1) "download" else "downloads"}"
    }
  }

  /**
   * Removes [tasks] from the list, and their files when [deleteFiles] is set. The rows hide at
   * once and are removed when the Undo window ends or the app closes.
   */
  fun remove(tasks: List<DownloadTask>, deleteFiles: Boolean = false) {
    if (tasks.isEmpty()) return
    deferRemoval(tasks, deleteFiles, label = "Remove") { count ->
      if (count == 1) "Removed ${tasks.single().displayName()}"
      else "Removed ${downloads(count)}"
    }
  }

  /**
   * Stops [tasks] and discards their progress. They pause at once and are canceled when the Undo
   * window ends; Undo resumes the ones that were running or waiting.
   */
  fun cancel(tasks: List<DownloadTask>): Job = scope.launch {
    if (tasks.isEmpty()) return@launch
    val running = tasks.filter {
      val state = it.state.value
      state is DownloadState.Downloading || state is DownloadState.Queued
    }
    val paused = runEach(running) { it.pause() }.filter { it.second == null }.map { it.first }
    val op = pendingOps.register(
      label = "Discard Progress",
      commit = { reportFailures("discard progress of", runEach(tasks) { it.cancel() }) },
      undo = { reportFailures("resume", runEach(paused) { it.resume() }) },
    )
    val title = if (tasks.size == 1) {
      "Discarded progress of ${tasks.single().displayName()}"
    } else {
      "Discarded progress of ${downloads(tasks.size)}"
    }
    messages.post(MessageLevel.Success, title, actions = listOf(undoAction(op)))
  }

  /**
   * Starts [task] over as a new task on the same device and removes the old one, with its
   * partial file. A finished task keeps its file.
   */
  fun redownload(task: DownloadTask): Job = scope.launch {
    val entry = deviceOf(task) ?: return@launch
    val key = TaskKey(entry.deviceId, task.taskId)
    trackPending(key, "download again") {
      val name = task.displayName()
      catchingUnlessCancelled { restart(entry, task) }
        .onSuccess {
          messages.post(MessageLevel.Success, "Restarted $name", deviceId = entry.deviceId)
        }
        .onFailure { e ->
          log.w { "Couldn't restart taskId=${task.taskId}: ${e.describeCauses()}" }
          postError(
            title = "Couldn't download $name again on ${nameOf(entry)}",
            detail = e.message,
            cause = e,
            taskKey = key,
          )
        }
    }
  }

  /**
   * Retries [task] as its Retry button promises: a paused or failed task resumes, and a failed
   * task whose progress cannot be reused (see [retryFailed]) or a canceled one starts over with
   * [redownload]. Other states are left alone.
   */
  fun retry(task: DownloadTask) {
    val current = task.state.value
    when {
      current is DownloadState.Canceled -> redownload(task)
      current is DownloadState.Failed && current.error.needsFreshStart() -> redownload(task)
      current is DownloadState.Failed || current is DownloadState.Paused -> {
        val verb = if (current is DownloadState.Failed) "retry" else "resume"
        runTaskCommand(task, "$verb ${task.displayName()}") { resume() }
      }
    }
  }

  /**
   * Starts [task] at once: it becomes [DownloadPriority.URGENT], which may pause a running task
   * to make room. The toast names that task, and Undo restores the previous priority and state.
   */
  fun startNow(task: DownloadTask): Job {
    val entry = deviceOf(task)
    val before = task.state.value
    val previousPriority = task.requestState.value.priority
    val name = task.displayName()
    val running = entry?.let { visibleTasks(it) }.orEmpty()
      .filter { it !== task && it.state.value is DownloadState.Downloading }
    return runTaskCommand(task, "start $name now") {
      if (before is DownloadState.Scheduled) reschedule(DownloadSchedule.Immediate)
      setPriority(DownloadPriority.URGENT)
      if (before is DownloadState.Paused || before is DownloadState.Failed) resume()
      withTimeoutOrNull(START_TIMEOUT) { state.first { it is DownloadState.Downloading } }
      val preempted = running.filter { it.state.value is DownloadState.Queued }
      val op = pendingOps.register(label = "Start Now", undo = {
        val results = runEach(listOf(task)) { undoStartNow(it, before, previousPriority) }
        reportFailures("undo start now of", results)
      })
      val title = buildString {
        append("Started $name now")
        if (preempted.isNotEmpty()) {
          val names = preempted.joinToString(", ") { it.displayName() }
          append(" · paused $names to make room")
        }
      }
      messages.post(MessageLevel.Success, title, actions = listOf(undoAction(op)))
    }
  }

  /**
   * Adds [tasks] to [target] with the same headers, speed limit, priority and connections; they
   * start over there. With [move], each task whose copy was added is removed here when the Undo
   * window ends; Undo instead removes the copies.
   */
  fun sendTo(tasks: List<DownloadTask>, target: InstanceEntry, move: Boolean = false): Job =
    scope.launch {
      if (tasks.isEmpty()) return@launch
      val results = supervisorScope {
        tasks.map { task ->
          async {
            val request = task.requestState.value.forDevice()
            task to catchingUnlessCancelled { target.instance.download(request) }
          }
        }.awaitAll()
      }
      val sent = results.mapNotNull { (task, result) -> result.getOrNull()?.let { task to it } }
      val failures = results.mapNotNull { (task, result) ->
        result.exceptionOrNull()?.let { task to it }
      }
      val targetName = nameOf(target)
      failures.forEach { (task, e) ->
        log.w { "Couldn't send taskId=${task.taskId} to $targetName: ${e.describeCauses()}" }
      }
      if (sent.isEmpty()) {
        val (task, e) = failures.first()
        postError(
          title = if (failures.size == 1) {
            "Couldn't send ${task.displayName()} to $targetName"
          } else {
            "Couldn't send ${downloads(failures.size)} to $targetName"
          },
          detail = e.message,
          cause = e,
          actions = listOf(MessageAction("Try again") { sendTo(tasks, target, move) }),
        )
        return@launch
      }
      val what = if (sent.size == 1) sent.single().first.displayName()
      else downloads(sent.size)
      val failedNote = if (failures.isEmpty()) "" else " · ${failures.size} failed"
      if (move) {
        val sources = sent.map { it.first }
        val op = pendingOps.register(
          label = "Move",
          hides = hide(sources),
          commit = {
            reportFailures("remove", runEach(sources) { it.remove(deleteFiles = false) })
          },
          undo = {
            val copies = sent.map { it.second }
            reportFailures("remove", runEach(copies) { it.remove(deleteFiles = true) })
          },
        )
        messages.post(
          level = if (failures.isEmpty()) MessageLevel.Success else MessageLevel.Warning,
          title = "Moved $what to $targetName$failedNote",
          deviceId = target.deviceId,
          actions = listOf(undoAction(op)),
        )
      } else {
        messages.post(
          level = if (failures.isEmpty()) MessageLevel.Success else MessageLevel.Warning,
          title = "Sent $what to $targetName$failedNote",
          deviceId = target.deviceId,
          actions = listOf(
            MessageAction("Show") { switchInstance(target) },
            MessageAction("Remove here") { remove(sent.map { it.first }) },
          ),
        )
      }
    }

  /** Starts the AI discovery search for [query], limited to the comma-separated [sites]. */
  fun aiDiscover(query: String, sites: String) {
    aiDiscover.discover(query, sites)
  }

  /** Adds [candidates] to the active device, each on its own, and closes the results. */
  fun aiDownloadSelected(candidates: List<AiCandidate>) {
    showAddDialog = false
    intakeRequest = null
    val entry = activeInstance.value
    val api = activeApi.value
    val query = aiDiscover.draft.submittedQuery
    resetAiDiscover()
    scope.launch {
      val result = aiDiscover.add(api, candidates, query)
      reportAdded(
        entry = entry,
        added = result.added,
        failures = result.failed.map { (candidate, e) -> candidate.title to e },
      )
    }
  }

  fun resetAiDiscover() {
    aiDiscover.reset()
  }

  /**
   * Dismisses every error message still on screen, so the banner clears at once instead of
   * showing the next older error.
   */
  fun dismissError() {
    messages.active.value.filter { it.level == MessageLevel.Error }
      .forEach { messages.dismiss(it.id) }
    latestError = null
  }

  /** Reports [event] from the host's activity monitor as a message. */
  fun report(event: ActivityEvent) {
    when (event) {
      is ActivityEvent.Added -> messages.post(
        level = MessageLevel.Info,
        title = onDevice(event.taskKey.deviceId, "Added ${displayName(event.request)}"),
        taskKey = event.taskKey,
        deviceId = event.taskKey.deviceId,
        toast = ToastMode.Silent,
      )
      is ActivityEvent.Completed -> messages.post(
        level = MessageLevel.Success,
        title = onDevice(event.taskKey.deviceId, "Download complete"),
        detail = (listOf(displayName(event.request, event.state)) + transferSummary(event.state))
          .joinToString(" · "),
        taskKey = event.taskKey,
        deviceId = event.taskKey.deviceId,
        notify = true,
      )
      is ActivityEvent.CompletedBatch -> {
        val bytes = event.completions.sumOf { it.state.totalBytes ?: 0L }
        messages.post(
          level = MessageLevel.Success,
          title = "${downloads(event.completions.size)} finished",
          detail = formatBytes(bytes).takeIf { bytes > 0 },
          notify = true,
        )
      }
      is ActivityEvent.Failed -> messages.post(
        level = MessageLevel.Error,
        title = onDevice(event.taskKey.deviceId, "Download failed"),
        detail = displayName(event.request, event.state),
        taskKey = event.taskKey,
        deviceId = event.taskKey.deviceId,
        actions = listOf(MessageAction("Retry") { retry(event.taskKey) }),
        notify = true,
        cause = event.state.error,
      )
      is ActivityEvent.Recovered -> messages.post(
        level = MessageLevel.Info,
        title = onDevice(
          event.deviceId,
          "Resuming ${downloads(event.count)} from your last session",
        ),
        deviceId = event.deviceId,
      )
      is ActivityEvent.QueueDrained -> messages.post(
        level = MessageLevel.Success,
        title = onDevice(event.deviceId, "All downloads finished"),
        detail = listOfNotNull(
          if (event.files == 1) "1 file" else "${event.files} files",
          formatBytes(event.bytes).takeIf { event.bytes > 0 },
        ).joinToString(" · "),
        deviceId = event.deviceId,
        notify = true,
      )
      is ActivityEvent.DeviceOffline -> messages.post(
        level = MessageLevel.Warning,
        title = "${deviceName(event.deviceId)} went offline",
        deviceId = event.deviceId,
      )
      is ActivityEvent.DeviceOnline -> messages.post(
        level = MessageLevel.Success,
        title = "Reconnected to ${deviceName(event.deviceId)}",
        deviceId = event.deviceId,
      )
    }
  }

  /** Commits the pending operations; call it when the app closes. */
  fun close() {
    pendingOps.flush()
  }

  /** The device that runs [task]: the one whose task list holds it, else the active one. */
  fun deviceOf(task: DownloadTask): InstanceEntry? =
    instances.value.firstOrNull { entry -> entry.instance.tasks.value.any { it === task } }
      ?: activeInstance.value

  /** Key of [task] across devices. */
  fun keyOf(task: DownloadTask): TaskKey =
    TaskKey(deviceOf(task)?.deviceId ?: LOCAL_DEVICE_ID, task.taskId)

  private fun visibleTasks(entry: InstanceEntry): List<DownloadTask> {
    val hidden = pendingOps.hidden.value
    return entry.instance.tasks.value.filter { TaskKey(entry.deviceId, it.taskId) !in hidden }
  }

  /** Tasks of [entry] as they change, without the ones a pending operation hides. */
  private fun visibleTasksOf(entry: InstanceEntry): Flow<List<DownloadTask>> {
    val deviceId = entry.deviceId
    return combine(entry.instance.tasks, pendingOps.hidden) { tasks, hidden ->
      if (hidden.isEmpty()) tasks else tasks.filter { TaskKey(deviceId, it.taskId) !in hidden }
    }
  }

  private fun listSourceOf(entry: InstanceEntry, canDiscover: Boolean): TaskListSource {
    val settings = settingsFor(entry)
    val remote = entry is RemoteInstance
    return TaskListSource(
      deviceId = entry.deviceId,
      device = DeviceInfo(
        name = entry.label,
        capabilities = if (remote) {
          RowCapabilities.remote(canDiscover = canDiscover)
        } else {
          RowCapabilities.local(canDiscover = canDiscover)
        },
      ),
      tasks = visibleTasksOf(entry),
      config = snapshotFlow { settings.download },
      slowLane = if (remote) flowOf(false) else localMode.map { it.isSlowLane },
    )
  }

  private fun pulseSourceOf(entry: InstanceEntry): PulseSource {
    val settings = settingsFor(entry)
    return PulseSource(
      deviceId = entry.deviceId,
      name = if (entry is EmbeddedInstance) localDeviceNoun() else entry.label,
      tasks = visibleTasksOf(entry),
      config = snapshotFlow { settings.download },
      status = entry.instance::status,
      health = when (entry) {
        is RemoteInstance -> entry.connectionState.map { it.toDeviceHealth() }
        else -> serverState.map { it.toDeviceHealth() }
      },
    )
  }

  private class PauseResult(
    val paused: List<DownloadTask>,
    val failures: List<Pair<DownloadTask, Throwable>>,
  )

  private suspend fun pauseDevice(entry: InstanceEntry): PauseResult {
    val paused = mutableListOf<DownloadTask>()
    val failures = mutableListOf<Pair<DownloadTask, Throwable>>()
    val attempted = mutableSetOf<String>()
    // A paused download frees its slot, and the queue may start a task that was not queued when
    // the round began, such as one a preemption re-queued; later rounds catch those.
    repeat(MAX_PAUSE_ROUNDS) {
      val tasks = visibleTasks(entry).filter { it.taskId !in attempted }
      val queued = tasks.filter { it.state.value is DownloadState.Queued }
      val running = tasks.filter { it.state.value is DownloadState.Downloading }
      if (queued.isEmpty() && running.isEmpty()) return PauseResult(paused, failures)
      for (group in listOf(queued, running)) {
        attempted += group.map { it.taskId }
        runEach(group) { it.pause() }.forEach { (task, error) ->
          if (error == null) paused += task else failures += task to error
        }
      }
    }
    return PauseResult(paused, failures)
  }

  private fun deferRemoval(
    tasks: List<DownloadTask>,
    deleteFiles: Boolean,
    label: String,
    title: (Int) -> String,
  ) {
    val keys = hide(tasks)
    val op = pendingOps.register(
      label = label,
      hides = keys,
      commit = { reportFailures("remove", runEach(tasks) { it.remove(deleteFiles) }) },
    )
    messages.post(MessageLevel.Success, title(tasks.size), actions = listOf(undoAction(op)))
  }

  /** Keys of [tasks], which leave the selection and the inspector as their rows hide. */
  private fun hide(tasks: List<DownloadTask>): Set<TaskKey> {
    val keys = tasks.mapTo(mutableSetOf()) { keyOf(it) }
    selectedKeys = selectedKeys - keys
    if (inspectedTask in keys) inspectedTask = null
    return keys
  }

  /**
   * Removes [task] and adds its request again on [entry], to start at once. The old task and its
   * partial file go first, since the new task writes to the same path.
   */
  private suspend fun restart(entry: InstanceEntry, task: DownloadTask) {
    val request = task.requestState.value.copy(
      schedule = DownloadSchedule.Immediate,
      resolvedSource = null,
    )
    task.remove(deleteFiles = task.state.value !is DownloadState.Completed)
    entry.instance.download(request)
  }

  private suspend fun undoStartNow(
    task: DownloadTask,
    before: DownloadState,
    previousPriority: DownloadPriority,
  ) {
    task.setPriority(previousPriority)
    when (before) {
      is DownloadState.Scheduled -> task.reschedule(before.schedule)
      is DownloadState.Queued -> {
        // Pausing frees the slot for the task the start preempted; resuming queues this again.
        task.pause()
        task.resume()
      }
      is DownloadState.Paused -> task.pause()
      else -> Unit
    }
  }

  private fun retry(key: TaskKey) {
    val entry = instances.value.firstOrNull { it.deviceId == key.deviceId } ?: return
    val task = entry.instance.tasks.value.firstOrNull { it.taskId == key.taskId } ?: return
    retry(task)
  }

  /** Adds each of [requests] to [entry] on its own and reports the outcome. */
  private suspend fun addRequests(
    entry: InstanceEntry?,
    requests: List<DownloadRequest>,
    offerOptions: Boolean = false,
  ) {
    val api = entry?.instance ?: activeApi.value
    val results = supervisorScope {
      requests.map { request ->
        async { request to catchingUnlessCancelled { api.download(request) } }
      }.awaitAll()
    }
    val added = results.mapNotNull { it.second.getOrNull() }
    val failed = results.mapNotNull { (request, result) ->
      result.exceptionOrNull()?.let { request to it }
    }
    failed.forEach { (request, e) ->
      log.w { "Couldn't add ${redactUrl(request.url)}: ${e.describeCauses()}" }
    }
    reportAdded(
      entry = entry,
      added = added,
      failures = failed.map { (request, e) -> displayName(request) to e },
      offerOptions = offerOptions,
      retry = { scope.launch { addRequests(entry, failed.map { it.first }, offerOptions) } },
    )
  }

  private fun reportAdded(
    entry: InstanceEntry?,
    added: List<DownloadTask>,
    failures: List<Pair<String, Throwable>>,
    offerOptions: Boolean = false,
    retry: (() -> Unit)? = null,
  ) {
    val deviceName = nameOf(entry)
    if (added.isEmpty()) {
      val (name, e) = failures.firstOrNull() ?: return
      postError(
        title = "Couldn't add " + if (failures.size == 1) name else downloads(failures.size),
        detail = e.message,
        cause = e,
        actions = listOfNotNull(retry?.let { MessageAction("Try again", it) }),
      )
      return
    }
    val op = pendingOps.register(label = "Add", timeout = ADD_UNDO_WINDOW, undo = {
      reportFailures("remove", runEach(added) { it.remove(deleteFiles = true) })
    })
    val single = added.singleOrNull()?.takeIf { failures.isEmpty() }
    val title = if (single != null) {
      "Added ${single.displayName()} → $deviceName"
    } else {
      "Added ${downloads(added.size)} → $deviceName" +
        if (failures.isEmpty()) "" else " · ${failures.size} failed"
    }
    val actions = buildList {
      if (single != null && offerOptions && entry != null) {
        add(
          MessageAction("Options") {
            openIntake(IntakeRequest(editTask = TaskKey(entry.deviceId, single.taskId)))
          },
        )
      }
      add(undoAction(op))
    }
    messages.post(
      level = if (failures.isEmpty()) MessageLevel.Success else MessageLevel.Warning,
      title = title,
      detail = failures.firstOrNull()?.first,
      taskKey = single?.let { TaskKey(entry?.deviceId ?: LOCAL_DEVICE_ID, it.taskId) },
      deviceId = entry?.deviceId,
      actions = actions,
      cause = failures.firstOrNull()?.second,
    )
  }

  private fun reportBatch(
    verb: String,
    command: String,
    results: List<Pair<DownloadTask, Throwable?>>,
    devices: Int = 1,
  ) {
    val done = results.count { it.second == null }
    val failures = results.mapNotNull { (task, error) -> error?.let { task to it } }
    if (done == 0) {
      reportFailures(command, results)
      return
    }
    val title = buildString {
      append("$verb ${downloads(done)}")
      if (devices > 1) append(" on $devices devices")
      if (failures.isNotEmpty()) append(" · ${failures.size} failed")
    }
    messages.post(
      level = if (failures.isEmpty()) MessageLevel.Success else MessageLevel.Warning,
      title = title,
      detail = failures.firstOrNull()?.second?.message,
      cause = failures.firstOrNull()?.second,
    )
  }

  /** Posts one Error message for the failures in [results], if any. */
  private fun reportFailures(command: String, results: List<Pair<DownloadTask, Throwable?>>) {
    val failures = results.mapNotNull { (task, error) -> error?.let { task to it } }
    if (failures.isEmpty()) return
    failures.forEach { (task, e) ->
      log.w { "Couldn't $command taskId=${task.taskId}: ${e.describeCauses()}" }
    }
    val (task, e) = failures.first()
    val device = deviceOf(task)
    val what = if (failures.size == 1) task.displayName()
    else downloads(failures.size)
    postError(
      title = "Couldn't $command $what on ${nameOf(device)}",
      detail = e.message,
      cause = e,
      taskKey = TaskKey(device?.deviceId ?: LOCAL_DEVICE_ID, task.taskId),
    )
  }

  /** Runs [action] on each of [items] in parallel; one failure never stops the others. */
  private suspend fun <T> runEach(
    items: List<T>,
    action: suspend (T) -> Unit,
  ): List<Pair<T, Throwable?>> = supervisorScope {
    items.map { item ->
      async { item to catchingUnlessCancelled { action(item) }.exceptionOrNull() }
    }.awaitAll()
  }

  private suspend fun trackPending(key: TaskKey, label: String, block: suspend () -> Unit) {
    val entry = key to label
    pendingCounts[entry] = (pendingCounts[entry] ?: 0) + 1
    pendingState.value = pendingCounts.keys.toSet()
    try {
      block()
    } finally {
      val count = (pendingCounts[entry] ?: 1) - 1
      if (count == 0) pendingCounts -= entry else pendingCounts[entry] = count
      pendingState.value = pendingCounts.keys.toSet()
    }
  }

  private fun postError(
    title: String,
    detail: String? = null,
    cause: Throwable? = null,
    taskKey: TaskKey? = null,
    actions: List<MessageAction> = emptyList(),
  ) {
    messages.post(
      level = MessageLevel.Error,
      title = title,
      detail = detail,
      taskKey = taskKey,
      deviceId = taskKey?.deviceId,
      actions = actions,
      cause = cause,
    )
  }

  private fun undoAction(op: PendingOp): MessageAction =
    MessageAction("Undo") { pendingOps.undo(op.id) }

  private fun nameOf(entry: InstanceEntry?): String = entry?.label ?: "this device"

  private fun deviceName(deviceId: String): String =
    instances.value.firstOrNull { it.deviceId == deviceId }?.label ?: deviceId

  /** Prefixes [title] with the device when it is not the active one. */
  private fun onDevice(deviceId: String, title: String): String =
    if (deviceId == (activeInstance.value?.deviceId ?: LOCAL_DEVICE_ID)) title
    else "On ${deviceName(deviceId)}: $title"

  /** Picks the folder of the last add on [entry] as a directory destination. */
  private suspend fun folderDestination(entry: InstanceEntry, folder: String): Destination {
    if (folder.endsWith('/') || folder.endsWith('\\') || folder.startsWith("content://")) {
      return Destination(folder)
    }
    val separator = catchingUnlessCancelled { entry.instance.status().system.separator }
      .getOrDefault("/")
    return Destination(folder + separator)
  }

  private companion object {
    /** Matches the daemon's upload limit; torrent metainfo is 4 MiB by default. */
    const val MAX_DROPPED_FILE_BYTES = 16L * 1024 * 1024

    /** Largest link list read from a dropped file. */
    const val MAX_LINK_LIST_BYTES = 1L * 1024 * 1024

    /** Dropped files read as text and handed to the add sheet. */
    val LINK_LIST_EXTENSIONS = setOf("txt", "csv", "url", "webloc")

    /** Bulk pauses repeat until nothing is left to pause, at most this often. */
    const val MAX_PAUSE_ROUNDS = 3

    /** How long Start now waits for the task to start before naming what it preempted. */
    val START_TIMEOUT = 2.seconds

    /** How long a new download can be undone, together with its file. */
    val ADD_UNDO_WINDOW = 8.seconds

    /** Order of the Downloads list until it is changed: newest first, ungrouped. */
    val DEFAULT_ARRANGEMENT = ListArrangement(
      sort = SortKey.Added,
      descending = true,
      group = GroupBy.None,
    )
  }
}

/** Compose state that the models outside composition also read, through [flow]. */
private class FlowState<T>(initial: T) {
  private var state by mutableStateOf(initial)

  /** The value as it changes. */
  val flow = MutableStateFlow(initial)

  var value: T
    get() = state
    set(value) {
      state = value
      flow.value = value
    }
}

/**
 * Runs [block], turning its failures into a failed [Result]. Cancellation of the calling
 * coroutine is rethrown, but a `CancellationException` the call throws while the caller is still
 * active, such as from a closed HTTP client, is a failure like any other.
 */
internal suspend fun <R> catchingUnlessCancelled(block: suspend () -> R): Result<R> =
  try {
    Result.success(block())
  } catch (e: CancellationException) {
    currentCoroutineContext().ensureActive()
    Result.failure(e)
  } catch (e: Exception) {
    Result.failure(e)
  }

/** Whether a failure leaves nothing to resume, so a retry has to start over. */
private fun KetchError.needsFreshStart(): Boolean = toCopy().primary == RowAction.DownloadAgain

private fun downloads(count: Int): String = if (count == 1) "1 download" else "$count downloads"

/** "2 scheduled still start at 02:00" for the scheduled tasks a bulk pause leaves alone. */
private fun scheduledNote(schedules: List<DownloadSchedule>): String? {
  if (schedules.isEmpty()) return null
  val first = schedules.filterIsInstance<DownloadSchedule.AtTime>().minOfOrNull { it.startAt }
    ?: return "${schedules.size} scheduled still start on time"
  val time = first.toLocalDateTime(TimeZone.currentSystemDefault()).time
  val hh = time.hour.toString().padStart(2, '0')
  val mm = time.minute.toString().padStart(2, '0')
  return "${schedules.size} scheduled still start at $hh:$mm"
}

/** Name of this task for messages, from its current request and state. */
private fun DownloadTask.displayName(): String = displayName(requestState.value, state.value)

/**
 * This request as another device should run it: a folder or file path of this device is
 * reduced to the file name, and the resolved source is dropped.
 */
private fun DownloadRequest.forDevice(): DownloadRequest {
  val portable = destination?.let { destination ->
    when {
      destination.isName() -> destination
      destination.isDirectory() || destination.value.startsWith("content://") -> null
      else -> extractFilename(destination.value.replace('\\', '/'))
        .takeIf { it.isNotBlank() }?.let(::Destination)
    }
  }
  return copy(destination = portable, resolvedSource = null)
}
