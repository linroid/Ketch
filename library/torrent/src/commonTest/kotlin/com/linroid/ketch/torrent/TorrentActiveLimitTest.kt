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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okio.FileSystem
import okio.Path
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * Ketch may start more torrents than [TorrentConfig.maxActiveTorrents] (for example with
 * unlimited simultaneous downloads); the extra tasks wait for an engine slot instead of failing.
 */
class TorrentActiveLimitTest {
  private class Swarm(val root: Path, val seeder: KotlinTorrentEngine) {
    val payloads = mutableListOf<Pair<ByteArray, ByteArray>>()

    suspend fun seed(name: String): Int {
      val bytes = ByteArray(128 * 1024) { (it * 31 + name.hashCode()).toByte() }
      val hashes = bytes.asList().chunked(16_384).fold(ByteArray(0)) { value, chunk ->
        value + sha1Digest(chunk.toByteArray())
      }
      val data = Bencode.encode(mapOf("info" to mapOf("name" to name,
        "length" to bytes.size.toLong(), "piece length" to 16_384L, "pieces" to hashes)))
      torrentFileSystem.write(root / "seed-$name") { write(bytes) }
      val session = seeder.addTask(TorrentTaskSpec("seed-$name", TorrentMetadata.fromBencode(data),
        (root / "seed-$name").toString(), emptySet()))
      session.resume()
      assertEquals(TorrentSessionState.SEEDING, session.state.first {
        it == TorrentSessionState.SEEDING || it == TorrentSessionState.STOPPED
      })
      payloads += bytes to data
      return payloads.lastIndex
    }

    fun request(
      source: TorrentDownloadSource,
      index: Int,
      limit: SpeedLimit = SpeedLimit.Unlimited,
    ): DownloadRequest {
      val data = payloads[index].second
      val metadata = TorrentMetadata.fromBencode(data)
      val magnet = MagnetUri(metadata.infoHash,
        explicitPeers = listOf("127.0.0.1:${seeder.listenPort}")).toUri()
      return DownloadRequest(magnet, destination = Destination((root / "out-$index").toString()),
        resolvedSource = source.resolveMetainfo(data), speedLimit = limit)
    }

    fun assertDownloaded(index: Int) {
      assertContentEquals(payloads[index].first,
        torrentFileSystem.read(root / "out-$index") { readByteArray() })
    }
  }

  private fun withSwarm(
    block: suspend CoroutineScope.(Swarm, Ketch, TorrentDownloadSource) -> Unit,
  ) = runTest {
    withContext(Dispatchers.Default) {
      withTimeout(20_000) {
        val root = FileSystem.SYSTEM_TEMPORARY_DIRECTORY /
          "ketch-active-${InfoHash.fromBytes(torrentRandomBytes(20)).hex}"
        torrentFileSystem.createDirectories(root)
        val seeder = KotlinTorrentEngine(TorrentConfig(dhtEnabled = false,
          uploadPolicy = TorrentUploadPolicy.SEED_AFTER_COMPLETION))
        val source = TorrentDownloadSource(TorrentConfig(dhtEnabled = false,
          maxActiveTorrents = 1))
        // Unlimited simultaneous downloads: Ketch admits every torrent at once.
        val ketch = Ketch(UnusedHttp, config = DownloadConfig(maxConcurrentDownloads = 0),
          additionalSources = listOf(source))
        try {
          seeder.start()
          ketch.start()
          block(Swarm(root, seeder), ketch, source)
        } finally {
          ketch.close()
          seeder.stop()
          torrentFileSystem.deleteRecursively(root, mustExist = false)
        }
      }
    }
  }

  /** Waits until the task holds the only engine slot and has verified some payload. */
  private suspend fun awaitActive(task: DownloadTask) {
    task.segments.first { segments -> segments.sumOf { it.downloadedBytes } > 0 }
  }

  /** A task waiting for an engine slot reports as downloading at 0 bytes/s. */
  private suspend fun awaitWaiting(task: DownloadTask) {
    task.state.first { it is DownloadState.Downloading }
  }

  @Test
  fun torrentBeyondActiveLimit_waitsForSlotThenCompletes() = withSwarm { swarm, ketch, source ->
    val first = swarm.seed("first")
    val second = swarm.seed("second")
    // One byte per second after the limiter's 64 KiB burst keeps the first torrent active.
    val active = ketch.download(swarm.request(source, first, SpeedLimit.of(1)))
    awaitActive(active)
    val waiting = ketch.download(swarm.request(source, second))
    awaitWaiting(waiting)
    delay(500)
    // Previously this failed with "Too many active torrents"; now it waits, transferring nothing.
    val progress = assertIs<DownloadState.Downloading>(waiting.state.value).progress
    assertEquals(0L, progress.downloadedBytes)
    assertEquals(0L, progress.bytesPerSecond)
    assertEquals(0L, waiting.segments.value.sumOf { it.downloadedBytes })

    // Out-of-range peer caps are clamped for torrents instead of failing the task.
    active.setConnections(100_000)
    active.setSpeedLimit(SpeedLimit.Unlimited)
    active.await().getOrThrow()
    waiting.await().getOrThrow()
    swarm.assertDownloaded(first)
    swarm.assertDownloaded(second)
  }

  @Test
  fun pauseAndCancelWhileWaiting_returnPromptlyAndDoNotLeakTheSlot() =
    withSwarm { swarm, ketch, source ->
      val first = swarm.seed("first")
      val second = swarm.seed("second")
      val third = swarm.seed("third")
      val active = ketch.download(swarm.request(source, first, SpeedLimit.of(1)))
      awaitActive(active)
      val canceled = ketch.download(swarm.request(source, second))
      val paused = ketch.download(swarm.request(source, third))
      awaitWaiting(canceled)
      awaitWaiting(paused)

      withTimeout(2000) { canceled.cancel() }
      assertIs<DownloadState.Canceled>(canceled.state.value)
      withTimeout(2000) { paused.pause() }
      assertIs<DownloadState.Paused>(paused.state.value)

      active.setSpeedLimit(SpeedLimit.Unlimited)
      active.await().getOrThrow()
      paused.resume()
      paused.await().getOrThrow()
      swarm.assertDownloaded(first)
      swarm.assertDownloaded(third)
    }

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
}
