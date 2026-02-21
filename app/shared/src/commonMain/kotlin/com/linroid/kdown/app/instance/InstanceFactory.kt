package com.linroid.kdown.app.instance

import com.linroid.kdown.api.KDownApi
import com.linroid.kdown.api.config.DownloadConfig
import com.linroid.kdown.api.config.RemoteConfig
import com.linroid.kdown.core.KDown
import com.linroid.kdown.core.log.Logger
import com.linroid.kdown.core.task.TaskStore
import com.linroid.kdown.engine.KtorHttpEngine
import com.linroid.kdown.remote.RemoteKDown

/**
 * Creates [KDownApi] instances for each instance type.
 *
 * @param taskStore persistent storage for download task records.
 *   Required when using the default embedded instance. Pass `null`
 *   for remote-only mode (e.g. wasmJs/web).
 * @param deviceName label for the embedded instance (e.g. device
 *   model on Android, hostname on desktop).
 * @param embeddedFactory factory for creating the embedded KDown
 *   instance. When `null`, no embedded instance is available and
 *   [InstanceManager] starts in remote-only mode.
 *   Override in tests to inject fakes.
 * @param localServerFactory optional factory that starts an HTTP
 *   server exposing the embedded [KDownApi]. Receives port,
 *   optional API token, and the embedded KDownApi instance.
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
  private val embeddedFactory: (() -> KDown)? = taskStore?.let { ts ->
    { createDefaultEmbeddedKDown(ts, downloadConfig, deviceName) }
  },
  private val localServerFactory:
    ((port: Int, apiToken: String?, KDownApi) -> LocalServerHandle)? = null,
) {
  /** Whether an embedded instance is available. */
  val hasEmbedded: Boolean get() = embeddedFactory != null

  /** Whether this platform supports starting a local server. */
  val isLocalServerSupported: Boolean
    get() = localServerFactory != null

  private var localServer: LocalServerHandle? = null

  /** Create the embedded KDown instance. */
  fun createEmbedded(): EmbeddedInstance {
    val kdown = embeddedFactory?.invoke()
      ?: throw UnsupportedOperationException(
        "No embedded instance available"
      )
    return EmbeddedInstance(
      instance = kdown,
      label = deviceName,
    )
  }

  /** Create a remote instance from a [RemoteConfig]. */
  fun createRemote(config: RemoteConfig): RemoteInstance {
    return RemoteInstance(
      instance = RemoteKDown(
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
  config: DownloadConfig,
  name: String,
): KDown {
  return KDown(
    httpEngine = KtorHttpEngine(),
    taskStore = taskStore,
    config = config,
    name = name,
    logger = Logger.console(),
  )
}
