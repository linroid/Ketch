package com.linroid.kdown.app.backend

import com.linroid.kdown.api.KDownApi
import com.linroid.kdown.core.DownloadConfig
import com.linroid.kdown.core.KDown
import com.linroid.kdown.core.QueueConfig
import com.linroid.kdown.core.log.Logger
import com.linroid.kdown.core.task.TaskStore
import com.linroid.kdown.engine.KtorHttpEngine
import com.linroid.kdown.remote.RemoteKDown

/**
 * Creates [KDownApi] instances for each backend type.
 *
 * @param taskStore persistent storage for download task records.
 *   Required when using the default embedded backend. Pass `null`
 *   for remote-only mode (e.g. wasmJs/web).
 * @param embeddedFactory factory for creating the embedded KDown
 *   instance. When `null`, no embedded backend is available and
 *   [BackendManager] starts in remote-only mode.
 *   Override in tests to inject fakes.
 * @param localServerFactory optional factory that starts an HTTP
 *   server exposing the embedded [KDownApi]. Receives port,
 *   optional API token, and the embedded KDownApi instance.
 *   When non-null, server controls appear in the Embedded
 *   backend entry. Provided by Android and JVM/Desktop.
 */
class BackendFactory(
  taskStore: TaskStore? = null,
  defaultDirectory: String = "downloads",
  private val embeddedFactory: (() -> KDownApi)? = taskStore?.let { ts ->
    { createDefaultEmbeddedKDown(ts, defaultDirectory) }
  },
  private val localServerFactory:
    ((port: Int, apiToken: String?, KDownApi) -> LocalServerHandle)? = null,
) {
  /** Whether an embedded backend is available. */
  val hasEmbedded: Boolean get() = embeddedFactory != null

  /** Whether this platform supports starting a local server. */
  val isLocalServerSupported: Boolean
    get() = localServerFactory != null

  private var localServer: LocalServerHandle? = null

  /** Create the embedded KDown instance. */
  fun createEmbedded(): KDownApi {
    return embeddedFactory?.invoke()
      ?: throw UnsupportedOperationException(
        "No embedded backend available"
      )
  }

  /** Create a remote client for the given config. */
  fun createRemote(config: BackendConfig.Remote): KDownApi =
    RemoteKDown(config.baseUrl, config.apiToken)

  /**
   * Start a local HTTP server exposing [api].
   * Does not change the active backend.
   */
  fun startServer(
    port: Int,
    apiToken: String?,
    api: KDownApi,
  ) {
    val factory = localServerFactory
      ?: throw UnsupportedOperationException(
        "Local server not supported on this platform"
      )
    localServer = factory(port, apiToken, api)
  }

  /** Stop the local server if running (does not close KDown). */
  fun stopServer() {
    localServer?.stop()
    localServer = null
  }
}

private fun createDefaultEmbeddedKDown(
  taskStore: TaskStore,
  defaultDirectory: String,
): KDownApi {
  return KDown(
    httpEngine = KtorHttpEngine(),
    taskStore = taskStore,
    config = DownloadConfig(
      defaultDirectory = defaultDirectory,
      maxConnections = 4,
      retryCount = 3,
      retryDelayMs = 1000,
      progressUpdateIntervalMs = 200,
      queueConfig = QueueConfig(
        maxConcurrentDownloads = 3,
        maxConnectionsPerHost = 4,
      )
    ),
    logger = Logger.console(),
  )
}
