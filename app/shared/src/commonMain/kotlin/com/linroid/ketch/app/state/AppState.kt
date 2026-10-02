package com.linroid.ketch.app.state

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import com.linroid.ketch.app.feedback.MessageAction
import com.linroid.ketch.app.feedback.MessageCenter
import com.linroid.ketch.app.feedback.MessageLevel
import com.linroid.ketch.app.feedback.ToastMode
import com.linroid.ketch.app.input.KetchCommand
import com.linroid.ketch.app.instance.DeviceScope
import com.linroid.ketch.app.instance.DiscoveredServer
import com.linroid.ketch.app.instance.EmbeddedInstance
import com.linroid.ketch.app.instance.InstanceEntry
import com.linroid.ketch.app.instance.InstanceManager
import com.linroid.ketch.app.instance.LanServerDiscovery
import com.linroid.ketch.app.instance.RemoteInstance
import com.linroid.ketch.app.instance.ServerState
import com.linroid.ketch.app.instance.displayName
import com.linroid.ketch.app.instance.toPulseScope
import com.linroid.ketch.app.platform.DroppedFile
import com.linroid.ketch.app.util.LinkKind
import com.linroid.ketch.app.util.TaskOrigin
import com.linroid.ketch.app.util.clockTime
import com.linroid.ketch.app.util.displayName
import com.linroid.ketch.app.util.downloads
import com.linroid.ketch.app.util.extractFilename
import com.linroid.ketch.app.util.formatBytes
import com.linroid.ketch.app.util.plural
import com.linroid.ketch.app.util.toCopy
import com.linroid.ketch.app.util.transferSummary
import com.linroid.ketch.config.IntakePreferences
import com.linroid.ketch.config.SpeedLimitMode
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
import kotlin.reflect.KProperty
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
 * App state shared by every screen: the devices, the tasks of the device shown or of every
 * device at once ([deviceScope]), the commands that act on them and the requests that open the
 * app's surfaces.
 *
 * Commands run in [scope], which outlives the UI (see [AppController]), report failures through
 * [messages] and register their Undo with [pendingOps]. Like the Compose state it holds, it is
 * meant to be used from the main thread.
 *
 * @param scope runs the commands; it should use a `SupervisorJob` and the main dispatcher.
 * @param speedMode speed mode of the embedded device, owned by the host, such as the service whose
 *   notification switches it; `null` when the host keeps none.
 * @property incoming downloads and pairing links opened from outside the app; the shell asks
 *   before it connects to a device a pairing link names.
 * @property clock current time of the task list, the speed history and the time labels.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AppState(
  val instanceManager: InstanceManager,
  private val scope: CoroutineScope,
  val appSettings: AppSettingsController = AppSettingsController(),
  val aiSettings: AiSettingsController = AiSettingsController(),
  val incoming: IncomingDownloads = IncomingDownloads(),
  val messages: MessageCenter = MessageCenter(),
  val speedMode: SpeedModeController? = null,
  val clock: Clock = Clock.System,
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

  /**
   * Which devices the app shows: the active one, or every device at once (see
   * [showAllDevices]). New downloads go to the active device either way.
   */
  val deviceScope: StateFlow<DeviceScope> = instanceManager.deviceScope

  /** The devices the app shows, in the order of [instances]. */
  val shownInstances: StateFlow<List<InstanceEntry>> =
    combine(instances, activeInstance, deviceScope) { entries, active, shown ->
      if (shown == DeviceScope.All) entries else listOfNotNull(active)
    }.stateIn(scope, SharingStarted.Eagerly, listOfNotNull(activeInstance.value))

  /** Tasks of the shown devices, without the ones a pending operation hides. */
  val tasks: StateFlow<List<DownloadTask>> =
    shownInstances.flatMapLatest { entries ->
      if (entries.isEmpty()) {
        flowOf(emptyList())
      } else {
        combine(entries.map(::visibleTasksOf)) { lists -> lists.flatMap { it } }
      }
    }.stateIn(scope, SharingStarted.Eagerly, emptyList())

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
  var statusFilter: StatusFilter by filterState

  /** What is typed in the Downloads search field. */
  var searchQuery: String by searchState

  /** Sort order and grouping of the Downloads list; newest first and ungrouped until changed. */
  var listArrangement: ListArrangement by arrangementState

  /**
   * Whether the pointer is over the Downloads list, a row has focus or a menu is open, which
   * holds the list's order still.
   */
  var listFrozen: Boolean by frozenState

  /** Whether the add sheet shows, which it does while [intakeRequest] is set. */
  val showAddDialog: Boolean get() = intakeRequest != null
  var showInstanceSelector by mutableStateOf(false)
  var showAddRemoteDialog by mutableStateOf(false)

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

  /**
   * Counts the Settings requests, so asking again for the page Settings opened at goes back to
   * it after the user moved on, although the request is the same.
   */
  var settingsRequests by mutableIntStateOf(0)
    private set

  /**
   * Whether the keyboard shortcut sheet was asked for, such as from Settings; the shell shows it
   * and clears this with [shortcutsShown].
   */
  var shortcutsRequested by mutableStateOf(false)
    private set

  /**
   * A command only the window's shell runs, such as the command palette picked from the macOS
   * menu bar; the shell runs it and clears this with [shellCommandHandled].
   */
  var shellCommand by mutableStateOf<KetchCommand?>(null)
    private set

  /** A Discover search the shell should navigate to, cleared with [discoverRequestHandled]. */
  var discoverRequest by mutableStateOf<DiscoverRequest?>(null)
    private set

  /** Task shown in the inspector, or `null`. */
  var inspectedTask by mutableStateOf<TaskKey?>(null)
    private set

  /** Selected rows of the task list. */
  var selectedKeys by mutableStateOf(emptySet<TaskKey>())

  /** A Send to waiting for the user to accept that cookies go along; see [sendTo]. */
  var sendConfirmation by mutableStateOf<SendConfirmation?>(null)
    private set

  private val focusSearch = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

  /** Emits when the search field should take focus, such as on ⌘F. */
  val focusSearchRequests: SharedFlow<Unit> = focusSearch.asSharedFlow()

  private val showDownloadsRequests = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

  /** Emits when the shell should show the Downloads list, such as after adding from Discover. */
  val downloadsRequests: SharedFlow<Unit> = showDownloadsRequests.asSharedFlow()

  private val addedEvents = MutableSharedFlow<List<TaskKey>>(extraBufferCapacity = ADDED_BUFFER)

  /**
   * Emits the keys of the downloads each add from this window started, such as a quick add, a
   * dropped link or the add sheet, so the Downloads page can point the new rows out.
   */
  val addedTasks: SharedFlow<List<TaskKey>> = addedEvents.asSharedFlow()

  /**
   * Reports that [keys] were just added from this window; see [addedTasks]. Whatever added them
   * reports them itself, so the activity monitor's report of them is left out ([claimAdds]).
   */
  fun announceAdded(keys: List<TaskKey>) {
    if (keys.isEmpty()) return
    claimAdds(keys)
    addedEvents.tryEmit(keys)
  }

  // The monitor reports an add after its task shows up on the device, which can be before or
  // after the command that added it reports it, so either side may come second.
  private val claimedAdds = LinkedHashSet<TaskKey>()
  private val monitorAdds = LinkedHashMap<TaskKey, Long>()

  /**
   * Marks [keys] as tasks a command of this window added and reports itself, such as an add, a
   * send or a restart, so one add shows once in the Activity history: the activity monitor's
   * entry for them is withdrawn when it came first, and never posted when it comes later.
   */
  internal fun claimAdds(keys: Collection<TaskKey>) {
    for (key in keys) {
      monitorAdds.remove(key)?.let(messages::withdraw)
      claimedAdds += key
    }
    while (claimedAdds.size > RECENT_ADDS_LIMIT) claimedAdds.remove(claimedAdds.first())
  }

  private val settingsCache = mutableMapOf<InstanceEntry, InstanceSettingsController>()

  /** Download, network and torrent settings of the active device. */
  var instanceSettings by mutableStateOf(settingsForActive())
    private set

  var discoveryState by mutableStateOf<DiscoveryState>(
    DiscoveryState.Idle
  )
    private set
  private var discoveryJob: Job? = null
  private var switchingInstance by mutableStateOf<InstanceEntry?>(null)
  var unauthorizedInstance by
    mutableStateOf<RemoteInstance?>(null)
  var resolveState by mutableStateOf<ResolveState>(
    ResolveState.Idle
  )
    private set

  /**
   * A dropped `.torrent` file added in place of a typed URL; [resolveState] reflects its
   * resolution.
   */
  var droppedFile by mutableStateOf<DroppedFile?>(null)
    private set

  /** The device [droppedFile] is resolved on, whose result the add sheet can reuse. */
  internal var droppedFileApi: KetchApi? = null
    private set

  /** Speed mode of the embedded device; full speed when the host keeps no [speedMode]. */
  private val localMode: StateFlow<SpeedMode> = speedMode?.mode ?: MutableStateFlow(SpeedMode.Full)

  // Every device is listed and watched, shown or not, so switching keeps each device's speed
  // samples, histories and timelines. Built on the main thread, which owns the settings cache;
  // the models collect them elsewhere.
  private val listSources: StateFlow<List<TaskListSource>> =
    combine(instances, snapshotFlow { aiSettings.supported }) { entries, discover ->
      entries.map { listSourceOf(it, discover) }
    }.stateIn(scope, SharingStarted.Eagerly, emptyList())

  private val pulseSources: StateFlow<List<PulseSource>> =
    instances.map { entries -> entries.map(::pulseSourceOf) }
      .stateIn(scope, SharingStarted.Eagerly, emptyList())

  /**
   * Rows of the shown devices' tasks, and the tab, search and order the Downloads list shows.
   * It keeps rows for the other devices too, in [TaskListModel.allRows].
   */
  val taskList: TaskListModel = TaskListModel(
    sources = listSources,
    scope = scope,
    filter = filterState.flow,
    query = searchState.flow,
    arrangement = arrangementState.flow,
    frozen = frozenState.flow,
    shown = shownScope(),
    clock = clock,
  )

  /** Speed of each task of every device once a second, for the inspector's Activity chart. */
  val speedHistory: SpeedHistoryStore = SpeedHistoryStore(taskList.allRows, scope, clock)

  /**
   * The add sheet's sessions. They run in the app scope, so adding, or a torrent's file list
   * left to load in the background, outlives the sheet and the screen that showed it.
   */
  val intake: IntakeController by lazy { IntakeController(this, scope) }

  /**
   * Speed, counts, speed limit, free space and health of the shown devices. Every device is
   * watched, so each keeps its speed history while another one shows.
   */
  val pulse: PulseModel = PulseModel(
    sources = pulseSources,
    pulseScope = shownScope().map { it.toPulseScope() },
    mode = combine(activeInstance, deviceScope, localMode) { entry, shown, mode ->
      if (entry is RemoteInstance && shown != DeviceScope.All) SpeedMode.Full else mode
    },
    scope = scope,
  )

  // Devices named by the last add of each kind this session, by device id.
  private val lastTargets = mutableMapOf<IntakeKind, String>()

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
      activeInstance.collect {
        instanceSettings = settingsForActive()
        instanceSettings.loadDownload()
      }
    }
    scope.launch {
      instances.collect { entries -> settingsCache.keys.retainAll(entries.toSet()) }
    }
    scope.launch {
      // Picking another device to work on picks where adds go, until another add says otherwise.
      activeInstance.drop(1).collect { lastTargets.clear() }
    }
    scope.launch {
      // Every shown device's limits explain why its queued tasks wait, including devices added
      // while every device shows.
      val loaded = mutableSetOf<InstanceEntry>()
      combine(deviceScope, instances) { shown, entries ->
        entries.takeIf { shown == DeviceScope.All }
      }.collect { entries ->
        if (entries == null) {
          loaded.clear()
          return@collect
        }
        entries.filter { it != activeInstance.value && loaded.add(it) }
          .forEach { settingsFor(it).loadDownload() }
      }
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
        savedSpeedLimit = { config -> standingCap(config.speedLimit) },
      )
    }

  // The embedded device's cap when the slow lane lets go: its live limit at full speed, else
  // the one the speed mode keeps for then.
  private fun standingCap(live: SpeedLimit): SpeedLimit {
    val settings = speedMode?.settings?.value ?: return live
    return if (settings.mode == SpeedLimitMode.Full) live else settings.standard
  }

  /** [deviceScope], naming the active device when it shows alone. */
  private fun shownScope(): Flow<DeviceScope> =
    combine(activeInstance, deviceScope) { active, shown ->
      if (shown == DeviceScope.All) {
        shown
      } else {
        DeviceScope.Single(active?.deviceId ?: LOCAL_DEVICE_ID)
      }
    }.distinctUntilChanged()

  /** Settings controller of the device with [deviceId], or `null` when it is not configured. */
  fun settingsOf(deviceId: String): InstanceSettingsController? =
    instances.value.firstOrNull { it.deviceId == deviceId }?.let(::settingsFor)

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
  }

  /** Closes the add dialog; the next opened download or links, if any are waiting, then show. */
  fun closeAddDialog() {
    resetResolveState()
    intakeRequest = null
    openedDownload?.let(incoming::complete)
    openedLinks?.let(incoming::complete)
  }

  /** Opens Settings at [target]. */
  fun openSettings(target: SettingsTarget = SettingsTarget(SettingsTarget.Page.General)) {
    settingsRequest = target
    settingsRequests++
  }

  /** Closes Settings. */
  fun closeSettings() {
    settingsRequest = null
  }

  /** Asks the shell to show the keyboard shortcut sheet, as `⌘/` does. */
  fun showShortcuts() {
    shortcutsRequested = true
  }

  /** Marks [shortcutsRequested] as shown. */
  fun shortcutsShown() {
    shortcutsRequested = false
  }

  /** Asks the window's shell to run [command], which needs the shell's own state. */
  fun runInShell(command: KetchCommand) {
    shellCommand = command
  }

  /** Marks [shellCommand] as run. */
  fun shellCommandHandled() {
    shellCommand = null
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

  /**
   * Shows the selected download in the inspector, which sums up two or more by itself, and asks
   * the shell for the Downloads page on the tab shown. Returns whether anything is selected.
   */
  fun showDetails(): Boolean {
    if (selectedKeys.isEmpty()) return false
    selectedKeys.singleOrNull()?.let { inspectedTask = it }
    showDownloadsRequests.tryEmit(Unit)
    return true
  }

  /** Closes the inspector; a selection of several rows, which it sums up, is cleared. */
  fun closeInspector() {
    inspectedTask = null
    if (selectedKeys.size >= 2) selectedKeys = emptySet()
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

  /**
   * Adds dropped files: the first `.torrent` file opens the add dialog, which resolves it on the
   * active device; link lists such as `.txt` files open the add sheet with their text.
   */
  fun addDroppedFiles(files: List<DroppedFile>) {
    val torrent = files.firstOrNull {
      it.name.endsWith(".torrent", ignoreCase = true)
    }
    if (torrent != null) {
      openIntake()
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

  /**
   * Reads [file] and resolves its content on [target], by default the active device; also
   * retries a failed attempt.
   */
  fun resolveDroppedFile(file: DroppedFile, target: InstanceEntry? = null) {
    val api = target?.instance ?: activeApi.value
    droppedFile = file
    droppedFileApi = api
    resolveState = ResolveState.Resolving
    scope.launch {
      runCatching {
        val content = file.readBytes(MAX_DROPPED_FILE_BYTES)
        api.resolveContent(content, file.name)
      }.onSuccess { result ->
        if (droppedFile === file) {
          resolveState = ResolveState.Resolved(result)
        }
      }.onFailure { e ->
        if (e is CancellationException) throw e
        if (droppedFile === file) {
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
    droppedFile = null
    droppedFileApi = null
    resolveState = ResolveState.Idle
  }

  /**
   * Adds [urls] to [target] at once with the options last used on that device (folder, priority
   * and connections from [com.linroid.ketch.config.UiPreferences.intake]). The toast offers
   * Options and, for 8 seconds, Undo, which removes the new tasks and their files.
   */
  fun quickAdd(urls: List<String>, target: InstanceEntry? = quickAddTarget()): Job =
    scope.launch {
      val entry = target ?: run {
        showAddRemoteDialog = true
        return@launch
      }
      val defaults = appSettings.ui.intake[entry.deviceId] ?: IntakePreferences()
      val folder = defaults.folder?.let { folderDestination(entry, it) }
      val requests = urls.mapNotNull { url ->
        // Torrents write their own files, which an Android content:// folder cannot take.
        val torrent = LinkKind.of(url).isTorrent
        val destination = folder?.takeUnless { torrent && it.value.startsWith("content://") }
        try {
          DownloadRequest(
            url = url,
            destination = destination,
            priority = defaults.priority,
            connections = defaults.connections.coerceAtLeast(0),
            properties = mapOf(TaskOrigin.PROPERTY to TaskOrigin.App.id),
          )
        } catch (e: IllegalArgumentException) {
          postError("Couldn't add $url", detail = e.message, cause = e)
          null
        }
      }
      if (requests.isEmpty()) return@launch
      rememberTarget(IntakeKind.Links, entry)
      if (statusFilter != StatusFilter.All) statusFilter = StatusFilter.All
      addRequests(entry, requests, offerOptions = true)
    }

  /**
   * Shows [instance] alone and makes it the target of new downloads. Picking the active device
   * while every device shows goes back to showing it alone.
   */
  fun switchInstance(instance: InstanceEntry) {
    val shown = instance == activeInstance.value && deviceScope.value != DeviceScope.All
    if (shown || switchingInstance != null) return
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

  /**
   * Shows the downloads of every device at once (`⌘⌥0`); the active device stays the target of
   * new downloads. Returns `false`, and changes nothing, with fewer than
   * [DeviceScope.MIN_DEVICES] devices.
   */
  fun showAllDevices(): Boolean {
    val shown = instanceManager.showAllDevices()
    if (shown) showInstanceSelector = false
    return shown
  }

  /**
   * The device downloads of [kind] were last added to this session, while it is still
   * configured; `null` before the first add.
   */
  fun lastTarget(kind: IntakeKind): InstanceEntry? =
    lastTargets[kind]?.let { id -> instances.value.firstOrNull { it.deviceId == id } }

  /** Remembers [target] as where downloads of [kind] go, for the rest of the session. */
  fun rememberTarget(kind: IntakeKind, target: InstanceEntry) {
    lastTargets[kind] = target.deviceId
  }

  /**
   * Where a link added without the sheet goes: the active device, or under All devices the
   * device links were last added to.
   */
  fun quickAddTarget(): InstanceEntry? {
    val active = activeInstance.value
    if (deviceScope.value != DeviceScope.All) return active
    return lastTarget(IntakeKind.Links) ?: active
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

  /**
   * Runs [block] in the app scope, so closing the control that started it cancels neither the
   * command nor the Undo it offers.
   */
  internal fun launchCommand(block: suspend CoroutineScope.() -> Unit): Job =
    scope.launch(block = block)

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
   * Pauses every queued task, then every downloading one, on each of [targets] (the shown
   * devices by default), so the queue cannot start a task that was waiting. Scheduled tasks keep
   * their schedule. Undo resumes exactly the tasks this paused.
   */
  fun pauseAll(targets: List<InstanceEntry> = shownInstances.value): Job =
    scope.launch {
      val results = supervisorScope {
        targets.map { entry ->
          async {
            entry to pauseActiveTasks({ visibleTasks(entry) }) { group ->
              runEach(group) { it.pause() }
            }
          }
        }.awaitAll()
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
      val devices = results.count { (_, result) -> result.paused.isNotEmpty() }
      val title = buildString {
        append("Paused ${downloads(paused.size)}")
        if (devices > 1) append(" on $devices devices")
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

  /** Resumes every paused task on each of [targets], the shown devices by default. */
  fun resumeAll(targets: List<InstanceEntry> = shownInstances.value): Job =
    runOnAll(targets, { it is DownloadState.Paused }, "Resumed", "resume") { _, task ->
      task.resume()
    }

  /**
   * Retries every failed task on each of [targets], the shown devices by default. Tasks whose
   * progress cannot be reused (the file changed, the saved progress is damaged, or the server
   * refused to resume) start over.
   */
  fun retryFailed(targets: List<InstanceEntry> = shownInstances.value): Job =
    runOnAll(targets, { it is DownloadState.Failed }, "Retrying", "retry", ::retryOn)

  /**
   * Removes the finished tasks of each of [targets] (the shown devices by default) from the
   * list, keeping their files. The rows hide at once and are removed when the Undo window ends.
   */
  fun clearCompleted(targets: List<InstanceEntry> = shownInstances.value) {
    val tasks = targets.flatMap { entry ->
      visibleTasks(entry).filter { it.state.value is DownloadState.Completed }
    }
    if (tasks.isEmpty()) return
    deferRemoval(tasks, deleteFiles = { false }, label = "Clear Finished") { count ->
      "Cleared $count finished ${if (count == 1) "download" else "downloads"}"
    }
  }

  /**
   * Removes [tasks] from the list, and their files when [deleteFiles] is set. The rows hide at
   * once and are removed when the Undo window ends or the app closes.
   */
  fun remove(tasks: List<DownloadTask>, deleteFiles: Boolean = false) {
    removeTasks(tasks) { deleteFiles }
  }

  private fun removeTasks(tasks: List<DownloadTask>, deleteFiles: (DownloadTask) -> Boolean) {
    if (tasks.isEmpty()) return
    deferRemoval(tasks, deleteFiles, label = "Remove") { "Removed ${what(tasks)}" }
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
    val title = "Discarded progress of ${what(tasks)}"
    messages.post(MessageLevel.Success, title, actions = listOf(undoAction(op)))
  }

  /**
   * Starts [task] over as a new task on the same device and removes the old one, with its
   * partial file. A finished task keeps its file.
   */
  fun redownload(task: DownloadTask): Job = redownload(listOf(task))

  /**
   * Starts each of [tasks] over, like [redownload] for one, and reports them in one message,
   * such as "Restarted 3 downloads". Try again repeats what failed: it adds a request again
   * when its old task was already removed.
   */
  fun redownload(tasks: List<DownloadTask>): Job = scope.launch {
    val targets = tasks.mapNotNull { task -> deviceOf(task)?.let { it to task } }
    if (targets.isEmpty()) return@launch
    val results = runEach(targets) { (entry, task) ->
      trackPending(TaskKey(entry.deviceId, task.taskId), "download again") {
        restart(entry, task)
      }
    }
    val done = results.filter { it.second == null }.map { it.first.second }
    val failed = results.mapNotNull { (target, e) -> e?.let { target to it } }
    failed.forEach { (target, e) ->
      log.w { "Couldn't restart taskId=${target.second.taskId}: ${e.describeCauses()}" }
    }
    val retry = MessageAction("Try again") { retryRestarts(failed) }
    if (done.isEmpty()) {
      val (target, e) = failed.first()
      val (entry, task) = target
      postError(
        title = if (failed.size == 1) {
          "Couldn't download ${task.displayName()} again on ${nameOf(entry)}"
        } else {
          "Couldn't download ${downloads(failed.size)} again"
        },
        detail = e.message,
        cause = e,
        taskKey = TaskKey(entry.deviceId, task.taskId).takeIf { failed.size == 1 },
        actions = listOf(retry),
      )
      return@launch
    }
    val title = buildString {
      append("Restarted ")
      append(what(done))
      if (failed.isNotEmpty()) append(" · ${failed.size} failed")
    }
    messages.post(
      level = if (failed.isEmpty()) MessageLevel.Success else MessageLevel.Warning,
      title = title,
      detail = failed.firstOrNull()?.second?.message,
      deviceId = targets.map { it.first.deviceId }.distinct().singleOrNull(),
      actions = if (failed.isEmpty()) emptyList() else listOf(retry),
      cause = failed.firstOrNull()?.second,
    )
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
   * Retries [task] on its device as [retry] does, for a batch that reports the outcome itself:
   * it resumes, or starts over when its progress cannot be reused or it was canceled.
   */
  internal suspend fun retryInBatch(task: DownloadTask) {
    deviceOf(task)?.let { retryOn(it, task) }
  }

  /**
   * Starts [task] at once: it becomes [DownloadPriority.URGENT], which may pause a running task
   * to make room. The toast names that task, and Undo restores the previous priority and state.
   */
  fun startNow(task: DownloadTask): Job = startNow(listOf(task))

  /**
   * Starts each of [tasks] at once, like [startNow] for one, with one message naming what they
   * paused to make room, and one Undo for all of them.
   */
  fun startNow(tasks: List<DownloadTask>): Job = scope.launch {
    if (tasks.isEmpty()) return@launch
    val starts = tasks.map { task ->
      Start(task, deviceOf(task), task.state.value, task.requestState.value.priority)
    }
    val starting = starts.mapTo(HashSet()) { it.task }
    val running = starts.mapNotNull { it.entry }.distinct()
      .flatMap { visibleTasks(it) }
      .filter { it.state.value is DownloadState.Downloading && it !in starting }
    val results = runEach(starts) { start ->
      val key = TaskKey(start.entry?.deviceId ?: LOCAL_DEVICE_ID, start.task.taskId)
      trackPending(key, "start now") { start.run() }
    }
    val started = results.filter { it.second == null }.map { it.first }
    val failed = results.mapNotNull { (start, e) -> e?.let { start to it } }
    failed.forEach { (start, e) ->
      log.w { "Couldn't start taskId=${start.task.taskId} now: ${e.describeCauses()}" }
    }
    val retry = MessageAction("Try again") { startNow(failed.map { it.first.task }) }
    if (started.isEmpty()) {
      val (start, e) = failed.first()
      postError(
        title = if (failed.size == 1) {
          "Couldn't start ${start.task.displayName()} now on ${nameOf(start.entry)}"
        } else {
          "Couldn't start ${downloads(failed.size)} now"
        },
        detail = e.message,
        cause = e,
        taskKey = start.entry?.let { TaskKey(it.deviceId, start.task.taskId) }
          ?.takeIf { failed.size == 1 },
        actions = listOf(retry),
      )
      return@launch
    }
    val preempted = running.filter { it.state.value is DownloadState.Queued }
    val op = pendingOps.register(label = "Start Now", undo = {
      reportFailures("undo start now of", runEach(started) { it.undo() }.map { (start, e) ->
        start.task to e
      })
    })
    val title = buildString {
      append("Started ${what(started.map { it.task })} now")
      if (preempted.isNotEmpty()) {
        append(" · paused ${preempted.joinToString(", ") { it.displayName() }} to make room")
      }
      if (failed.isNotEmpty()) append(" · ${failed.size} failed")
    }
    messages.post(
      level = if (failed.isEmpty()) MessageLevel.Success else MessageLevel.Warning,
      title = title,
      detail = failed.firstOrNull()?.second?.message,
      actions = listOfNotNull(undoAction(op), retry.takeIf { failed.isNotEmpty() }),
      cause = failed.firstOrNull()?.second,
    )
  }

  /**
   * Adds [tasks] to [target] with the same headers, speed limit, priority and connections; they
   * start over there. Tasks already on [target] are left alone. With [move], each task whose copy
   * was added is removed here when the Undo window ends, with its partial file; Undo instead
   * removes the copies.
   *
   * Tasks that carry cookies or a sign-in for a remote [target], which would keep them, first
   * wait in [sendConfirmation] until [confirmSend]; [confirmed] skips that.
   */
  fun sendTo(
    tasks: List<DownloadTask>,
    target: InstanceEntry,
    move: Boolean = false,
    confirmed: Boolean = false,
  ) {
    val outgoing = tasks.filter { deviceOf(it)?.instance !== target.instance }
    if (outgoing.isEmpty()) return
    val warning = if (target is RemoteInstance) {
      credentialWarning(outgoing.map { it.requestState.value.headers }, nameOf(target))
    } else {
      null
    }
    if (warning != null && !confirmed) {
      sendConfirmation = SendConfirmation(outgoing, target, move, warning)
      return
    }
    send(outgoing, target, move)
  }

  /** Sends what [sendConfirmation] waits for. */
  fun confirmSend() {
    val pending = sendConfirmation ?: return
    sendConfirmation = null
    send(pending.tasks, pending.target, pending.move)
  }

  /** Drops what [sendConfirmation] waits for. */
  fun dismissSendConfirmation() {
    sendConfirmation = null
  }

  private fun send(tasks: List<DownloadTask>, target: InstanceEntry, move: Boolean): Job =
    scope.launch {
      val results = supervisorScope {
        tasks.map { task ->
          async {
            val request = task.requestState.value.forDevice()
            task to catchingUnlessCancelled { target.instance.download(request) }
          }
        }.awaitAll()
      }
      val sent = results.mapNotNull { (task, result) -> result.getOrNull()?.let { task to it } }
      claimAdds(sent.map { TaskKey(target.deviceId, it.second.taskId) })
      val failures = results.mapNotNull { (task, result) ->
        result.exceptionOrNull()?.let { task to it }
      }
      val targetName = nameOf(target)
      failures.forEach { (task, e) ->
        log.w { "Couldn't send taskId=${task.taskId} to $targetName: ${e.describeCauses()}" }
      }
      if (sent.isEmpty()) {
        val e = failures.first().second
        postError(
          title = "Couldn't send ${what(failures.map { it.first })} to $targetName",
          detail = e.message,
          cause = e,
          actions = listOf(
            MessageAction("Try again") { sendTo(tasks, target, move, confirmed = true) },
          ),
        )
        return@launch
      }
      val failedNote = if (failures.isEmpty()) "" else " · ${failures.size} failed"
      val sources = sent.map { it.first }
      if (move) {
        val op = pendingOps.register(
          label = "Move",
          hides = hide(sources),
          commit = {
            reportFailures("remove", runEach(sources) { it.remove(it.hasPartialFile) })
          },
          undo = {
            val copies = sent.map { it.second }
            reportFailures("remove", runEach(copies) { it.remove(deleteFiles = true) })
          },
        )
        messages.post(
          level = if (failures.isEmpty()) MessageLevel.Success else MessageLevel.Warning,
          title = "Moved ${what(sources)} to $targetName$failedNote",
          deviceId = target.deviceId,
          actions = listOf(undoAction(op)),
        )
      } else {
        messages.post(
          level = if (failures.isEmpty()) MessageLevel.Success else MessageLevel.Warning,
          title = "Sent ${what(sources)} to $targetName$failedNote",
          deviceId = target.deviceId,
          actions = listOf(
            MessageAction("Show") { showOn(target, sent.singleOrNull()?.second) },
            MessageAction("Remove here") { removeTasks(sources) { it.hasPartialFile } },
          ),
        )
      }
    }

  /**
   * Shows the Downloads list with [task] of [target] inspected, switching to [target] only when
   * it is not shown already, such as under All devices.
   */
  internal fun showOn(target: InstanceEntry, task: DownloadTask?) {
    if (target !in shownInstances.value) switchInstance(target)
    showDownloads()
    task?.let { inspect(TaskKey(target.deviceId, it.taskId)) }
  }

  /** Reports [event] from the host's activity monitor as a message. */
  fun report(event: ActivityEvent) {
    when (event) {
      is ActivityEvent.Added -> {
        if (event.taskKey in claimedAdds) return
        val message = messages.post(
          level = MessageLevel.Info,
          title = onDevice(event.taskKey.deviceId, "Added ${displayName(event.request)}"),
          taskKey = event.taskKey,
          deviceId = event.taskKey.deviceId,
          toast = ToastMode.Silent,
        )
        monitorAdds[event.taskKey] = message.id
        while (monitorAdds.size > RECENT_ADDS_LIMIT) monitorAdds.remove(monitorAdds.keys.first())
      }
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
          plural(event.files, "file"),
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

  /**
   * The tasks of [keys] on the devices that hold them, in that order, such as rows dragged onto
   * a device to [sendTo] it; keys of tasks no device lists are skipped.
   */
  fun tasksOf(keys: Collection<TaskKey>): List<DownloadTask> = keys.mapNotNull { key ->
    instances.value.firstOrNull { it.deviceId == key.deviceId }
      ?.instance?.tasks?.value?.firstOrNull { it.taskId == key.taskId }
  }

  private fun visibleTasks(entry: InstanceEntry): List<DownloadTask> {
    val hidden = pendingOps.hidden.value
    return entry.instance.tasks.value.filter { TaskKey(entry.deviceId, it.taskId) !in hidden }
  }

  /** Tasks of [entry] as they change, without the ones a pending operation hides. */
  internal fun visibleTasksOf(entry: InstanceEntry): Flow<List<DownloadTask>> {
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
        name = entry.displayName,
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
      name = entry.displayName,
      tasks = visibleTasksOf(entry),
      config = snapshotFlow { settings.download },
      status = entry.instance::status,
      health = when (entry) {
        is RemoteInstance -> entry.connectionState.map { it.toDeviceHealth() }
        else -> serverState.map { it.toDeviceHealth() }
      },
    )
  }

  private fun deferRemoval(
    tasks: List<DownloadTask>,
    deleteFiles: (DownloadTask) -> Boolean,
    label: String,
    title: (Int) -> String,
  ) {
    val keys = hide(tasks)
    val op = pendingOps.register(
      label = label,
      hides = keys,
      commit = { reportFailures("remove", runEach(tasks) { it.remove(deleteFiles(it)) }) },
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
   * partial file go first, since the new task writes to the same path; when the add then fails,
   * the [AddFailed] it throws keeps the request for Try again.
   */
  private suspend fun restart(entry: InstanceEntry, task: DownloadTask) {
    val request = task.requestState.value.copy(
      schedule = DownloadSchedule.Immediate,
      resolvedSource = null,
    )
    task.remove(deleteFiles = task.state.value !is DownloadState.Completed)
    try {
      val copy = entry.instance.download(request)
      claimAdds(listOf(TaskKey(entry.deviceId, copy.taskId)))
    } catch (e: CancellationException) {
      currentCoroutineContext().ensureActive()
      throw AddFailed(entry, request, e)
    } catch (e: Exception) {
      throw AddFailed(entry, request, e)
    }
  }

  /** Resumes [task] on [entry], or starts it over when that is what its Retry promises. */
  private suspend fun retryOn(entry: InstanceEntry, task: DownloadTask) {
    val current = task.state.value
    val fresh = current is DownloadState.Canceled ||
      current is DownloadState.Failed && current.error.needsFreshStart()
    if (fresh) restart(entry, task) else task.resume()
  }

  /** Repeats the restarts that [failed]: adds again what was removed, restarts the rest. */
  private fun retryRestarts(failed: List<Pair<Pair<InstanceEntry, DownloadTask>, Throwable>>) {
    val again = failed.filter { it.second !is AddFailed }.map { it.first.second }
    if (again.isNotEmpty()) redownload(again)
    failed.mapNotNull { it.second as? AddFailed }.groupBy { it.entry }.forEach { (entry, adds) ->
      scope.launch { addRequests(entry, adds.map { it.request }) }
    }
  }

  /** A task's state and priority before Start now, to undo it. */
  private class Start(
    val task: DownloadTask,
    val entry: InstanceEntry?,
    val before: DownloadState,
    val priority: DownloadPriority,
  ) {
    suspend fun run() {
      if (before is DownloadState.Scheduled) task.reschedule(DownloadSchedule.Immediate)
      task.setPriority(DownloadPriority.URGENT)
      if (before is DownloadState.Paused || before is DownloadState.Failed) task.resume()
      withTimeoutOrNull(START_TIMEOUT) { task.state.first { it is DownloadState.Downloading } }
    }

    suspend fun undo() {
      task.setPriority(priority)
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
  }

  /** The old task of a restart is gone, but its [request] could not be added on [entry]. */
  private class AddFailed(
    val entry: InstanceEntry,
    val request: DownloadRequest,
    cause: Throwable,
  ) : Exception(cause.message, cause)

  private fun retry(key: TaskKey) {
    val entry = instances.value.firstOrNull { it.deviceId == key.deviceId } ?: return
    val task = entry.instance.tasks.value.firstOrNull { it.taskId == key.taskId } ?: return
    retry(task)
  }

  /** Adds each of [requests] to [entry] on its own and reports the outcome. */
  private suspend fun addRequests(
    entry: InstanceEntry,
    requests: List<DownloadRequest>,
    offerOptions: Boolean = false,
  ) {
    val results = supervisorScope {
      requests.map { request ->
        async { request to catchingUnlessCancelled { entry.instance.download(request) } }
      }.awaitAll()
    }
    val added = results.mapNotNull { it.second.getOrNull() }
    val failed = results.mapNotNull { (request, result) ->
      result.exceptionOrNull()?.let { request to it }
    }
    failed.forEach { (request, e) ->
      log.w { "Couldn't add ${redactUrl(request.url)}: ${e.describeCauses()}" }
    }
    announceAdded(added.map { TaskKey(entry.deviceId, it.taskId) })
    reportAdded(
      entry = entry,
      added = added,
      failures = failed.map { (request, e) -> displayName(request) to e },
      offerOptions = offerOptions,
      retry = { scope.launch { addRequests(entry, failed.map { it.first }, offerOptions) } },
    )
  }

  private fun reportAdded(
    entry: InstanceEntry,
    added: List<DownloadTask>,
    failures: List<Pair<String, Throwable>>,
    offerOptions: Boolean = false,
    retry: (() -> Unit)? = null,
  ) {
    val deviceName = entry.label
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
      if (single != null && offerOptions) {
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
      taskKey = single?.let { TaskKey(entry.deviceId, it.taskId) },
      deviceId = entry.deviceId,
      actions = actions,
      cause = failures.firstOrNull()?.second,
    )
  }

  /**
   * Runs [action] on each task of [targets] whose state [matches], all at once, and reports them
   * in one message, such as "Resumed 3 downloads on 2 devices".
   */
  private fun runOnAll(
    targets: List<InstanceEntry>,
    matches: (DownloadState) -> Boolean,
    verb: String,
    command: String,
    action: suspend (InstanceEntry, DownloadTask) -> Unit,
  ): Job = scope.launch {
    val tasks = targets.flatMap { entry ->
      visibleTasks(entry).filter { matches(it.state.value) }.map { entry to it }
    }
    if (tasks.isEmpty()) return@launch
    val results = runEach(tasks) { (entry, task) -> action(entry, task) }
      .map { (pair, error) -> pair.second to error }
    val devices = tasks.distinctBy { it.first.deviceId }.size
    val done = results.count { it.second == null }
    val failures = results.mapNotNull { (task, error) -> error?.let { task to it } }
    if (done == 0) {
      reportFailures(command, results)
      return@launch
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
    postError(
      title = "Couldn't $command ${what(failures.map { it.first })} on ${nameOf(device)}",
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

  /**
   * Registers the Undo of adding [tasks], which removes them with their files, and returns its
   * button; [logger] notes each task that could not be removed.
   */
  internal fun undoAddAction(tasks: List<DownloadTask>, logger: KetchLogger): MessageAction {
    val op = pendingOps.register(label = "Add", timeout = ADD_UNDO_WINDOW, undo = {
      tasks.forEach { task ->
        catchingUnlessCancelled { task.remove(deleteFiles = true) }.onFailure { e ->
          logger.w { "Couldn't undo the add of taskId=${task.taskId}: ${e.describeCauses()}" }
        }
      }
    })
    return undoAction(op)
  }

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
    /** Dropped files read as text and handed to the add sheet. */
    val LINK_LIST_EXTENSIONS = setOf("txt", "csv", "url", "webloc")

    /** How long Start now waits for the task to start before naming what it preempted. */
    val START_TIMEOUT = 2.seconds

    /** Order of the Downloads list until it is changed: newest first, ungrouped. */
    val DEFAULT_ARRANGEMENT = ListArrangement(
      sort = SortKey.Added,
      descending = true,
      group = GroupBy.None,
    )
  }
}

/**
 * A Send to that waits for the user to accept that cookies or a sign-in go along.
 *
 * @property tasks the downloads to send.
 * @property target the device they go to.
 * @property move whether they are removed here once sent.
 * @property warning what goes along, such as "Cookies from your browser will be sent to
 *   NAS-Basement."
 */
data class SendConfirmation(
  val tasks: List<DownloadTask>,
  val target: InstanceEntry,
  val move: Boolean,
  val warning: String,
)

/**
 * What of [headers] a device named [deviceName] would receive and keep: cookies, a sign-in or
 * both; `null` when none of the requests sends any.
 */
internal fun credentialWarning(headers: List<Map<String, String>>, deviceName: String): String? {
  val names = headers.flatMap { it.keys }
  val cookies = names.any { it.equals(COOKIE_HEADER, ignoreCase = true) }
  val signIn = names.any { it.equals(AUTHORIZATION_HEADER, ignoreCase = true) }
  return when {
    cookies && signIn -> {
      "Cookies and sign-in details from your browser will be sent to $deviceName."
    }
    cookies -> "Cookies from your browser will be sent to $deviceName."
    signIn -> "Sign-in details will be sent to $deviceName."
    else -> null
  }
}

internal const val COOKIE_HEADER = "Cookie"
internal const val AUTHORIZATION_HEADER = "Authorization"

/**
 * Largest dropped `.torrent` file read; matches the daemon's upload limit, while torrent metainfo
 * is 4 MiB by default.
 */
internal const val MAX_DROPPED_FILE_BYTES = 16L * 1024 * 1024

/** Largest link list read from a dropped file. */
internal const val MAX_LINK_LIST_BYTES = 1L * 1024 * 1024

/** How long a new download can be undone, together with its file. */
internal val ADD_UNDO_WINDOW = 8.seconds

/** How many adds [AppState.addedTasks] holds for a Downloads page that is still busy. */
private const val ADDED_BUFFER = 8

/**
 * How many recent adds [AppState] remembers to report each once; the two reports of an add
 * arrive moments apart.
 */
private const val RECENT_ADDS_LIMIT = 256

/** Compose state that the models outside composition also read, through [flow]. */
private class FlowState<T>(initial: T) {
  private var state by mutableStateOf(initial)

  /** The value as it changes. */
  val flow = MutableStateFlow(initial)

  operator fun getValue(thisRef: Any?, property: KProperty<*>): T = state

  operator fun setValue(thisRef: Any?, property: KProperty<*>, value: T) {
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

/** "2 scheduled still start at 02:00" for the scheduled tasks a bulk pause leaves alone. */
private fun scheduledNote(schedules: List<DownloadSchedule>): String? {
  if (schedules.isEmpty()) return null
  val first = schedules.filterIsInstance<DownloadSchedule.AtTime>().minOfOrNull { it.startAt }
    ?: return "${schedules.size} scheduled still start on time"
  val time = clockTime(first, TimeZone.currentSystemDefault())
  return "${schedules.size} scheduled still start at $time"
}

/** Name of this task for messages, from its current request and state. */
private fun DownloadTask.displayName(): String = displayName(requestState.value, state.value)

/** The name of the only one of [tasks], else how many they are, such as "3 downloads". */
private fun what(tasks: List<DownloadTask>): String =
  tasks.singleOrNull()?.displayName() ?: downloads(tasks.size)

/**
 * Whether this task's file is unfinished, so removing the task once it was sent elsewhere takes
 * the file too; a finished file is kept.
 */
private val DownloadTask.hasPartialFile: Boolean
  get() = state.value !is DownloadState.Completed

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
