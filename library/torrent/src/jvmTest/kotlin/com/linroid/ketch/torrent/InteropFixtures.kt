package com.linroid.ketch.torrent

import com.linroid.ketch.api.Destination
import com.linroid.ketch.api.DownloadRequest
import com.linroid.ketch.api.DownloadState
import com.linroid.ketch.api.ResolvedSource
import com.linroid.ketch.api.SpeedLimit
import com.linroid.ketch.core.Ketch
import com.linroid.ketch.core.engine.DownloadContext
import com.linroid.ketch.core.file.FileAccessor
import com.linroid.ketch.engine.KtorHttpEngine
import com.linroid.ketch.torrent.engine.KotlinTorrentEngine
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import org.libtorrent4j.AlertListener
import org.libtorrent4j.SessionManager
import org.libtorrent4j.SessionParams
import org.libtorrent4j.SettingsPack
import org.libtorrent4j.TorrentInfo
import org.libtorrent4j.alerts.Alert
import org.libtorrent4j.alerts.AlertType
import org.libtorrent4j.alerts.ListenFailedAlert
import org.libtorrent4j.swig.settings_pack
import java.io.File
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.random.Random
import kotlin.test.assertIs
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

internal fun sourceContext(
  url: String,
  resolved: ResolvedSource,
  output: String,
  taskId: String = "interop",
): DownloadContext =
  DownloadContext(taskId = taskId, url = url, request = DownloadRequest(url),
    fileAccessor = object : FileAccessor {
      override suspend fun writeAt(offset: Long, data: ByteArray) = Unit
      override suspend fun flush() = Unit
      override fun close() = Unit
      override suspend fun delete() = Unit
      override suspend fun size(): Long = 0
      override suspend fun preallocate(size: Long) = Unit
    },
    segments = MutableStateFlow(emptyList()), onProgress = { _, _ -> }, throttle = {},
    headers = emptyMap(), preResolved = resolved, outputPath = output,
  )

/** Connections Ketch's engine opened when the selection changed, and by the end. */
internal class LiveExpansion(val connectsAtChange: Int, val connects: Int)

/**
 * Downloads [first] of the torrent [metainfo] through Ketch from the peer [magnet] names, held
 * by a task speed limit, then chooses [expanded] while it runs, lifts the limit and waits until
 * the download completes in [output]. The source must leave nothing behind.
 */
@OptIn(ExperimentalAtomicApi::class)
internal suspend fun downloadExpandingLive(
  magnet: String,
  metainfo: ByteArray,
  output: String,
  first: Set<String>,
  expanded: Set<String>,
): LiveExpansion {
  val network = CountingTorrentNetwork()
  var engine: KotlinTorrentEngine? = null
  val source = TorrentDownloadSource(TorrentConfig(dhtEnabled = false)).also { source ->
    source.engineFactory = { config ->
      KotlinTorrentEngine(config, network, listenHost = "127.0.0.1").also { engine = it }
    }
  }
  val ketch = Ketch(KtorHttpEngine(), additionalSources = listOf(source))
  try {
    ketch.start()
    // One byte per second once the limiter's burst is spent: the task runs but cannot finish.
    val task = ketch.download(DownloadRequest(magnet, destination = Destination(output),
      resolvedSource = source.resolveMetainfo(metainfo), selectedFileIds = first,
      speedLimit = SpeedLimit.of(1)))
    awaitStage("the first verified bytes", task, timeoutMs = 30_000) {
      task.segments.first { segments -> segments.sumOf { it.downloadedBytes } > 0 }
    }
    assertIs<DownloadState.Downloading>(task.state.value)
    val atChange = network.connects.load()
    task.selectFiles(expanded)
    task.setSpeedLimit(SpeedLimit.Unlimited)
    awaitStage("completion", task, timeoutMs = 30_000) { task.await().getOrThrow() }
    source.assertNoLeaks()
    return LiveExpansion(atChange, network.connects.load())
  } finally {
    ketch.close()
    engine?.stop()
    engine?.assertNoLeaks()
  }
}

/** libtorrent settings for a peer on loopback only: no DHT, local discovery or port mapping. */
internal fun loopbackSettings(): SettingsPack = SettingsPack().also { settings ->
  for (flag in listOf(settings_pack.bool_types.enable_dht, settings_pack.bool_types.enable_lsd,
    settings_pack.bool_types.enable_upnp, settings_pack.bool_types.enable_natpmp)) {
    settings.setBoolean(flag.swigValue(), false)
  }
}

/**
 * Starts this session with [settings] seeding [info] from [directory] and waits until it seeds
 * and listens. Returns the port it listens on.
 *
 * libtorrent listens on loopback at an explicit port below every system's ephemeral range
 * (Linux starts at 32768, macOS and Windows at 49152) rather than port 0. A session that has not
 * listened within 5 s, as happened on Windows CI with port 0, listens again on another port, and
 * the listen failures libtorrent reports are printed, so a seeder that never listens says why.
 */
internal suspend fun SessionManager.startSeeding(
  info: TorrentInfo,
  directory: File,
  settings: SettingsPack = loopbackSettings(),
): Int {
  fun listenOnAnotherPort(pack: SettingsPack) = pack.setString(
    settings_pack.string_types.listen_interfaces.swigValue(),
    "127.0.0.1:${Random.nextInt(20_000, 32_000)}",
  )
  addListener(object : AlertListener {
    override fun types() = intArrayOf(AlertType.LISTEN_FAILED.swig())
    override fun alert(alert: Alert<*>) {
      val failed = alert as ListenFailedAlert
      println("libtorrent seeder: listening on ${failed.port()} failed (${failed.socketType()}, " +
        "${failed.operation()}): ${failed.error().message}")
    }
  })
  listenOnAnotherPort(settings)
  start(SessionParams(settings))
  download(info, directory)
  var listening = TimeSource.Monotonic.markNow()
  var reported: String? = null
  while (true) {
    val status = find(info.infoHash())?.status()
    val port = swig().listen_port()
    val state = "state=${status?.state()} progress=${status?.progress()} port=$port"
    if (state != reported) println("libtorrent seeder: $state").also { reported = state }
    check(status == null || !status.errorCode().isError) {
      "libtorrent seeder failed: ${status?.errorCode()?.message}"
    }
    if (port != 0 && status?.isSeeding() == true) return port
    if (port == 0 && listening.elapsedNow() > 5.seconds) {
      println("libtorrent seeder: not listening after 5 s, listening on another port")
      applySettings(SettingsPack().also(::listenOnAnotherPort))
      listening = TimeSource.Monotonic.markNow()
    }
    delay(20)
  }
}
