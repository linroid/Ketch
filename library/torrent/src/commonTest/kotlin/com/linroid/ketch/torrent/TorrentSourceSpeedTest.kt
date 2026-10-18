package com.linroid.ketch.torrent

import com.linroid.ketch.api.DownloadRequest
import com.linroid.ketch.core.engine.DownloadContext
import com.linroid.ketch.core.engine.HttpEngine
import com.linroid.ketch.core.engine.SelectionUpdate
import com.linroid.ketch.core.engine.ServerInfo
import com.linroid.ketch.core.file.FileAccessor
import com.linroid.ketch.torrent.crypto.torrentRandomBytes
import com.linroid.ketch.torrent.engine.KotlinTorrentEngine
import com.linroid.ketch.torrent.engine.TorrentSessionState
import com.linroid.ketch.torrent.engine.TorrentV2TaskSpec
import com.linroid.ketch.torrent.metainfo.Bencode
import com.linroid.ketch.torrent.metainfo.InfoHash
import com.linroid.ketch.torrent.net.TorrentHttp
import com.linroid.ketch.torrent.net.createTorrentNetwork
import com.linroid.ketch.torrent.storage.torrentFileSystem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okio.FileSystem
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.TimeSource

/** The speed a torrent task reports while its download runs. */
class TorrentSourceSpeedTest {
  private object NoIo : FileAccessor {
    override suspend fun writeAt(offset: Long, data: ByteArray): Unit = error("Source owns I/O")
    override suspend fun flush(): Unit = error("Source owns I/O")
    override fun close() = Unit
    override suspend fun delete(): Unit = error("Source owns I/O")
    override suspend fun size(): Long = error("Source owns I/O")
    override suspend fun preallocate(size: Long): Unit = error("Source owns I/O")
  }

  /** A tracker naming the peer listening on [port] of loopback for every announce. */
  private class Tracker(private val port: Int) : HttpEngine {
    override suspend fun head(url: String, headers: Map<String, String>): ServerInfo =
      error("Unused")

    override suspend fun download(
      url: String,
      range: LongRange?,
      headers: Map<String, String>,
      onData: suspend (ByteArray) -> Unit,
    ) = onData(Bencode.encode(mapOf("interval" to 3600L,
      "peers" to listOf(mapOf("ip" to "127.0.0.1", "port" to port.toLong())))))

    override fun close() = Unit
  }

  /** One sample of the task's reports: when, the progress it reported, and its speed. */
  private class Report(val atMs: Long, val downloaded: Long, val speed: Long?)

  @Test
  fun download_v2BlocksSecondsApartUnderALowLimit_reportsNearTheLimit() = runTest {
    withContext(Dispatchers.Default) {
      withTimeout(40_000) {
        val root = FileSystem.SYSTEM_TEMPORARY_DIRECTORY /
          "ketch-source-speed-${InfoHash.fromBytes(torrentRandomBytes(20)).hex}"
        torrentFileSystem.createDirectories(root)
        // Two pieces of two 16 KiB blocks, which a v2 owner asks for one at a time.
        val fixture = TorrentV2Fixture.build(listOf("data.bin" to 4 * BLOCK),
          pieceLength = 2 * BLOCK)
        val seeder = KotlinTorrentEngine(TorrentConfig(encryption = TorrentEncryption.DISABLED,
          dhtEnabled = false, uploadPolicy = TorrentUploadPolicy.SEED_AFTER_COMPLETION),
          listenHost = "127.0.0.1")
        val engines = mutableListOf<KotlinTorrentEngine>()
        var source: TorrentDownloadSource? = null
        try {
          seeder.start()
          val seedOutput = root / "seed"
          seeder.addV2Task(TorrentV2TaskSpec("seed", fixture.document, seedOutput.toString(),
            checkpoint = fixture.preseed(seedOutput, "seed"),
            privacy = TorrentDiscoveryPrivacy.PUBLIC)).also { seed ->
            seed.resume()
            seed.state.first { it == TorrentSessionState.SEEDING }
          }
          val tracker = Tracker(seeder.listenPort)
          val downloader = TorrentDownloadSource(TorrentConfig(
            encryption = TorrentEncryption.DISABLED, dhtEnabled = false, portMapping = false,
            localDiscovery = false), tracker).also {
            it.engineFactory = { config ->
              KotlinTorrentEngine(config, createTorrentNetwork(), TorrentHttp(tracker),
                listenHost = "127.0.0.1").also { engine -> engines += engine }
            }
          }
          source = downloader
          // Metainfo keys are sorted: `announce` goes before `info`.
          val announce = "http://tracker/announce"
          val metainfo = "d8:announce${announce.length}:$announce".encodeToByteArray() +
            fixture.metainfo.copyOfRange(1, fixture.metainfo.size)
          val resolved = downloader.resolveMetainfo(metainfo)
          val files = resolved.files.map { it.id }.toSet()
          // A 5 KiB/s task limit without a burst: each block waits 3.2 s after the last.
          val started = TimeSource.Monotonic.markNow()
          val paced = Mutex()
          var dueMs = 0L
          val downloaded = MutableStateFlow(0L)
          val context = DownloadContext(taskId = "speed", url = resolved.url,
            request = DownloadRequest(resolved.url, selectedFileIds = files), fileAccessor = NoIo,
            segments = MutableStateFlow(emptyList()),
            onProgress = { bytes, _ -> downloaded.value = bytes },
            throttle = { bytes ->
              paced.withLock {
                val now = started.elapsedNow().inWholeMilliseconds
                dueMs = maxOf(dueMs, now) + bytes * 1000L / LIMIT
                delay(dueMs - now)
              }
            },
            headers = emptyMap(), preResolved = resolved, outputPath = (root / "out").toString(),
            selection = MutableStateFlow(SelectionUpdate(files, 0)), globalThrottle = {},
          )
          val reports = mutableListOf<Report>()
          coroutineScope {
            val sampling = launch {
              while (true) {
                reports += Report(started.elapsedNow().inWholeMilliseconds, downloaded.value,
                  context.reportedSpeed.value)
                delay(100)
              }
            }
            downloader.download(context, null)
            sampling.cancel()
          }
          // Once its first piece, two blocks, is in, it reads near the limit until the last
          // block, never a block's worth in one second and none in the next.
          val firstPieceAt = reports.first { it.downloaded >= 2 * BLOCK }.atMs
          val steady = reports.filter { it.atMs >= firstPieceAt + 300 }
          assertTrue(steady.size >= 30, "Only ${steady.size} reports after the first piece")
          for (report in steady) {
            assertTrue(report.speed != null && report.speed in LIMIT * 4 / 5..LIMIT * 6 / 5,
              "Read ${report.speed} B/s at ${report.atMs} ms, ${report.downloaded} bytes in")
          }
        } finally {
          withContext(NonCancellable) {
            source?.close()
            engines.forEach { it.stop() }
            seeder.stop()
            torrentFileSystem.deleteRecursively(root, mustExist = false)
          }
        }
      }
    }
  }

  private companion object {
    const val BLOCK = 16_384

    /** Bytes per second. */
    const val LIMIT = 5_120L
  }
}
