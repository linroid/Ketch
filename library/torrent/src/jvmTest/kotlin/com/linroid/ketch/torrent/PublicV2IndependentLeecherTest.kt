package com.linroid.ketch.torrent

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okio.Path.Companion.toOkioPath
import org.libtorrent4j.LibTorrent
import org.libtorrent4j.SessionManager
import org.libtorrent4j.SessionParams
import org.libtorrent4j.SettingsPack
import org.libtorrent4j.Sha1Hash
import org.libtorrent4j.swig.settings_pack
import org.libtorrent4j.swig.torrent_flags_t
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * libtorrent, an independent implementation, downloads v2 and hybrid torrents from a Ketch
 * engine seeding them: it fetches the info dictionary over `ut_metadata`, the piece layers over
 * BEP 52 hash requests (one file's layer is wider than a 512-hash group, so the proofs carry
 * uncles from the cached upper tree) and every block, and checks them all.
 */
class PublicV2IndependentLeecherTest {
  // a.bin's piece layer has 521 hashes, past one 512-hash request; b.bin and c.bin end a piece.
  private val files = listOf("a.bin" to 520 * 32_768 + 77, "b.bin" to 40_000, "c.bin" to 5)

  @Test
  fun libtorrentDownloadsPureV2FromKetchSeeder() = runTest {
    upload(TorrentV2Fixture.build(files, hybrid = false)) { it.identity }
  }

  @Test
  fun libtorrentDownloadsHybridFromKetchSeeder() = runTest {
    upload(TorrentV2Fixture.build(files, hybrid = true)) { it.identity }
  }

  @Test
  fun libtorrentJoinsHybridThroughTheV1Topic() = runTest {
    val session = upload(TorrentV2Fixture.build(files, hybrid = true)) {
      TorrentIdentity(v1 = it.identity.v1)
    }
    // A btih-only magnet reaches the owner on its v1 tag, without the upgrade bit.
    assertTrue(session.v1UploadedBytes() > 0, "Nothing went out over the v1 route")
  }

  /**
   * Seeds [fixture] from a Ketch engine, has libtorrent download it from a magnet of the topics
   * [topics] picks with Ketch as its only peer, and returns the seeding owner once removed.
   */
  private suspend fun upload(
    fixture: TorrentV2Fixture,
    topics: (TorrentV2Document) -> TorrentIdentity,
  ): TorrentV2DownloadSession = withContext(Dispatchers.Default) {
    withTimeout(60_000) {
      NativeLibraryLoader.ensureLoaded()
      assertEquals(ConformanceClients.version("libtorrent4j"), LibTorrent.libtorrent4jVersion())
      println("CONFORMANCE_CLIENT libtorrent ${LibTorrent.version()} " +
        "libtorrent4j ${LibTorrent.libtorrent4jVersion()}")
      val root = Files.createTempDirectory("ketch-public-v2-leecher").toFile()
      val leech = root.resolve("leech").apply { mkdirs() }
      val engine = KotlinTorrentEngine(TorrentConfig(dhtEnabled = false,
        uploadPolicy = TorrentUploadPolicy.SEED_AFTER_COMPLETION), listenHost = "127.0.0.1")
      val manager = SessionManager()
      try {
        engine.start()
        val output = root.resolve("seed").toOkioPath()
        val checkpoint = fixture.preseed(output, "seed")
        val session = engine.addV2Task(TorrentV2TaskSpec("seed", fixture.document,
          output.toString(), checkpoint = checkpoint, privacy = TorrentDiscoveryPrivacy.PUBLIC))
        session.resume()
        assertEquals(TorrentSessionState.SEEDING, session.state.first {
          it == TorrentSessionState.SEEDING || it == TorrentSessionState.FINISHED ||
            it == TorrentSessionState.STOPPED
        }, session.failure.value?.stackTraceToString())

        // The memory-mapped back end guards its writes with SIGSEGV and SIGBUS handlers that take
        // the JVM's implicit null checks down with them; plain file I/O installs none.
        manager.start(SessionParams(leecherSettings()).apply { setPosixDiskIO() })
        // libtorrent reads x.pe without unescaping it, so the peer goes in as written.
        val identity = topics(fixture.document)
        val magnet = MagnetUri(identity).toUri() + "&x.pe=127.0.0.1:${engine.listenPort}"
        manager.download(magnet, leech, torrent_flags_t())
        // libtorrent files a torrent under its best topic: the truncated v2 hash when it has one.
        awaitSeeding(manager, identity.v2?.hex?.take(40) ?: checkNotNull(identity.v1).hex)
        for ((index, name) in fixture.names.withIndex()) {
          assertContentEquals(fixture.payloads[index],
            leech.resolve("pack/$name").readBytes(), name)
        }
        val payload = fixture.payloads.sumOf { it.size.toLong() }
        assertTrue(session.uploadedBytes() >= payload,
          "Uploaded ${session.uploadedBytes()} of $payload bytes")
        engine.removeTorrent(fixture.document.info.hash.hex)
        assertEquals(TorrentSessionState.STOPPED, session.state.value)
        assertEquals(0, engine.admittedSessionBytes)
        session
      } finally {
        manager.stop()
        engine.stop()
        assertEquals(0, engine.admittedSessionBytes)
        assertEquals(0, engine.allocatedExchangeBytes)
        root.deleteRecursively()
      }
    }
  }

  /** Waits until libtorrent's torrent [tag] has every piece, failing on its first error. */
  private suspend fun awaitSeeding(manager: SessionManager, tag: String) {
    var last: String? = null
    while (true) {
      val status = manager.find(Sha1Hash.parseHex(tag))?.status()
      val state = "state=${status?.state()} progress=${status?.progress()} " +
        "error=${status?.errorCode()?.message} peers=${status?.numPeers()}"
      if (state != last) {
        println("Independent v2 leecher: $state")
        last = state
      }
      check(status == null || !status.errorCode().isError) { "Independent leecher failed: $state" }
      if (status?.isSeeding == true) return
      delay(20)
    }
  }

  /**
   * Loopback only, no DHT, local discovery or port mapping; plaintext TCP, since Ketch speaks
   * neither protocol encryption nor uTP yet.
   */
  private fun leecherSettings(): SettingsPack {
    val settings = SettingsPack()
    settings.setString(settings_pack.string_types.listen_interfaces.swigValue(), "127.0.0.1:0")
    for (flag in listOf(settings_pack.bool_types.enable_dht, settings_pack.bool_types.enable_lsd,
      settings_pack.bool_types.enable_upnp, settings_pack.bool_types.enable_natpmp,
      settings_pack.bool_types.enable_outgoing_utp, settings_pack.bool_types.enable_incoming_utp)) {
      settings.setBoolean(flag.swigValue(), false)
    }
    settings.setInteger(settings_pack.int_types.out_enc_policy.swigValue(),
      settings_pack.enc_policy.pe_disabled.swigValue())
    return settings
  }
}

private fun File.toOkioPath() = toPath().toOkioPath()
