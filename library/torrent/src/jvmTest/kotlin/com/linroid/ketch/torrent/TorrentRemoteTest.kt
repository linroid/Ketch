package com.linroid.ketch.torrent

import com.linroid.ketch.api.Destination
import com.linroid.ketch.api.DownloadRequest
import com.linroid.ketch.api.DownloadState
import com.linroid.ketch.api.SpeedLimit
import com.linroid.ketch.core.Ketch
import com.linroid.ketch.engine.KtorHttpEngine
import com.linroid.ketch.remote.RemoteKetch
import com.linroid.ketch.server.KetchServer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.net.ServerSocket
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TorrentRemoteTest {
  @Test
  fun remoteResolveSelectionProgressPauseAndResume_useRealTorrentRuntime() = runTest {
    withContext(Dispatchers.Default) {
      withTimeout(30_000) {
        val root = Files.createTempDirectory("ketch-remote-torrent").toFile()
        val bytes = ByteArray(256 * 1024) { (it * 11).toByte() }
        val hashes = bytes.asList().chunked(16_384).fold(ByteArray(0)) { value, chunk ->
          value + sha1Digest(chunk.toByteArray())
        }
        val metadata = TorrentMetadata.fromBencode(Bencode.encode(mapOf("info" to mapOf(
          "name" to "bundle", "piece length" to 16_384L, "pieces" to hashes,
          "files" to listOf(mapOf("length" to 131_072L, "path" to listOf("skip")),
            mapOf("length" to 131_072L, "path" to listOf("keep")))
        ))))
        val seedDir = root.resolve("seed").apply { mkdirs() }
        seedDir.resolve("skip").writeBytes(bytes.copyOfRange(0, 131_072))
        seedDir.resolve("keep").writeBytes(bytes.copyOfRange(131_072, bytes.size))
        val seed = KotlinTorrentEngine(TorrentConfig(dhtEnabled = false,
          uploadPolicy = TorrentUploadPolicy.SEED_AFTER_COMPLETION))
        val ketch = Ketch(KtorHttpEngine(), additionalSources = listOf(
          TorrentDownloadSource(TorrentConfig(dhtEnabled = false))))
        val port = ServerSocket(0).use { it.localPort }
        val server = KetchServer(ketch, host = "127.0.0.1", port = port, mdnsEnabled = false)
        val remote = RemoteKetch("127.0.0.1", port)
        try {
          seed.start()
          val seeding = seed.addTask(TorrentTaskSpec("seed", metadata,
            seedDir.absolutePath, emptySet()))
          seeding.resume()
          seeding.state.first { it == TorrentSessionState.SEEDING }
          ketch.start()
          server.start(wait = false)
          remote.start()
          val magnet = MagnetUri(metadata.infoHash,
            explicitPeers = listOf("127.0.0.1:${seed.listenPort}")).toUri()
          val resolved = remote.resolve(magnet)
          assertEquals(listOf("0", "1"), resolved.files.map { it.id })
          val output = root.resolve("output")
          val task = remote.download(DownloadRequest(magnet,
            destination = Destination(output.absolutePath), selectedFileIds = setOf("1"),
            resolvedSource = resolved, speedLimit = SpeedLimit.of(16_384)))
          while (task.segments.value.sumOf { it.downloadedBytes } == 0L) delay(25)
          task.pause()
          task.state.first { it is DownloadState.Paused }
          assertTrue(task.segments.value.sumOf { it.downloadedBytes } < 131_072)
          task.setSpeedLimit(SpeedLimit.Unlimited)
          task.resume()
          val result = task.await()
          if (result.isFailure) {
            val failed = ketch.tasks.value.first().state.value as? DownloadState.Failed
            throw AssertionError("Local torrent failed: ${failed?.error}", failed?.error)
          }
          assertEquals(131_072L, task.segments.value.sumOf { it.downloadedBytes })
          assertFalse(output.resolve("skip").exists())
          assertContentEquals(bytes.copyOfRange(131_072, bytes.size),
            output.resolve("keep").readBytes())
        } finally {
          remote.close()
          server.stop()
          ketch.close()
          seed.stop()
          root.deleteRecursively()
        }
      }
    }
  }
}
