package com.linroid.ketch.torrent

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okio.ByteString.Companion.toByteString
import okio.FileSystem
import okio.Path
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Peer exchange (BEP 11) on v2 connections: engines introduce peers to each other, outgoing
 * peers carry the reachable flag, and restricted torrents neither offer nor take it. Loopback
 * peers count only because these engines allow local peers.
 */
class TorrentV2PexTest {
  // Pieces of 32 KiB: a spans pieces 0 and 1, b is piece 2, c is piece 3.
  private val files = listOf("a" to 40_000, "b" to 5, "c" to 20_000)
  private val fixture = TorrentV2Fixture.build(files)

  private fun engine(policy: TorrentUploadPolicy = TorrentUploadPolicy.DISABLED) =
    KotlinTorrentEngine(TorrentConfig(dhtEnabled = false, uploadPolicy = policy),
      allowLocalPeers = true, listenHost = "127.0.0.1")

  private fun local(port: Int) = PeerEndpoint("127.0.0.1", port)

  private fun spec(
    taskId: String,
    output: Path,
    fixture: TorrentV2Fixture = this.fixture,
    privacy: TorrentDiscoveryPrivacy = TorrentDiscoveryPrivacy.PUBLIC,
    checkpoint: TorrentV2Checkpoint? = null,
    throttle: suspend (Int) -> Unit = {},
    peer: PeerEndpoint? = null,
  ) = TorrentV2TaskSpec(taskId, fixture.document, output.toString(), checkpoint = checkpoint,
    privacy = privacy, throttle = throttle, discover = { peers ->
      peer?.let { peers.send(it) }
      awaitCancellation()
    })

  /** Runs [body] with a scratch directory and [engines], then stops them and checks budgets. */
  private fun engines(
    engines: List<KotlinTorrentEngine>,
    body: suspend CoroutineScope.(Path) -> Unit,
  ) = runTest {
    withContext(Dispatchers.Default) {
      withTimeout(30_000) {
        val root = (FileSystem.SYSTEM_TEMPORARY_DIRECTORY /
          "ketch-v2-pex-${InfoHash.fromBytes(torrentRandomBytes(20)).hex}")
          .also { torrentFileSystem.createDirectories(it) }
        try {
          engines.forEach { it.start() }
          body(root)
          engines.forEach { assertEquals(0, it.admittedSessionBytes) }
        } finally {
          engines.forEach { it.stop() }
          torrentFileSystem.deleteRecursively(root, mustExist = false)
        }
        engines.forEach { assertEquals(0, it.allocatedExchangeBytes) }
      }
    }
  }

  private suspend fun TorrentV2DownloadSession.reaches(target: TorrentSessionState) {
    assertEquals(target, state.first { it == target || it == TorrentSessionState.STOPPED },
      failure.value?.stackTraceToString())
  }

  @Test
  fun pexIntroducesAThirdEngineToTheSeeder() {
    val seeder = engine(TorrentUploadPolicy.SEED_AFTER_COMPLETION)
    // The middle engine uploads nothing, so the third can only complete from the seeder.
    val middle = engine(TorrentUploadPolicy.DISABLED)
    val third = engine()
    engines(listOf(seeder, middle, third)) { root ->
      val seed = seeder.addV2Task(spec("seed", root / "seed",
        checkpoint = fixture.preseed(root / "seed", "seed")))
      seed.resume()
      seed.reaches(TorrentSessionState.SEEDING)
      // It knows the seed, but never asks it for anything: it only introduces.
      val held = CompletableDeferred<Unit>()
      val introducer = middle.addV2Task(spec("middle", root / "middle",
        throttle = { held.await() }, peer = local(seeder.listenPort)))
      introducer.resume()
      try {
        // The third engine knows only the introducer.
        val leech = third.addV2Task(spec("third", root / "third",
          peer = local(middle.listenPort)))
        leech.resume()
        leech.reaches(TorrentSessionState.FINISHED)
        for ((index, name) in fixture.names.withIndex()) {
          assertContentEquals(fixture.payloads[index],
            torrentFileSystem.read(root / "third" / name) { readByteArray() })
        }
        assertEquals(0L, introducer.uploadedBytes())
        assertTrue(seed.uploadedBytes() >= files.sumOf { it.second }, "${seed.uploadedBytes()}")
        third.removeTorrent(fixture.document.info.hash.hex)
      } finally { held.complete(Unit) }
      middle.removeTorrent(fixture.document.info.hash.hex)
      seeder.removeTorrent(fixture.document.info.hash.hex)
    }
  }

