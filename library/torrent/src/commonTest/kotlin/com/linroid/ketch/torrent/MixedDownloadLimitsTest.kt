package com.linroid.ketch.torrent

import com.linroid.ketch.api.Destination
import com.linroid.ketch.api.DownloadConfig
import com.linroid.ketch.api.DownloadRequest
import com.linroid.ketch.api.DownloadState
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
import okio.FileSystem
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MixedDownloadLimitsTest {
  @Test
  fun globalLimitIsSharedWithHttpAndRemovingTaskLimitWakesPendingTorrentReads() = runTest {
    withContext(Dispatchers.Default) {
      withTimeout(15_000) {
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
          assertEquals(TorrentSessionState.SEEDING, seed.state.first {
            it == TorrentSessionState.SEEDING || it == TorrentSessionState.STOPPED
          })
          ketch.start()
          val magnet = MagnetUri(metadata.infoHash,
            explicitPeers = listOf("127.0.0.1:${seeder.listenPort}")).toUri()
          val torrent = ketch.download(DownloadRequest(magnet,
            destination = Destination((root / "torrent").toString()),
            resolvedSource = source.resolveMetainfo(data), speedLimit = SpeedLimit.of(1024)))
          val ordinary = ketch.download(DownloadRequest("http://fixture/payload",
            destination = Destination((root / "http").toString())))
          delay(500)
          val transferred = torrent.segments.value.sumOf { it.downloadedBytes } +
            ordinary.segments.value.sumOf { it.downloadedBytes }
          // One shared 64 KiB burst plus half a second of refill and scheduler jitter.
          assertTrue(transferred <= 114_688, "Combined verified bytes: $transferred")
          ketch.updateConfig(config.copy(speedLimit = SpeedLimit.Unlimited))
          withTimeout(3000) { ordinary.await().getOrThrow() }
          assertFalse(torrent.state.value is DownloadState.Completed)
          torrent.setSpeedLimit(SpeedLimit.Unlimited)
          withTimeout(3000) { torrent.await().getOrThrow() }
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
}
