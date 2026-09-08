package com.linroid.ketch.torrent

import com.sun.management.UnixOperatingSystemMXBean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.lang.management.ManagementFactory
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertTrue

class KotlinTorrentResourceTest {
  @Test
  fun repeatedTransfersAndSourceClose_doNotGrowOpenDescriptors() = runTest {
    val os = ManagementFactory.getOperatingSystemMXBean() as? UnixOperatingSystemMXBean
      ?: return@runTest
    withContext(Dispatchers.Default) {
      withTimeout(30_000) {
        val root = Files.createTempDirectory("ketch-resource").toFile()
        val bytes = ByteArray(64 * 1024) { it.toByte() }
        val data = Bencode.encode(mapOf("info" to mapOf("name" to "payload",
          "length" to bytes.size.toLong(), "piece length" to bytes.size.toLong(),
          "pieces" to sha1Digest(bytes))))
        val metadata = TorrentMetadata.fromBencode(data)
        val seedFile = root.resolve("seed").apply { writeBytes(bytes) }
        val seeder = KotlinTorrentEngine(TorrentConfig(dhtEnabled = false,
          uploadPolicy = TorrentUploadPolicy.SEED_AFTER_COMPLETION))
        val counts = mutableListOf<Long>()
        try {
          seeder.start()
          val seed = seeder.addTask(TorrentTaskSpec("resource-seed", metadata,
            seedFile.absolutePath, emptySet()))
          seed.resume()
          seed.state.first { it == TorrentSessionState.SEEDING }
          val magnet = MagnetUri(metadata.infoHash,
            explicitPeers = listOf("127.0.0.1:${seeder.listenPort}")).toUri()
          repeat(12) { index ->
            val source = TorrentDownloadSource(TorrentConfig(dhtEnabled = false))
            try {
              val output = root.resolve("result-$index")
              source.download(sourceContext(magnet, source.resolveMetainfo(data),
                output.absolutePath, "resource-$index"))
              assertTrue(output.readBytes().contentEquals(bytes))
            } finally { source.close() }
            delay(50) // Allow canceled selector/accept jobs to finish closing their descriptors.
            counts += os.openFileDescriptorCount
          }
          assertTrue(counts.drop(3).max() <= counts.take(3).max() + 2, "Descriptor counts: $counts")
          println("RESOURCE_CHURN open_fds=$counts")
        } finally { seeder.stop(); root.deleteRecursively() }
      }
    }
  }
}
