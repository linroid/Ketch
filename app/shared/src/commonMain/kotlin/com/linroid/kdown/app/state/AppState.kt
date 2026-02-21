package com.linroid.kdown.app.state

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.linroid.kdown.api.DownloadPriority
import com.linroid.kdown.api.DownloadRequest
import com.linroid.kdown.api.DownloadSchedule
import com.linroid.kdown.api.DownloadState
import com.linroid.kdown.api.DownloadTask
import com.linroid.kdown.api.KDownApi
import com.linroid.kdown.api.ResolvedSource
import com.linroid.kdown.api.SpeedLimit
import com.linroid.kdown.app.instance.DiscoveredServer
import com.linroid.kdown.app.instance.InstanceEntry
import com.linroid.kdown.app.instance.InstanceManager
import com.linroid.kdown.app.instance.LanServerDiscovery
import com.linroid.kdown.app.instance.RemoteInstance
import com.linroid.kdown.app.instance.ServerState
import com.linroid.kdown.remote.ConnectionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
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
  data class Error(val message: String) : ResolveState
}

@OptIn(ExperimentalCoroutinesApi::class)
class AppState(
  val instanceManager: InstanceManager,
  private val scope: CoroutineScope,
) {
  private val lanServerDiscovery = LanServerDiscovery()

  val activeApi: StateFlow<KDownApi> =
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
  private var resolvingUrl: String? = null

  init {
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
    resolvingUrl = url
    resolveState = ResolveState.Resolving
    scope.launch {
      runCatching {
        activeApi.value.resolve(url)
      }.onSuccess { result ->
        if (resolvingUrl == url) {
          resolveState = ResolveState.Resolved(result)
        }
      }.onFailure { e ->
        if (resolvingUrl == url) {
          resolveState = ResolveState.Error(
            e.message ?: "Failed to resolve URL"
          )
        }
      }
    }
  }

  fun resetResolveState() {
    resolvingUrl = null
    resolveState = ResolveState.Idle
  }

  fun startDownload(
    url: String,
    fileName: String,
    speedLimit: SpeedLimit,
    priority: DownloadPriority,
    schedule: DownloadSchedule = DownloadSchedule.Immediate,
    resolvedUrl: ResolvedSource? = null,
  ) {
    scope.launch {
      runCatching {
        val request = DownloadRequest(
          url = url,
          directory = null,
          fileName = fileName.ifBlank { null },
          speedLimit = speedLimit,
          priority = priority,
          schedule = schedule,
          resolvedUrl = resolvedUrl,
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

  fun dismissError() {
    errorMessage = null
  }
}
