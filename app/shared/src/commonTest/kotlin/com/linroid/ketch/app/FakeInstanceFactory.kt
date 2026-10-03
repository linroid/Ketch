package com.linroid.ketch.app

import com.linroid.ketch.api.DownloadConfig
import com.linroid.ketch.api.DownloadRequest
import com.linroid.ketch.api.DownloadTask
import com.linroid.ketch.api.KetchApi
import com.linroid.ketch.api.KetchStatus
import com.linroid.ketch.api.ResolvedSource
import com.linroid.ketch.app.instance.InstanceFactory
import com.linroid.ketch.app.instance.InstanceManager
import com.linroid.ketch.app.instance.RemoteInstance
import com.linroid.ketch.config.RemoteConfig
import com.linroid.ketch.remote.ConnectionState
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Builds the [InstanceFactory] of an [InstanceManager] under test, whose remote devices are
 * [FakeRemote] clients, and records every client it creates.
 *
 * Usage:
 * ```
 * val fakes = FakeInstanceFactory()
 * val manager = InstanceManager(fakes.factory, initialRemotes = listOf(RemoteConfig("nas")))
 * fakes.clientsOf("nas:8642").single().startCount
 * ```
 *
 * @param embeddedFactory creates the embedded device; `null` for remote-only mode.
 */
class FakeInstanceFactory(
  embeddedFactory: (() -> KetchApi)? = { FakeKetchApi("Core") },
) {
  /** Every remote client created, oldest first. */
  val remotes: MutableList<FakeRemote> = mutableListOf()

  /** State a remote client reaches when started; set it before the client is created. */
  var stateOnStart: ConnectionState = ConnectionState.Connected

  /** Name new remote clients report in their status. */
  var announcedName: String = "Ketch"

  /** Operating system new remote clients report in their status. */
  var announcedOs: String = "Linux"

  /** The factory to build the manager with. */
  val factory: InstanceFactory = InstanceFactory(
    deviceName = "MacBook Pro",
    embeddedFactory = embeddedFactory,
    remoteFactory = ::createRemote,
  )

  /** Clients created for the device with [deviceId] (`host:port`), oldest first. */
  fun clientsOf(deviceId: String): List<FakeRemote> = remotes.filter { it.deviceId == deviceId }

  private fun createRemote(config: RemoteConfig): RemoteInstance {
    val client = FakeRemote(config, stateOnStart, announcedName, announcedOs)
    remotes += client
    return RemoteInstance(client, config, client.connection)
  }
}

/**
 * A remote device's client that tests drive directly. Starting it after [close] sets
 * [startedAfterClose] instead, so a test notices a manager reusing a closed client.
 *
 * @property config the connection it was created for.
 */
class FakeRemote(
  val config: RemoteConfig,
  private val stateOnStart: ConnectionState,
  private val announcedName: String,
  private val announcedOs: String,
) : KetchApi {
  /** `host:port` of the device. */
  val deviceId: String = "${config.host}:${config.port}"

  /** Connection state the manager sees; tests move it. */
  val connection = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected("Not started"))

  override val backendLabel: String = "Remote · $deviceId"
  override val tasks = MutableStateFlow<List<DownloadTask>>(emptyList())

  var startCount = 0
    private set
  var closed = false
    private set
  var startedAfterClose = false
    private set

  override suspend fun start() {
    if (closed) startedAfterClose = true
    startCount++
    connection.value = stateOnStart
  }

  override fun close() {
    closed = true
  }

  override suspend fun status(): KetchStatus = testStatus(
    name = announcedName,
    version = "0.0.1",
    uptime = 3600,
    system = testSystem(
      os = announcedOs,
      arch = "x64",
      javaVersion = "21",
      availableProcessors = 4,
      downloadDirectory = "/volume1/downloads",
      totalSpace = 4_000_000_000_000,
      freeSpace = 1_800_000_000_000,
      usableSpace = 1_800_000_000_000,
    ),
  )

  override suspend fun download(request: DownloadRequest): DownloadTask =
    throw UnsupportedOperationException("FakeRemote does not support downloads")

  override suspend fun resolve(url: String, properties: Map<String, String>): ResolvedSource =
    throw UnsupportedOperationException("FakeRemote does not support resolve")

  override suspend fun updateConfig(config: DownloadConfig) {}
}