  @Test
  fun outgoingPeersCarryReachableFlag() {
    val owner = engine(TorrentUploadPolicy.SEED_AFTER_COMPLETION)
    engines(listOf(owner)) { root ->
      val network = createTorrentNetwork()
      val dialed = network.listen(local(0))
      try {
        val seed = owner.addV2Task(spec("seed", root / "seed",
          checkpoint = fixture.preseed(root / "seed", "seed"), peer = dialed.local))
        seed.resume()
        seed.reaches(TorrentSessionState.SEEDING)
        // A peer the owner dialed, so it is known to accept connections.
        val outgoing = Raw.answer(dialed.accept(), fixture)
        outgoing.until { it is PeerMessage.Bitfield }
        // A peer that dialed the owner and told it where it listens.
        val incoming = Raw.dial(network, local(owner.listenPort), fixture)
        incoming.until { it is PeerMessage.Bitfield }
        incoming.wire.send(PeerMessage.Extended(0, Bencode.encode(mapOf(
          "m" to emptyMap<String, Long>(), "p" to 7_777L))))
        incoming.wire.send(PeerMessage.Control(PeerMessage.Signal.INTERESTED))
        // Its handshake came before INTERESTED, so the owner knows its port once it unchokes.
        incoming.until { it == PeerMessage.Control(PeerMessage.Signal.UNCHOKE) }
        // A third peer takes peer exchange and hears of both.
        val listener = Raw.dial(network, local(owner.listenPort), fixture)
        listener.wire.send(PeerMessage.Extended(0, Bencode.encode(mapOf(
          "m" to mapOf("ut_pex" to 5L)))))
        val update = listener.until { it is PeerMessage.Extended && it.id == 5 }
        val message = Bencode.parse((update as PeerMessage.Extended).payload)
        val added = TorrentTracker.compactEntries(checkNotNull(message["added"]?.bytes), false)
        val flags = checkNotNull(message["added.f"]?.bytes)
        assertEquals(added.size, flags.size)
        val advertised = added.indices.associate {
          checkNotNull(added[it]) to (flags[it].toInt() and 0xff)
        }
        // Only listen endpoints, never the incoming peer's source port; only the dialed peer
        // is marked reachable.
        assertEquals(mapOf(dialed.local to PEX_FLAG_REACHABLE, local(7_777) to 0), advertised)
        listOf(outgoing, incoming, listener).forEach { it.close() }
        owner.removeTorrent(fixture.document.info.hash.hex)
      } finally {
        dialed.close()
        network.close()
      }
    }
  }

  @Test
  fun privateV2NeverAdvertisesOrAcceptsPex() {
    val restricted = TorrentV2Fixture.build(files, privateTorrent = true)
    val owner = engine(TorrentUploadPolicy.SEED_AFTER_COMPLETION)
    engines(listOf(owner)) { root ->
      val network = createTorrentNetwork()
      val listener = network.listen(local(0))
      try {
        // The owner dials its tracker's peer; private torrents take no other.
        owner.addV2Task(spec("private", root / "private", fixture = restricted,
          peer = listener.local)).resume()
        val peer = Raw.answer(listener.accept(), restricted)
        val ours = assertIs<PeerMessage.Extended>(peer.wire.read())
        // Neither peer exchange nor the info dictionary is offered (BEP 27).
        assertEquals(emptySet(), offered(ours))
        // Peer exchange sent anyway breaks the rules: the owner hangs up.
        peer.wire.send(PeerMessage.Extended(0, Bencode.encode(mapOf(
          "m" to mapOf("ut_pex" to 5L)))))
        peer.wire.send(PeerMessage.Extended(PeerExtensions.PEX, Bencode.encode(mapOf(
          "added" to DhtCodec.compactEndpoint(PeerEndpoint("203.0.113.7", 6_881)),
          "added.f" to byteArrayOf(PEX_FLAG_REACHABLE.toByte())))))
        val failure = assertNotNull(runCatching {
          withTimeout(5_000) { while (true) peer.wire.read() }
        }.exceptionOrNull(), "The owner kept a peer that sent peer exchange")
        assertFalse(failure is TimeoutCancellationException, "The connection stayed open")
        peer.close()
        owner.removeTorrent(restricted.document.info.hash.hex)
      } finally {
        listener.close()
        network.close()
      }
    }
  }

  @Test
  fun trackerOnlyV2NeverAdvertisesPex() {
    val owner = engine(TorrentUploadPolicy.SEED_AFTER_COMPLETION)
    engines(listOf(owner)) { root ->
      val network = createTorrentNetwork()
      val listener = network.listen(local(0))
      try {
        owner.addV2Task(spec("tracker-only", root / "tracker-only",
          privacy = TorrentDiscoveryPrivacy.TRACKER_ONLY, peer = listener.local)).resume()
        val peer = Raw.answer(listener.accept(), fixture)
        val ours = assertIs<PeerMessage.Extended>(peer.wire.read())
        // Public metainfo still shares its info dictionary while uploading; never its peers.
        assertEquals(setOf("ut_metadata"), offered(ours))
        peer.close()
        owner.removeTorrent(fixture.document.info.hash.hex)
      } finally {
        listener.close()
        network.close()
      }
    }
  }

  private fun offered(handshake: PeerMessage.Extended): Set<String> {
    assertEquals(0, handshake.id)
    val values = Bencode.parse(handshake.payload)
    return checkNotNull(values["m"]?.dictionary).keys.map { it.utf8() }.toSet()
  }

  /** A raw v2 peer with extensions, speaking for the test. */
  private class Raw(val connection: TorrentConnection, pieceCount: Int) {
    val wire = PeerWire(connection, pieceCount = pieceCount)

    /** The next message that [match]es; others are skipped. */
    suspend fun until(match: (PeerMessage) -> Boolean): PeerMessage {
      while (true) {
        val message = wire.read()
        if (match(message)) return message
      }
    }

    fun close() = connection.close()

    companion object {
      private val budget = TorrentBufferBudget(65_536)

      /** Answers the owner, which dialed [connection]. */
      suspend fun answer(connection: TorrentConnection, fixture: TorrentV2Fixture): Raw {
        val route = PeerIdentityHandshake(fixture.document.identity, extensions = true)
          .respond(connection, torrentRandomBytes(20).toByteString(), budget)
        check(route.extensions)
        return Raw(connection, fixture.layout.pieceCount.toInt())
      }

      /** Dials the owner at [endpoint]. */
      suspend fun dial(
        network: TorrentNetwork,
        endpoint: PeerEndpoint,
        fixture: TorrentV2Fixture,
      ): Raw {
        val connection = network.connect(endpoint)
        val route = PeerIdentityHandshake(fixture.document.identity, extensions = true)
          .initiate(connection, PeerIdentityHandshake.Mode.V2,
            torrentRandomBytes(20).toByteString(), budget)
        check(route.extensions)
        return Raw(connection, fixture.layout.pieceCount.toInt())
      }
    }
  }
}
