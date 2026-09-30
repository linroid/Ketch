package com.linroid.ketch.torrent

import com.linroid.ketch.api.Destination
import com.linroid.ketch.api.DownloadConfig
import com.linroid.ketch.api.DownloadRequest
import com.linroid.ketch.api.DownloadState
import com.linroid.ketch.api.DownloadTask
import com.linroid.ketch.api.SpeedLimit
import com.linroid.ketch.core.Ketch
import com.linroid.ketch.core.engine.HttpEngine
import com.linroid.ketch.core.engine.ServerInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import okio.FileSystem
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

class MixedDownloadLimitsTest {
  @Test
  fun globalLimitIsSharedWithHttpAndRemovingTaskLimitWakesPendingTorrentReads() = runTest {
    withContext(Dispatchers.Default) {
      withTimeout(TEST_TIMEOUT_MS) {
        val root = FileSystem.SYSTEM_TEMPORARY_DIRECTORY /
          "ketch-mixed-${InfoHash.fromBytes(torrentRandomBytes(20)).hex}"
        val bytes = ByteArray(128 * 1024) { (it * 13).toByte() }
        val hashes = bytes.asList().chunked(16_384).fold(ByteArray(0)) { value, chunk ->
          value + sha1Digest(chunk.toByteArray())
        }
        val data = Bencode.encode(mapOf("info" to mapOf("name" to "payload",
          "length" to bytes.size.toLong(), "piece length" to 16_384L, "pieces" to hashes)))
        val metadata = TorrentMetadata.fromBencode(data)
        torrentFileSystem.createDirectories(root)
        torrentFileSystem.write(root / "seed") { write(bytes) }
        val seeder = KotlinTorrentEngine(TorrentConfig(dhtEnabled = false,
          uploadPolicy = TorrentUploadPolicy.SEED_AFTER_COMPLETION))
        val source = TorrentDownloadSource(TorrentConfig(dhtEnabled = false))
        val http = object : HttpEngine {
          override suspend fun head(url: String, headers: Map<String, String>): ServerInfo =
            ServerInfo(bytes.size.toLong(), true, null, null)
          override suspend fun download(url: String, range: LongRange?,
            headers: Map<String, String>, onData: suspend (ByteArray) -> Unit) {
            val begin = range?.first?.toInt() ?: 0
            val end = range?.last?.toInt()?.plus(1) ?: bytes.size
            for (offset in begin until end step 16_384) {
              onData(bytes.copyOfRange(offset, minOf(end, offset + 16_384)))
            }
          }
          override fun close() = Unit
        }
        val config = DownloadConfig(speedLimit = SpeedLimit.of(65_536), maxConcurrentDownloads = 2,
          maxConnectionsPerDownload = 1)
        val ketch = Ketch(http, config = config, additionalSources = listOf(source))
        try {
          seeder.start()
          val seed = seeder.addTask(TorrentTaskSpec("limit-seed", metadata,
            (root / "seed").toString(), emptySet()))
          seed.resume()
          assertEquals(TorrentSessionState.SEEDING, awaitStage("the seeder to start seeding") {
            seed.state.first {
              it == TorrentSessionState.SEEDING || it == TorrentSessionState.STOPPED
            }
          })
          ketch.start()
          val magnet = MagnetUri(metadata.infoHash,
            explicitPeers = listOf("127.0.0.1:${seeder.listenPort}")).toUri()
          // One byte per second once the limiter burst is spent: a read left waiting on this
          // limit cannot finish within the test, however generous the wait below is.
          val torrent = ketch.download(DownloadRequest(magnet,
            destination = Destination((root / "torrent").toString()),
            resolvedSource = source.resolveMetainfo(data), speedLimit = SpeedLimit.of(1)))
          val ordinary = ketch.download(DownloadRequest("http://fixture/payload",
            destination = Destination((root / "http").toString())))
          delay(500)
          val transferred = torrent.segments.value.sumOf { it.downloadedBytes } +
            ordinary.segments.value.sumOf { it.downloadedBytes }
          // One shared 64 KiB burst plus half a second of refill and scheduler jitter.
          assertTrue(transferred <= 114_688, "Combined verified bytes: $transferred")
          ketch.updateConfig(config.copy(speedLimit = SpeedLimit.Unlimited))
          awaitStage("the HTTP download after lifting the global limit", ordinary) {
            ordinary.await().getOrThrow()
          }
          assertFalse(torrent.state.value is DownloadState.Completed)
          torrent.setSpeedLimit(SpeedLimit.Unlimited)
          awaitStage("the torrent download after lifting its task limit", torrent) {
            torrent.await().getOrThrow()
          }
          assertContentEquals(bytes, torrentFileSystem.read(root / "torrent") { readByteArray() })
          assertContentEquals(bytes, torrentFileSystem.read(root / "http") { readByteArray() })
        } finally {
          ketch.close()
          seeder.stop()
          torrentFileSystem.deleteRecursively(root, mustExist = false)
        }
      }
    }
  }

