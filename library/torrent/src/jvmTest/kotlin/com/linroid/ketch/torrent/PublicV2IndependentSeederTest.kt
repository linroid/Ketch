package com.linroid.ketch.torrent

import com.linroid.ketch.api.Destination
import com.linroid.ketch.api.DownloadRequest
import com.linroid.ketch.core.Ketch
import com.linroid.ketch.core.engine.resolve
import com.linroid.ketch.engine.KtorHttpEngine
import com.linroid.ketch.torrent.crypto.sha1Digest
import com.linroid.ketch.torrent.crypto.sha256Digest
import com.linroid.ketch.torrent.magnet.MagnetUri
import com.linroid.ketch.torrent.metainfo.Bencode
import com.linroid.ketch.torrent.metainfo.TorrentV2Document
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okio.ByteString.Companion.toByteString
import org.libtorrent4j.SessionManager
import org.libtorrent4j.TorrentInfo
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

/** Independent peer exercises public magnets, hash exchange, and payload transfer. */
class PublicV2IndependentSeederTest {
  @Test fun pureV2MagnetDownloadsFromLibtorrent() = runTest { download(false) }
  @Test fun hybridMagnetDownloadsFromLibtorrent() = runTest { download(true) }

  @Test fun liveSelectionExpand_pureV2FromIndependentSeeder() = runTest {
    withContext(Dispatchers.Default) {
      withTimeout(60_000) {
        NativeLibraryLoader.ensureLoaded()
        val root = Files.createTempDirectory("ketch-public-v2-selection").toFile()
        val seed = root.resolve("seed").apply { mkdirs() }
        // Two files of several 16 KiB pieces; libtorrent seeds them from seed/pack.
        val fixture = TorrentV2Fixture.build(listOf("a" to 100_000, "b" to 70_000),
          pieceLength = 16_384)
        seed.resolve("pack").mkdirs()
        fixture.names.forEachIndexed { index, name ->
          seed.resolve("pack/$name").writeBytes(fixture.payloads[index])
        }
        val manager = SessionManager()
        try {
          val port = manager.startSeeding(TorrentInfo(fixture.metainfo), seed)
          val magnet = MagnetUri(fixture.document.identity,
            explicitPeers = listOf("127.0.0.1:$port")).toUri()
          val output = root.resolve("out")
          val expansion = downloadExpandingLive(magnet, fixture.metainfo, output.absolutePath,
            first = setOf("0"), expanded = setOf("0", "1"))
          assertContentEquals(fixture.payloads[0], output.resolve("a").readBytes())
          assertContentEquals(fixture.payloads[1], output.resolve("b").readBytes())
          // The running owner took the expansion: no peer was dialed again for it.
          assertEquals(expansion.connectsAtChange, expansion.connects)
        } finally {
          manager.stop()
          root.deleteRecursively()
        }
      }
    }
  }

  private suspend fun download(hybrid: Boolean) = withContext(Dispatchers.Default) {
    withTimeout(30_000) {
      NativeLibraryLoader.ensureLoaded()
      val root = Files.createTempDirectory("ketch-public-v2-interop").toFile()
      val seed = root.resolve("seed").apply { mkdirs() }
      val payload = ByteArray(49_155) { (it * 31 + 17).toByte() }
      seed.resolve("fixture.bin").writeBytes(payload)
      val blocks = payload.asList().chunked(16_384).map { it.toByteArray() }
      val hashes = blocks.map(::sha256Digest)
      val merkle = sha256Digest(sha256Digest(hashes[0] + hashes[1]) +
        sha256Digest(hashes[2] + hashes[3]))
      val info = mutableMapOf<String, Any>("name" to "fixture.bin", "meta version" to 2L,
        "piece length" to 16_384L, "file tree" to mapOf("fixture.bin" to mapOf("" to
          mapOf("length" to payload.size.toLong(), "pieces root" to merkle))))
      if (hybrid) {
        info["length"] = payload.size.toLong()
        info["pieces"] = blocks.fold(ByteArray(0)) { a, b -> a + sha1Digest(b) }
      }
      val data = Bencode.encode(mapOf("info" to info, "piece layers" to mapOf(
        merkle.toByteString() to hashes.fold(ByteArray(0)) { a, b -> a + b })))
      val expected = TorrentV2Document.parse(data)
      val manager = SessionManager()
      val source = TorrentDownloadSource(TorrentConfig(dhtEnabled = false))
      val ketch = Ketch(KtorHttpEngine(), additionalSources = listOf(source))
      try {
        val torrent = TorrentInfo(data)
        for (index in 0 until torrent.files().numFiles()) {
          val path = torrent.files().filePath(index)
          println("Independent v2 seed file: $path exists=${seed.resolve(path).exists()}")
        }
        val port = manager.startSeeding(torrent, seed)
        val magnet = MagnetUri(expected.identity,
          explicitPeers = listOf("127.0.0.1:$port")).toUri()
        val resolved = source.resolve(magnet, emptyMap())
        assertEquals(expected.info.hash.hex, resolved.metadata["infoHash"])
        ketch.start()
        val task = ketch.download(DownloadRequest(magnet,
          destination = Destination(root.resolve("out").absolutePath), resolvedSource = resolved))
        task.await().getOrThrow()
        assertContentEquals(payload, root.resolve("out/fixture.bin").readBytes())
      } finally {
        ketch.close()
        manager.stop()
        root.deleteRecursively()
      }
    }
  }
}
