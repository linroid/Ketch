package com.linroid.ketch.torrent

import kotlinx.coroutines.channels.Channel
import okio.Buffer
import okio.ByteString.Companion.toByteString
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The swarm loop's extension side against a peer whose actor queue the test holds: what waits
 * when the queue is full, and what a peer may make us offer.
 */
class TorrentV2ExtensionsTest {
  private var now = 0L
  private val fixture = TorrentV2Fixture.build(listOf("a" to 40_000))
  private val buffers = TorrentBufferBudget(1 shl 20)
  private val state = TorrentBufferBudget(1 shl 20)
  private val channels = mutableListOf<Channel<*>>()
  private val keep = TorrentV2Uploader.Verdict.KEEP

  private val network = object : TorrentNetwork {
    override suspend fun connect(remote: PeerEndpoint): TorrentConnection = error("Unused")
    override suspend fun listen(local: PeerEndpoint): TorrentListener = error("Unused")
    override suspend fun bindUdp(local: PeerEndpoint): TorrentDatagramSocket = error("Unused")
    override fun close() = Unit
  }

  private val candidates = TorrentV2Candidates(hybrid = false, restricted = false) { now }
  private val extensions = TorrentV2Extensions(TorrentV2Swarm(fixture.document,
    TorrentV2Runtime(network, ByteArray(20) { 9 }.toByteString(), buffers, state,
      uploadPolicy = { TorrentUploadPolicy.SEED_AFTER_COMPLETION }, allowLocalPeers = true),
    restricted = false, waitForPeers = true, discovered = channel(), dial = channel(),
    dialFailures = channel(), controls = channel(), connectionLimit = { 8 },
    generation = { 0 }), candidates) { now }

  private fun <T> channel(capacity: Int = Channel.RENDEZVOUS): Channel<T> =
    Channel<T>(capacity).also { channels += it }

  private class Link(override val remote: PeerEndpoint) : TorrentConnection {
    override suspend fun readExactly(size: Int): ByteArray = error("Unused")
    override suspend fun write(bytes: ByteArray) = error("Unused")
    override fun close() = Unit
  }

  /** A peer we dialed at [endpoint] that negotiated extensions, with a queue of [capacity]. */
  private inner class Remote(val endpoint: PeerEndpoint, capacity: Int = 1) {
    val commands = channel<PeerV2DownloadActor.Command>(capacity)
    private val link = Link(endpoint)
    val info = PeerInfo(ByteArray(20) { endpoint.port.toByte() }.toByteString(),
      PeerIdentityHandshake.Mode.V2, PeerV2Origin.Outgoing(endpoint), extensions = true,
      link = link)
    val view = TorrentV2PeerView(commands, info, nowMs = { now })
    private val transport = PeerHashTransport(link, PeerHashExchange(buffers, { null }), buffers)
    val peer = PeerV2Pool.Peer(PeerBlockExchange(fixture.layout, transport, buffers),
      transport, info = info)

    /**
     * Its extension handshake: our messages to it use its IDs 3 (`ut_metadata`) and, unless it
     * leaves [pex] out, 4.
     */
    fun greet(pex: Boolean = true) {
      val ids = if (pex) mapOf("ut_metadata" to 3L, "ut_pex" to 4L) else mapOf("ut_metadata" to 3L)
      assertEquals(keep, extensions.receive(view, PeerMessage.Extended(0, Bencode.encode(mapOf(
        "m" to ids)))))
    }

    /** What we sent it, in order, taking each from its queue. */
    fun sent(): List<PeerMessage> = generateSequence {
      commands.tryReceive().getOrNull()
    }.map { (it as PeerV2DownloadActor.Command.Send).message }.toList()

    /** Fills its queue, as the Haves of a fast download do. */
    fun fill() {
      while (true) {
        if (!commands.trySend(PeerV2DownloadActor.Command.Send(PeerMessage.Have(0))).isSuccess) {
          return
        }
      }
    }
  }

  @AfterTest
  fun release() {
    channels.forEach { it.cancel() }
    assertEquals(0, buffers.allocated)
  }

  private fun metadataRequest(piece: Int) =
    TorrentMetadataExchange.metadataMessage(PeerExtensions.METADATA, 0, piece)

  private fun pexPeers(message: PeerMessage): PexUpdate {
    val extended = assertIs<PeerMessage.Extended>(message)
    assertEquals(4, extended.id)
    return PeerExchange().receive(extended.payload)
  }

  @Test
  fun metadataRequestOnAFullQueueWaitsForRoom() {
    val remote = Remote(PeerEndpoint("10.0.0.1", 6881))
    remote.greet(pex = false)
    remote.fill()
    // Our own backlog is no reason to drop the peer: the answer waits for room.
    assertEquals(keep, extensions.receive(remote.view, metadataRequest(0)))
    // A queue that drains tells the loop nothing, so it looks again every 250 ms.
    val peers = mapOf(remote.peer to remote.view)
    assertEquals(250L, extensions.nextDueMs(peers))
    extensions.flush(remote.view)
    assertEquals(listOf<PeerMessage>(PeerMessage.Have(0)), remote.sent())
    extensions.flush(remote.view)
    val answer = assertIs<PeerMessage.Extended>(remote.sent().single())
    assertEquals(3, answer.id)
    val header = Bencode.parsePrefix(answer.payload, PeerWire.MAX_FRAME_SIZE)
    assertEquals(1L, header["msg_type"]?.integer)
    assertEquals(fixture.document.info.rawInfo,
      answer.payload.copyOfRange(header.end, answer.payload.size).toByteString())
    assertNull(extensions.nextDueMs(peers))
  }

