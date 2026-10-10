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
        val settings = SettingsPack()
        settings.setString(settings_pack.string_types.listen_interfaces.swigValue(),
          "127.0.0.1:0")
        for (flag in listOf(settings_pack.bool_types.enable_dht,
          settings_pack.bool_types.enable_lsd, settings_pack.bool_types.enable_upnp,
          settings_pack.bool_types.enable_natpmp)) {
          settings.setBoolean(flag.swigValue(), false)
        }
        val manager = SessionManager()
        try {
          manager.start(SessionParams(settings))
          val torrent = TorrentInfo(fixture.metainfo)
          manager.download(torrent, seed)
          while (true) {
            val status = manager.find(torrent.infoHash())?.status()
            check(status == null || !status.errorCode().isError) {
              "Independent v2 seeder failed: ${status?.errorCode()?.message}"
            }
            if (status?.isSeeding() == true && manager.swig().listen_port() != 0) break
            delay(20)
          }
          val magnet = MagnetUri(fixture.document.identity,
            explicitPeers = listOf("127.0.0.1:${manager.swig().listen_port()}")).toUri()
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
        for (index in 0 until torrent.files().numFiles()) {
          val path = torrent.files().filePath(index)
          println("Independent v2 seed file: $path exists=${seed.resolve(path).exists()}")
        }
        manager.download(torrent, seed)
        var lastState: String? = null
        while (true) {
          val status = manager.find(torrent.infoHash())?.status()
          val state = "state=${status?.state()} progress=${status?.progress()} " +
            "error=${status?.errorCode()?.message} port=${manager.swig().listen_port()}"
          if (state != lastState) {
            println("Independent v2 seeder: $state")
            lastState = state
          }
          check(status == null || !status.errorCode().isError) {
            "Independent v2 seeder failed: $state"
          }
          if (status?.isSeeding() == true && manager.swig().listen_port() != 0) break
          delay(20)
        }
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
