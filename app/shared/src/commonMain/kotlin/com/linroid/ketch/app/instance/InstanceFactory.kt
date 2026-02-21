package com.linroid.ketch.app.instance

import com.linroid.ketch.api.KetchApi
import com.linroid.ketch.api.config.DownloadConfig
import com.linroid.ketch.api.config.RemoteConfig
import com.linroid.ketch.core.Ketch
import com.linroid.ketch.api.log.Logger
import com.linroid.ketch.core.task.TaskStore
import com.linroid.ketch.engine.KtorHttpEngine
import com.linroid.ketch.remote.RemoteKetch

/**
 * Creates [KetchApi] instances for each instance type.
 *
 * @param taskStore persistent storage for download task records.
 *   Required when using the default embedded instance. Pass `null`
 *   for remote-only mode (e.g. wasmJs/web).
 * @param deviceName label for the embedded instance (e.g. device
 *   model on Android, hostname on desktop).
 * @param embeddedFactory factory for creating the embedded Ketch
 *   instance. When `null`, no embedded instance is available and
 *   [InstanceManager] starts in remote-only mode.
 *   Override in tests to inject fakes.
 * @param localServerFactory optional factory that starts an HTTP
 *   server exposing the embedded [KetchApi]. Receives port,
 *   optional API token, and the embedded KetchApi instance.
 *   When non-null, server controls appear in the Embedded
 *   instance entry. Provided by Android and JVM/Desktop.
 */
class InstanceFactory(
  taskStore: TaskStore? = null,
  defaultDirectory: String = "downloads",
  downloadConfig: DownloadConfig = DownloadConfig(
    defaultDirectory = defaultDirectory,
  ),
  val deviceName: String = "Embedded",
  private val embeddedFactory: (() -> Ketch)? = taskStore?.let { ts ->
    { createDefaultEmbeddedKetch(ts, downloadConfig, deviceName) }
  },
  private val localServerFactory:
    ((port: Int, apiToken: String?, KetchApi) -> LocalServerHandle)? = null,
) {
  /** Whether an embedded instance is available. */
  val hasEmbedded: Boolean get() = embeddedFactory != null

  /** Whether this platform supports starting a local server. */
  val isLocalServerSupported: Boolean
    get() = localServerFactory != null

  private var localServer: LocalServerHandle? = null

  /** Create the embedded Ketch instance. */
  fun createEmbedded(): EmbeddedInstance {
    val ketch = embeddedFactory?.invoke()
      ?: throw UnsupportedOperationException(
        "No embedded instance available"
      )
    return EmbeddedInstance(
      instance = ketch,
      label = deviceName,
    )
  }

  /** Create a remote instance from a [RemoteConfig]. */
  fun createRemote(config: RemoteConfig): RemoteInstance {
    return RemoteInstance(
      instance = RemoteKetch(
        config.host, config.port, config.apiToken, config.secure,
      ),
      label = "${config.host}:${config.port}",
      remoteConfig = config,
    )
  }

  /** Create a remote instance for the given host/port/token. */
  fun createRemote(
    host: String,
    port: Int = 8642,
    token: String? = null,
  ): RemoteInstance {
    return createRemote(
      RemoteConfig(host = host, port = port, apiToken = token),
    )
  }

  /**
   * Start a local HTTP server exposing [api].
   * Does not change the active instance.
   */
  fun startServer(
    port: Int,
    apiToken: String?,
    api: KetchApi,
  ) {
    val factory = localServerFactory
      ?: throw UnsupportedOperationException(
        "Local server not supported on this platform"
      )
    localServer = factory(port, apiToken, api)
  }

  /** Stop the local server if running (does not close Ketch). */
  fun stopServer() {
    localServer?.stop()
    localServer = null
  }
}

private fun createDefaultEmbeddedKetch(
  taskStore: TaskStore,
  config: DownloadConfig,
  name: String,
): Ketch {
  return Ketch(
    httpEngine = KtorHttpEngine(),
    taskStore = taskStore,
    config = config,
    name = name,
    logger = Logger.console(),
  )
}