  @Test
  fun waitingMetadataAnswersAreBounded() {
    val remote = Remote(PeerEndpoint("10.0.0.1", 6881))
    remote.greet()
    remote.fill()
    // Far more than any client keeps outstanding: the rest go unanswered, the peer stays.
    repeat(MAX_QUEUED_METADATA_ANSWERS + 4) {
      assertEquals(keep, extensions.receive(remote.view, metadataRequest(0)))
    }
    assertEquals(MAX_QUEUED_METADATA_ANSWERS, remote.view.metadataAnswers.size)
    remote.view.close()
  }

  @Test
  fun metadataAnswerDroppedUnsentGoesOutAgain() {
    val remote = Remote(PeerEndpoint("10.0.0.1", 6881), capacity = 4)
    remote.greet(pex = false)
    assertEquals(keep, extensions.receive(remote.view, metadataRequest(0)))
    val answer = remote.sent().single()
    // The actor had no frame credit for it: it waits a moment rather than spin until there is.
    extensions.sent(remote.view, PeerV2DownloadActor.Event.Sent(answer, sent = false))
    val peers = mapOf(remote.peer to remote.view)
    assertEquals(250L, extensions.nextDueMs(peers))
    extensions.flush(remote.view)
    assertTrue(remote.sent().isEmpty())
    now += 100
    assertEquals(150L, extensions.nextDueMs(peers))
    now += 150
    extensions.flush(remote.view)
    val again = assertIs<PeerMessage.Extended>(remote.sent().single())
    assertEquals(1L, Bencode.parsePrefix(again.payload, PeerWire.MAX_FRAME_SIZE)["msg_type"]
      ?.integer)
    assertNull(extensions.nextDueMs(peers))
  }

  @Test
  fun peerExchangeRefusedByAFullQueueIsSentLater() {
    val target = Remote(PeerEndpoint("10.0.0.1", 6881))
    val other = Remote(PeerEndpoint("10.0.0.2", 6881))
    target.greet()
    val peers = mapOf(target.peer to target.view, other.peer to other.view)
    val listen = { peer: PeerV2Pool.Peer -> peers.getValue(peer).info?.link?.remote }
    target.fill()
    extensions.pump(peers, listen)
    assertEquals(listOf<PeerMessage>(PeerMessage.Have(0)), target.sent())
    // Nothing went out, so nothing counts as told: five seconds on, the same peer is offered.
    now += 5_000
    extensions.pump(peers, listen)
    val update = pexPeers(target.sent().single())
    assertEquals(listOf(other.endpoint), update.added)
    assertEquals(mapOf(other.endpoint to PEX_FLAG_REACHABLE), update.flags)
  }

  @Test
  fun peerExchangeDroppedUnsentIsSentAgain() {
    val target = Remote(PeerEndpoint("10.0.0.1", 6881))
    val other = Remote(PeerEndpoint("10.0.0.2", 6881))
    target.greet()
    val peers = mapOf(target.peer to target.view, other.peer to other.view)
    val listen = { peer: PeerV2Pool.Peer -> peers.getValue(peer).info?.link?.remote }
    extensions.pump(peers, listen)
    val first = target.sent().single()
    assertEquals(listOf(other.endpoint), pexPeers(first).added)
    // The actor dropped it for want of frame credit; a moment later the peer is told again.
    extensions.sent(target.view, PeerV2DownloadActor.Event.Sent(first, sent = false))
    assertEquals(250L, extensions.nextDueMs(peers))
    extensions.pump(peers, listen)
    assertTrue(target.sent().isEmpty())
    now += 250
    extensions.pump(peers, listen)
    assertEquals(listOf(other.endpoint), pexPeers(target.sent().single()).added)
    // Sent this time: nothing more for a minute.
    now += 5_000
    extensions.pump(peers, listen)
    assertTrue(target.sent().isEmpty())
  }

  @Test
  fun droppedPeerExchangeEntriesMakeNoRoomForMore() {
    val remote = Remote(PeerEndpoint("10.0.0.1", 6881))
    remote.greet()
    fun hosts(from: Int) = List(MAX_PEX_INTRODUCTIONS) { PeerEndpoint("10.9.${from + it}.1", 6881) }
    fun compact(peers: List<PeerEndpoint>) =
      peers.fold(Buffer()) { buffer, peer -> buffer.write(DhtCodec.compactEndpoint(peer)) }
        .readByteArray()
    val first = hosts(0)
    assertEquals(keep, extensions.receive(remote.view, PeerMessage.Extended(PeerExtensions.PEX,
      Bencode.encode(mapOf("added" to compact(first))))))
    assertEquals(MAX_PEX_INTRODUCTIONS, candidates.pendingCount)
    // Dropping its first introductions and adding as many new ones introduces nobody new.
    now += 20_000
    assertEquals(keep, extensions.receive(remote.view, PeerMessage.Extended(PeerExtensions.PEX,
      Bencode.encode(mapOf("added" to compact(hosts(100)), "dropped" to compact(first))))))
    assertEquals(MAX_PEX_INTRODUCTIONS, candidates.pendingCount)
    assertEquals(first.toSet(), generateSequence { candidates.next() }.map { it.endpoint }.toSet())
  }
}
