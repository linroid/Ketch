package com.linroid.ketch.torrent

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okio.Buffer
import okio.FileSystem
import platform.posix.RUSAGE_SELF
import platform.posix.getrusage
import platform.posix.rusage
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.time.TimeSource

/** Records native socket polling CPU cost without a timing pass threshold. */
@OptIn(ExperimentalForeignApi::class)
class TorrentPollingCostTest {
  @Test
  fun loopbackTransferAndIdleListener_recordWallTimeAndCpu() = runTest {
    withContext(Dispatchers.Default) {
      withTimeout(60_000) {
        val bytes = ByteArray(8 * 1024 * 1024 + 37) { it.toByte() }
        val pieces = Buffer()
        val pieceLength = 256 * 1024
        for (offset in bytes.indices step pieceLength) {
          val end = minOf(offset + pieceLength, bytes.size)
          pieces.write(sha1Digest(bytes.copyOfRange(offset, end)))
        }
        val metadata = TorrentMetadata.fromBencode(Bencode.encode(mapOf("info" to mapOf(
          "name" to "payload", "length" to bytes.size.toLong(),
          "piece length" to pieceLength.toLong(), "pieces" to pieces.readByteArray()
        ))))
        val root = FileSystem.SYSTEM_TEMPORARY_DIRECTORY /
          "ketch-polling-${InfoHash.fromBytes(torrentRandomBytes(20)).hex}"
        FileSystem.SYSTEM.createDirectories(root)
        FileSystem.SYSTEM.write(root / "seed") { write(bytes) }
        val seed = KotlinTorrentEngine(TorrentConfig(dhtEnabled = false,
          uploadPolicy = TorrentUploadPolicy.SEED_AFTER_COMPLETION))
        val client = KotlinTorrentEngine(TorrentConfig(dhtEnabled = false))
        try {
          seed.start()
          val seeding = seed.addTask(TorrentTaskSpec("seed", metadata,
            (root / "seed").toString(), emptySet()))
          seeding.resume()
          seeding.state.first { it == TorrentSessionState.SEEDING }
          val idleStart = cpuMicros()
          delay(1_000)
          val idleCpu = cpuMicros() - idleStart
          val cpuStart = cpuMicros()
          val start = TimeSource.Monotonic.markNow()
          client.start()
          val magnet = MagnetUri(metadata.infoHash,
            explicitPeers = listOf("127.0.0.1:${seed.listenPort}")).toUri()
          val downloading = client.addTask(TorrentTaskSpec("download", metadata,
            (root / "output").toString(), emptySet(), magnet))
          downloading.resume()
          val state = downloading.state.first {
            it == TorrentSessionState.FINISHED || it == TorrentSessionState.STOPPED
          }
          assertEquals(TorrentSessionState.FINISHED, state)
          val elapsedMs = start.elapsedNow().inWholeMilliseconds
          val cpu = cpuMicros() - cpuStart
          assertContentEquals(bytes, FileSystem.SYSTEM.read(root / "output") { readByteArray() })
          println("IOS_POLLING bytes=${bytes.size} elapsed_ms=$elapsedMs " +
            "transfer_cpu_us=$cpu idle_1s_cpu_us=$idleCpu")
        } finally {
          client.stop()
          seed.stop()
          FileSystem.SYSTEM.deleteRecursively(root, mustExist = false)
        }
      }
    }
  }

  private fun cpuMicros(): Long = memScoped {
    val usage = alloc<rusage>()
    check(getrusage(RUSAGE_SELF, usage.ptr) == 0)
    usage.ru_utime.tv_sec * 1_000_000L + usage.ru_utime.tv_usec +
      usage.ru_stime.tv_sec * 1_000_000L + usage.ru_stime.tv_usec
  }
}
