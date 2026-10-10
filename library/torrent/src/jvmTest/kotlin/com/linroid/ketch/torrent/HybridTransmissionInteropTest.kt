package com.linroid.ketch.torrent

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okio.Path.Companion.toOkioPath
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Transmission, which knows no BitTorrent v2, as a v1-only peer of a hybrid torrent's v1 swarm:
 * it loads the hybrid metainfo as v1 with BEP 47 padding, and a Ketch owner of the same torrent
 * serves it canonical v1 blocks with zeros for the padding, then downloads from it, stripping
 * the padding, while still checking every piece against both hashes.
 */
class HybridTransmissionInteropTest {
  // Pieces of 32 KiB: a.bin and b.bin end inside a piece, so padding follows each.
  private val fixture = TorrentV2Fixture.build(
    listOf("a.bin" to 100_000, "b.bin" to 40_000, "c.bin" to 5), hybrid = true)

  @Test
  fun transmissionDownloadsHybridFromKetchSeeder() = interop { binary, root ->
    val leech = root.resolve("leech").apply { mkdirs() }
    TransmissionDaemon.start(binary, root, leech).use { daemon ->
      daemon.awaitReady()
      daemon.add(fixture.metainfo, leech)
      engine(TorrentUploadPolicy.SEED_AFTER_COMPLETION) { engine ->
        val output = root.resolve("seed").toOkioPath()
        val session = engine.addV2Task(TorrentV2TaskSpec("seed", fixture.document,
          output.toString(), checkpoint = fixture.preseed(output, "seed"),
          privacy = TorrentDiscoveryPrivacy.PUBLIC, discover = transmission(daemon),
          discoverMode = PeerIdentityHandshake.Mode.V1))
        session.resume()
        assertEquals(TorrentSessionState.SEEDING, session.settled(),
          session.failure.value?.stackTraceToString())
        daemon.awaitComplete()
        assertFiles(leech)
        val payload = fixture.payloads.sumOf { it.size.toLong() }
        assertTrue(session.v1UploadedBytes() >= payload,
          "Uploaded ${session.v1UploadedBytes()} of $payload bytes over the v1 route")
        engine.removeTorrent(fixture.document.info.hash.hex)
      }
    }
  }

  @Test
  fun hybridTaskDownloadsFromTransmissionV1Seeder() = interop { binary, root ->
    val seed = root.resolve("seed").apply { mkdirs() }
    // Transmission checks the v1 pieces, padding included, so the padding is on disk as zeros.
    for (file in fixture.layout.files) {
      seed.resolve("pack/${fixture.names[file.v2Index]}").apply { parentFile.mkdirs() }
        .writeBytes(fixture.payloads[file.v2Index])
    }
    for (padding in fixture.document.hybrid!!.padding) {
      seed.resolve("pack/.pad/${padding.length}").apply { parentFile.mkdirs() }
        .writeBytes(ByteArray(padding.length.toInt()))
    }
    TransmissionDaemon.start(binary, root, seed).use { daemon ->
      daemon.awaitReady()
      daemon.add(fixture.metainfo, seed)
      daemon.awaitComplete()
      engine(TorrentUploadPolicy.DISABLED) { engine ->
        val output = root.resolve("download")
        val session = engine.addV2Task(TorrentV2TaskSpec("leech", fixture.document,
          output.absolutePath, privacy = TorrentDiscoveryPrivacy.PUBLIC,
          discover = transmission(daemon), discoverMode = PeerIdentityHandshake.Mode.V1))
        session.resume()
        assertEquals(TorrentSessionState.FINISHED, session.settled(),
          session.failure.value?.stackTraceToString())
        for ((index, name) in fixture.names.withIndex()) {
          assertContentEquals(fixture.payloads[index], output.resolve(name).readBytes(), name)
        }
        // Only file bytes are stored and counted; the padding Transmission sent is dropped.
        val payload = fixture.payloads.sumOf { it.size.toLong() }
        assertTrue(session.receivedBytes() >= payload, "Received ${session.receivedBytes()}")
        assertTrue(output.listFiles().orEmpty().none { it.name == ".pad" })
        engine.removeTorrent(fixture.document.info.hash.hex)
      }
    }
  }

  /** Runs [body] with the pinned daemon and a scratch folder, within a minute. */
  private fun interop(body: suspend (binary: String, root: File) -> Unit) = runTest {
    withContext(Dispatchers.IO) {
      withTimeout(60_000) {
        val binary = TransmissionDaemon.binary()
        val root = Files.createTempDirectory("ketch-hybrid-transmission").toFile()
        try { body(binary, root) } finally { root.deleteRecursively() }
      }
    }
  }

  /** Runs [body] with a started engine, then checks that it gave back everything it took. */
  private suspend fun engine(
    policy: TorrentUploadPolicy,
    body: suspend (KotlinTorrentEngine) -> Unit,
  ) {
    val engine = KotlinTorrentEngine(TorrentConfig(dhtEnabled = false, uploadPolicy = policy))
    try {
      engine.start()
      body(engine)
      assertEquals(0, engine.admittedSessionBytes)
    } finally {
      engine.stop()
    }
    assertEquals(0, engine.admittedSessionBytes)
    assertEquals(0, engine.allocatedExchangeBytes)
  }

  /** Discovery that finds only [daemon], as a peer of the v1 swarm. */
  private fun transmission(
    daemon: TransmissionDaemon,
  ): suspend (SendChannel<PeerEndpoint>) -> Unit = { peers ->
    peers.send(PeerEndpoint("127.0.0.1", daemon.peerPort))
    awaitCancellation()
  }

  private suspend fun TorrentV2DownloadSession.settled(): TorrentSessionState = state.first {
    it == TorrentSessionState.SEEDING || it == TorrentSessionState.FINISHED ||
      it == TorrentSessionState.STOPPED
  }

  /** Transmission saved every file of the torrent, byte for byte, under its name. */
  private fun assertFiles(folder: File) {
    for ((index, name) in fixture.names.withIndex()) {
      assertContentEquals(fixture.payloads[index], folder.resolve("pack/$name").readBytes(), name)
    }
  }
}

private fun File.toOkioPath() = toPath().toOkioPath()
