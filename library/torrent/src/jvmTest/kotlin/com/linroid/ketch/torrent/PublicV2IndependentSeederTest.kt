package com.linroid.ketch.torrent

import com.linroid.ketch.api.Destination
import com.linroid.ketch.api.DownloadRequest
import com.linroid.ketch.core.Ketch
import com.linroid.ketch.engine.KtorHttpEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okio.ByteString.Companion.toByteString
import org.libtorrent4j.SessionManager
import org.libtorrent4j.SessionParams
import org.libtorrent4j.SettingsPack
import org.libtorrent4j.TorrentInfo
import org.libtorrent4j.swig.settings_pack
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

/** Independent peer exercises public magnets, hash exchange, and payload transfer. */
class PublicV2IndependentSeederTest {
  @Test fun pureV2MagnetDownloadsFromLibtorrent() = runTest { download(false) }
  @Test fun hybridMagnetDownloadsFromLibtorrent() = runTest { download(true) }

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
      val settings = SettingsPack()
      settings.setString(settings_pack.string_types.listen_interfaces.swigValue(), "127.0.0.1:0")
      for (flag in listOf(settings_pack.bool_types.enable_dht, settings_pack.bool_types.enable_lsd,
        settings_pack.bool_types.enable_upnp, settings_pack.bool_types.enable_natpmp)) {
        settings.setBoolean(flag.swigValue(), false)
      }
      val manager = SessionManager()
      val source = TorrentDownloadSource(TorrentConfig(dhtEnabled = false,
        metadataTimeoutSeconds = 10))
      val ketch = Ketch(KtorHttpEngine(), additionalSources = listOf(source))
      try {
        manager.start(SessionParams(settings))
        val torrent = TorrentInfo(data)
        manager.download(torrent, seed)
        while (manager.find(torrent.infoHash())?.status()?.isSeeding() != true ||
          manager.swig().listen_port() == 0) delay(20)
        val magnet = MagnetUri(expected.identity,
          explicitPeers = listOf("127.0.0.1:${manager.swig().listen_port()}")).toUri()
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
