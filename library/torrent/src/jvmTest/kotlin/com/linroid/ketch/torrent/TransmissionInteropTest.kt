package com.linroid.ketch.torrent

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okio.Path.Companion.toPath
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** An isolated Transmission process is a test fixture, never a production downloader dependency. */
class TransmissionInteropTest {
  @Test
  fun publicSource_resolvesTrackerlessMagnetAndDownloadsFromTransmission() = runTest {
    withContext(Dispatchers.IO) {
      withTimeout(60_000) {
        val binary = TransmissionDaemon.binary()
        val root = Files.createTempDirectory("ketch-transmission").toFile()
        val seed = root.resolve("seed").apply { mkdirs() }
        val payload = ByteArray(512 * 1024 + 37) { (it * 31 + 17).toByte() }
        seed.resolve("fixture.bin").writeBytes(payload)
        val data = Bencode.encode(mapOf("info" to mapOf(
          "name" to "fixture.bin", "length" to payload.size.toLong(), "piece length" to 16_384L,
          "pieces" to payload.asList().chunked(16_384).fold(ByteArray(0)) { hashes, chunk ->
            hashes + sha1Digest(chunk.toByteArray())
          }
        )))
        val metadata = TorrentMetadata.fromBencode(data)
        val daemon = TransmissionDaemon.start(binary, root, seed)
        val source = TorrentDownloadSource(TorrentConfig(dhtEnabled = false))
        try {
          daemon.awaitReady()
          daemon.add(data, seed)
          daemon.awaitComplete()
          val magnet = MagnetUri(metadata.infoHash,
            explicitPeers = listOf("127.0.0.1:${daemon.peerPort}")).toUri()
          val resolved = source.resolve(magnet, emptyMap())
          val output = root.resolve("result.bin")
          val context = sourceContext(magnet, resolved, output.absolutePath)
          source.download(context)
          assertContentEquals(payload, output.readBytes())
          assertEquals(payload.size.toLong(), context.segments.value.sumOf { it.downloadedBytes })
          for (variable in listOf("KETCH_NATIVE_CLI", "KETCH_JVM_CLI")) {
            val cli = System.getenv(variable)?.takeIf { it.isNotBlank() } ?: continue
            val cliOutput = root.resolve(variable)
            val log = root.resolve("$variable.log")
            val download = ProcessBuilder(cli, magnet, cliOutput.absolutePath)
              .redirectErrorStream(true).redirectOutput(log).start()
            val exited = download.waitFor(30, TimeUnit.SECONDS)
            if (!exited) download.destroyForcibly().waitFor()
            assertTrue(exited, log.readText())
            assertEquals(0, download.exitValue(), log.readText())
            assertTrue(cliOutput.exists(), log.readText())
            assertContentEquals(payload, cliOutput.readBytes())
          }
        } finally {
          source.close()
          daemon.close()
          root.deleteRecursively()
        }
      }
    }
  }

  @Test
  fun publicSource_expandsSelectionWhileDownloadingFromTransmission() = runTest {
    withContext(Dispatchers.IO) {
      withTimeout(90_000) {
        val binary = TransmissionDaemon.binary()
        val root = Files.createTempDirectory("ketch-transmission-selection").toFile()
        val seed = root.resolve("seed").apply { mkdirs() }
        // Three files whose boundaries fall inside pieces; Transmission seeds them from seed/pack.
        val torrent = MultiFileTorrent()
        torrent.writeTo(seed.absolutePath.toPath() / "pack")
        val daemon = TransmissionDaemon.start(binary, root, seed)
        try {
          daemon.awaitReady()
          daemon.add(torrent.metainfo, seed)
          daemon.awaitComplete()
          val magnet = MagnetUri(torrent.metadata.infoHash,
            explicitPeers = listOf("127.0.0.1:${daemon.peerPort}")).toUri()
          val output = root.resolve("out")
          val expansion = downloadExpandingLive(magnet, torrent.metainfo, output.absolutePath,
            first = setOf("0"), expanded = setOf("0", "2"))
          assertContentEquals(torrent.payloads[0], output.resolve("f0").readBytes())
          assertContentEquals(torrent.payloads[2], output.resolve("f2").readBytes())
          assertFalse(output.resolve("f1").exists())
          // The expansion reached the running swarm: no peer was dialed again.
          assertEquals(1, expansion.connects)
        } finally {
          daemon.close()
          root.deleteRecursively()
        }
      }
    }
  }
}
