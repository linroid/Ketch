package com.linroid.ketch.app.state

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.linroid.ketch.api.Destination
import com.linroid.ketch.api.DownloadConfig
import com.linroid.ketch.api.DownloadPriority
import com.linroid.ketch.api.DownloadRequest
import com.linroid.ketch.api.DownloadSchedule
import com.linroid.ketch.api.DownloadState
import com.linroid.ketch.api.DownloadTask
import com.linroid.ketch.api.KetchApi
import com.linroid.ketch.api.KetchError
import com.linroid.ketch.api.ResolvedSource
import com.linroid.ketch.api.SpeedLimit
import com.linroid.ketch.app.instance.DiscoveredServer
import com.linroid.ketch.app.instance.InstanceEntry
import com.linroid.ketch.app.instance.InstanceManager
import com.linroid.ketch.app.instance.LanServerDiscovery
import com.linroid.ketch.app.instance.EmbeddedInstance
import com.linroid.ketch.app.instance.RemoteInstance
import com.linroid.ketch.app.instance.ServerState
import com.linroid.ketch.app.platform.DroppedFile
import com.linroid.ketch.remote.ConnectionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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

@OptIn(ExperimentalCoroutinesApi::class)
class AppState(
  val instanceManager: InstanceManager,
  private val scope: CoroutineScope,
  val appSettings: AppSettingsController = AppSettingsController(),
  val aiSettings: AiSettingsController = AiSettingsController(),
  private val incoming: IncomingDownloads = IncomingDownloads(),
) {
  private val lanServerDiscovery = LanServerDiscovery()

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

  val tasks: StateFlow<List<DownloadTask>> =
    activeApi.flatMapLatest { it.tasks }.stateIn(
      scope,
      SharingStarted.WhileSubscribed(5000),
      emptyList()
    )

  val sortedTasks: StateFlow<List<DownloadTask>> =
    tasks.map { it.sortedByDescending { t -> t.createdAt } }
      .stateIn(
        scope,
        SharingStarted.WhileSubscribed(5000),
        emptyList()
      )

  // UI state
  var statusFilter by mutableStateOf(StatusFilter.All)
  var errorMessage by mutableStateOf<String?>(null)
  var showAddDialog by mutableStateOf(false)
  var showInstanceSelector by mutableStateOf(false)
  var showAddRemoteDialog by mutableStateOf(false)
  var aiDiscoverState by mutableStateOf<AiDiscoverState>(
    AiDiscoverState.Idle
  )
    private set

  /** The opened file the add dialog shows, or null when the user types a URL. */
  var openedDownload by mutableStateOf<IncomingDownload.Ready?>(null)
    private set

  /**
   * Handle "New Task" action. If no backend is available,
   * show the add-remote-server dialog instead.
   */
  fun requestAddDownload() {
    if (activeInstance.value == null) {
      showAddRemoteDialog = true
    } else {
      showAddDialog = true
    }
  }

  /** Closes the add dialog; the next opened download, if one is waiting, then shows. */
  fun closeAddDialog() {
    resetResolveState()
    showAddDialog = false
    openedDownload?.let(incoming::complete)
  }
  var discoveryState by mutableStateOf<DiscoveryState>(
    DiscoveryState.Idle
  )
    private set
  private var aiDiscoveryJob: Job? = null
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

  init {
    scope.launch {
      // Opened files show one at a time in the add dialog, like a dropped file, and wait for a
      // backend if none is connected. Pending files survive a recreated UI and show again.
      combine(incoming.pending, activeInstance) { pending, instance ->
        pending.firstOrNull() to (instance != null)
      }.collect { (next, connected) ->
        when {
          next == null -> openedDownload = null
          !connected -> showAddRemoteDialog = true
          next != openedDownload -> {
            openedDownload = next
            resolveDroppedFile(DroppedFile(next.label) { next.content })
            showAddDialog = true
          }
        }
      }
    }
    scope.launch {
      incoming.failures.collect {
        errorMessage = "Couldn't open ${it.label}: ${it.message}"
      }
    }
    scope.launch {
      connectionState.collect { state ->
        if (state is ConnectionState.Unauthorized) {
          val instance =
            activeInstance.value as? RemoteInstance
          if (instance != null) {
            unauthorizedInstance = instance
            showAddRemoteDialog = true
          }
        }
      }
    }
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
   * Opens the add dialog for the first `.torrent` file in [files], which
   * is then resolved by the active instance instead of a typed URL.
   */
  fun addDroppedFiles(files: List<DroppedFile>) {
    val torrent = files.firstOrNull {
      it.name.endsWith(".torrent", ignoreCase = true)
    }
    if (torrent == null) {
      errorMessage = "Only .torrent files can be dropped to add a download"
      return
    }
    requestAddDownload()
    if (showAddDialog) resolveDroppedFile(torrent)
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
        if (e is kotlinx.coroutines.CancellationException) throw e
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
    scope.launch {
      runCatching {
        val request = DownloadRequest(
          url = url,
          destination = fileName.ifBlank { null }
            ?.let { Destination(it) },
          speedLimit = speedLimit,
          priority = priority,
          schedule = schedule,
          resolvedSource = resolvedUrl,
          selectedFileIds = selectedFileIds,
        )
        activeApi.value.download(request)
      }.onFailure { e ->
        errorMessage =
          e.message ?: "Failed to start download"
      }
    }
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
      } catch (e: Exception) {
        errorMessage =
          "Failed to switch instance: ${e.message}"
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
      errorMessage =
        "Failed to add remote server: ${e.message}"
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
      } catch (e: Exception) {
        errorMessage =
          "Failed to remove instance: ${e.message}"
      }
    }
  }

  fun pauseAll() {
    scope.launch {
      tasks.value.forEach { task ->
        if (task.state.value.isActive) task.pause()
      }
    }
  }

  fun resumeAll() {
    scope.launch {
      tasks.value.forEach { task ->
        if (task.state.value is DownloadState.Paused) {
          task.resume()
        }
      }
    }
  }

  fun clearCompleted() {
    scope.launch {
      tasks.value.forEach { task ->
        if (task.state.value is DownloadState.Completed) {
          task.remove()
        }
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
      } catch (e: Exception) {
        errorMessage =
          "Failed to reconnect: ${e.message}"
      }
    }
  }

  /**
   * Persists download settings and applies them to the active instance,
   * which takes effect without a restart.
   */
  fun applyDownloadConfig(config: DownloadConfig) {
    appSettings.saveDownload(config)
    scope.launch {
      runCatching { activeApi.value.updateConfig(config) }
        .onFailure { e ->
          if (e is kotlinx.coroutines.CancellationException) throw e
          errorMessage =
            e.message ?: "Failed to apply download settings"
        }
    }
  }

  fun aiDiscover(query: String, sites: String) {
    aiDiscoveryJob?.cancel()
    val provider = aiSettings.provider
    if (provider == null) {
      aiDiscoverState = AiDiscoverState.Error(
        if (aiSettings.supported) {
          "Add an AI provider and API token in Settings first."
        } else {
          "AI discovery is not available on this platform."
        },
      )
      return
    }
    aiDiscoverState = AiDiscoverState.Loading
    aiDiscoveryJob = scope.launch {
      runCatching {
        val siteList = sites.split(",", " ")
          .map { it.trim() }
          .filter { it.isNotBlank() }
        provider.discover(
          AiDiscoverRequest(
            query = query,
            sites = siteList,
          ),
        )
      }.onSuccess { response ->
        aiDiscoverState = AiDiscoverState.Results(
          candidates = response.candidates,
        )
      }.onFailure { e ->
        if (e is kotlinx.coroutines.CancellationException) throw e
        aiDiscoverState = AiDiscoverState.Error(
          e.message ?: "Discovery failed",
        )
      }
    }
  }

  fun aiDownloadSelected(candidates: List<AiCandidate>) {
    showAddDialog = false
    resetAiDiscover()
    scope.launch {
      runCatching {
        val api = activeApi.value
        candidates.forEach { c ->
          api.download(
            DownloadRequest(
              url = c.url,
              destination = c.fileName
                ?.let { Destination(it) },
            ),
          )
        }
      }.onFailure { e ->
        errorMessage =
          e.message ?: "Failed to start AI downloads"
      }
    }
  }

  fun resetAiDiscover() {
    aiDiscoveryJob?.cancel()
    aiDiscoveryJob = null
    aiDiscoverState = AiDiscoverState.Idle
  }

  fun dismissError() {
    errorMessage = null
  }

  private companion object {
    /** Matches the daemon's upload limit; torrent metainfo is 4 MiB by default. */
    const val MAX_DROPPED_FILE_BYTES = 16L * 1024 * 1024
  }
}
