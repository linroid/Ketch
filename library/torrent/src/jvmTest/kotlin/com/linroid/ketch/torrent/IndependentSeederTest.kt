package com.linroid.ketch.torrent

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okio.Path.Companion.toPath
import org.libtorrent4j.SessionManager
import org.libtorrent4j.SessionParams
import org.libtorrent4j.SettingsPack
import org.libtorrent4j.TorrentInfo
import org.libtorrent4j.swig.settings_pack
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** libtorrent is used only as an independent local reference seeder for this test. */
class IndependentSeederTest {
  @Test
  fun kotlinDownloader_completesVerifiedTransferFromIndependentSeeder() = runTest {
    withContext(Dispatchers.Default) {
      withTimeout(20_000) {
        NativeLibraryLoader.ensureLoaded()
        val root = Files.createTempDirectory("ketch-interop").toFile()
        val seed = root.resolve("seed").apply { mkdirs() }
        val payload = ByteArray(80_037) { (it * 31 + 17).toByte() }
        seed.resolve("fixture.bin").writeBytes(payload)
        val hashes = payload.asList().chunked(16_384)
          .fold(ByteArray(0)) { bytes, chunk -> bytes + sha1Digest(chunk.toByteArray()) }
        val data = Bencode.encode(mapOf("info" to mapOf(
          "name" to "fixture.bin", "length" to payload.size.toLong(),
          "piece length" to 16_384L, "pieces" to hashes
        )))
        val settings = SettingsPack()
        settings.setString(settings_pack.string_types.listen_interfaces.swigValue(), "127.0.0.1:0")
        for (flag in listOf(settings_pack.bool_types.enable_dht, settings_pack.bool_types.enable_lsd,
          settings_pack.bool_types.enable_upnp, settings_pack.bool_types.enable_natpmp)) {
          settings.setBoolean(flag.swigValue(), false)
        }
        val manager = SessionManager()
        val network = createTorrentNetwork()
        try {
          manager.start(SessionParams(settings))
          val info = TorrentInfo(data)
          manager.download(info, seed)
          while (manager.find(info.infoHash())?.status()?.isSeeding() != true ||
            manager.swig().listen_port() == 0) {
            delay(20)
          }
          val output = root.resolve("download/fixture.bin")
          val metadata = TorrentMetadata.fromBencode(data)
          val store = TorrentPieceStore(metadata, output.absolutePath.toPath(), emptySet(), "interop")
          var received = 0L
          val progress = mutableListOf<Long>()
          TorrentPeerDownloader(store, network, consumePayload = { received += it },
            onProgress = { progress.add(it) }).download(
            PeerEndpoint("127.0.0.1", manager.swig().listen_port())
          )
          assertContentEquals(payload, output.readBytes())
          assertEquals(payload.size.toLong(), received)
          assertEquals(payload.size.toLong(), progress.last())
          assertTrue(store.completed())
        } finally {
          network.close()
          manager.stop()
          root.deleteRecursively()
        }
      }
    }
  }
}