  @Test
  fun taskLimitStillAppliesToTorrentAfterPauseAndResume() = runTest {
    withContext(Dispatchers.Default) {
      withTimeout(TEST_TIMEOUT_MS) {
        val root = FileSystem.SYSTEM_TEMPORARY_DIRECTORY /
          "ketch-resume-limit-${InfoHash.fromBytes(torrentRandomBytes(20)).hex}"
        val bytes = ByteArray(256 * 1024) { (it * 7).toByte() }
        val hashes = bytes.asList().chunked(16_384).fold(ByteArray(0)) { value, chunk ->
          value + sha1Digest(chunk.toByteArray())
        }
        val data = Bencode.encode(mapOf("info" to mapOf("name" to "payload",
          "length" to bytes.size.toLong(), "piece length" to 16_384L, "pieces" to hashes)))
        val metadata = TorrentMetadata.fromBencode(data)
        torrentFileSystem.createDirectories(root)
        torrentFileSystem.write(root / "seed") { write(bytes) }
        val seeder = KotlinTorrentEngine(TorrentConfig(dhtEnabled = false,
          uploadPolicy = TorrentUploadPolicy.SEED_AFTER_COMPLETION))
        val source = TorrentDownloadSource(TorrentConfig(dhtEnabled = false))
        val ketch = Ketch(UnusedHttp, additionalSources = listOf(source))
        try {
          seeder.start()
          val seed = seeder.addTask(TorrentTaskSpec("resume-seed", metadata,
            (root / "seed").toString(), emptySet()))
          seed.resume()
          assertEquals(TorrentSessionState.SEEDING, awaitStage("the seeder to start seeding") {
            seed.state.first {
              it == TorrentSessionState.SEEDING || it == TorrentSessionState.STOPPED
            }
          })
          ketch.start()
          val magnet = MagnetUri(metadata.infoHash,
            explicitPeers = listOf("127.0.0.1:${seeder.listenPort}")).toUri()
          val torrent = ketch.download(DownloadRequest(magnet,
            destination = Destination((root / "torrent").toString()),
            resolvedSource = source.resolveMetainfo(data), speedLimit = SpeedLimit.of(1)))
          awaitStage("the torrent's first verified bytes", torrent) {
            torrent.segments.first { segments -> segments.sumOf { it.downloadedBytes } > 0 }
          }
          torrent.pause()
          torrent.resume()
          awaitStage("the resumed torrent to start downloading", torrent) {
            torrent.state.first { it is DownloadState.Downloading }
          }
          delay(500)
          // Each execution gets a fresh 64 KiB limiter burst and then one byte per second.
          val downloaded = torrent.segments.value.sumOf { it.downloadedBytes }
          assertTrue(downloaded <= 131_072, "Verified bytes after resume: $downloaded")
          assertFalse(torrent.state.value is DownloadState.Completed)
          torrent.setSpeedLimit(SpeedLimit.Unlimited)
          awaitStage("the torrent download after lifting its task limit", torrent) {
            torrent.await().getOrThrow()
          }
          assertContentEquals(bytes, torrentFileSystem.read(root / "torrent") { readByteArray() })
        } finally {
          ketch.close()
          seeder.stop()
          torrentFileSystem.deleteRecursively(root, mustExist = false)
        }
      }
    }
  }

  /**
   * Waits for [block] and names the [stage] when it takes too long, with the progress of [task].
   * The bound leaves room for slow CI runners; the limits under test would hold a download for
   * far longer than this.
   */
  private suspend fun <T : Any> awaitStage(
    stage: String,
    task: DownloadTask? = null,
    block: suspend () -> T,
  ): T = withTimeoutOrNull(STAGE_TIMEOUT_MS) { block() } ?: fail(buildString {
    append("Timed out after $STAGE_TIMEOUT_MS ms waiting for $stage")
    if (task != null) {
      append(": state=${task.state.value}, ")
      append("downloaded=${task.segments.value.sumOf { it.downloadedBytes }} bytes")
    }
  })

  private object UnusedHttp : HttpEngine {
    override suspend fun head(url: String, headers: Map<String, String>): ServerInfo =
      error("unused")
    override suspend fun download(
      url: String,
      range: LongRange?,
      headers: Map<String, String>,
      onData: suspend (ByteArray) -> Unit,
    ): Unit = error("unused")
    override fun close() = Unit
  }

  private companion object {
    const val STAGE_TIMEOUT_MS = 10_000L

    /** Above the sum of a test's stage waits, so a slow stage fails under its own name. */
    const val TEST_TIMEOUT_MS = 45_000L
  }
}
