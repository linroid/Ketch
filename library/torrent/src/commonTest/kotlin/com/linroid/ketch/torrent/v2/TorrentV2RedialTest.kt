package com.linroid.ketch.torrent.v2

import com.linroid.ketch.torrent.TorrentV2Fixture
import com.linroid.ketch.torrent.api.TorrentStall
import com.linroid.ketch.torrent.metainfo.Bencode
import com.linroid.ketch.torrent.net.PeerEndpoint
import com.linroid.ketch.torrent.peer.PeerMessage
import com.linroid.ketch.torrent.peer.PeerOrigin
import com.linroid.ketch.torrent.peer.pieceBitfield
import com.linroid.ketch.torrent.v2.peer.PeerV2Dialer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import okio.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A v2 swarm dials a peer that sent payload again after it drops, longer than one that never
 * did: a peer we dialed, one that dialed us, or one whose connection replaced ours. Without a
 * peer, a stalled download dials every peer it knows every minute.
 */
class TorrentV2RedialTest {
  // Pieces of 32 KiB: a spans pieces 0 and 1, b is piece 2, c is piece 3.
  private val fixture = TorrentV2Fixture.build(listOf("a" to 40_000, "b" to 5, "c" to 20_000))
  private val endpoint = PeerEndpoint("192.0.2.5", 6881)

  @Test
  fun droppedPeerThatSentPayload_isDialedAgainAfterItsFourthDial() = swarm {
    discovered.send(TorrentV2Discovered(endpoint, PeerTopic.V2, PeerOrigin.TRACKER))
    val peer = connect(5, dialed = dial.receive())
    deliver(peer, piece = 2)
    drop(peer)
    // One that never sent payload is given up after its fourth dial; this one is dialed a fifth
    // time a minute later.
    expectDials(endpoint, 5_000L, 15_000L, 30_000L, 60_000L)
  }

  @Test
  fun peerThatDialedUsAndSentPayload_isDialedWhereItListensLater() = swarm {
    val peer = connect(7, extensions = true)
    // It says where it listens (BEP 10 `p`), then sends piece 2 and drops.
    peer.send(PeerMessage.Extended(0, Bencode.encode(mapOf(
      "m" to emptyMap<String, Long>(), "p" to 6881L))))
    deliver(peer, piece = 2)
    drop(peer)
    // Not at once, as one that sent nothing is, but as a peer we dialed that sent payload.
    assertNull(withTimeoutOrNull(300) { dial.receive() })
    expectDials(PeerEndpoint("192.0.2.7", 6881), 5_000L, 15_000L, 30_000L, 60_000L)
  }

  @Test
  fun peerKeptOverOurDial_isDialedThereAgainAsOneThatSentPayload() = swarm(listenPort = 7_000) {
    discovered.send(TorrentV2Discovered(endpoint, PeerTopic.V2, PeerOrigin.TRACKER))
    val first = connect(5, dialed = dial.receive())
    deliver(first, piece = 2)
    // It dials us too. We listen on a higher port than it, so its connection replaces ours.
    val second = connect(5, host = endpoint.host)
    assertNotNull(withTimeoutOrNull(5_000) { first.closed.await() },
      "The connection we dialed was kept too")
    assertIs<PeerMessage.Bitfield>(second.next())
    drop(second)
    // Where we dialed it counts the payload the replaced connection brought.
    expectDials(endpoint, 5_000L, 15_000L, 30_000L, 60_000L)
  }

  @Test
  fun lonelyPeer_isDialedEveryMinuteOnceTheDownloadStalls() =
    swarm(stall = MutableStateFlow<TorrentStall?>(null)) {
      discovered.send(TorrentV2Discovered(endpoint, PeerTopic.V2, PeerOrigin.TRACKER))
      val peer = connect(5, dialed = dial.receive())
      deliver(peer, piece = 2)
      drop(peer)
      // Its own dials come 5, 20, 50 and 110 s after it dropped, then two and five minutes
      // apart; without a peer, the stalled download also dials it every minute from 60 s on.
      val upAt = 250_000L
      var elapsed = 0L
      var reached: Long? = null
      while (reached == null && elapsed < upAt + 300_000) {
        skip(10_000)
        elapsed += 10_000
        val target = withTimeoutOrNull(150) { dial.receive() } ?: continue
        assertEquals(endpoint, target.endpoint)
        if (elapsed < upAt) {
          failures.send(PeerV2Dialer.Failure(target, IOException("Connection refused")))
          delay(100)
        } else {
          connect(5, dialed = target)
          reached = elapsed
        }
      }
      val after = assertNotNull(reached, "The peer was never dialed again") - upAt
      assertTrue(after <= 60_000, "Reached $after ms after it came back")
    }

  /** The peer has only [piece] and sends it; returns once it is verified. */
  private suspend fun TorrentV2SwarmRig.deliver(peer: TorrentV2RemotePeer, piece: Int) {
    peer.send(PeerMessage.Bitfield(pieceBitfield(BooleanArray(pieceCount) { it == piece })))
    peer.send(PeerMessage.Control(PeerMessage.Signal.UNCHOKE))
    val request = assertIs<PeerMessage.Request>(peer.next { it is PeerMessage.Request })
    peer.send(PeerMessage.Piece(request.index, request.begin, fixture.v2Piece(request.index)
      .copyOfRange(request.begin, request.begin + request.length)))
    while (!store.verifiedPieces()[piece]) delay(10)
  }

  /**
   * [endpoint] is dialed after each of [waits] in turn, never sooner, and each dial fails.
   * The loop's clock runs with real time too, so each wait is checked 2 s either side.
   */
  private suspend fun TorrentV2SwarmRig.expectDials(endpoint: PeerEndpoint, vararg waits: Long) {
    for (wait in waits) {
      skip(wait - 2_000)
      assertNull(withTimeoutOrNull(300) { dial.receive() }, "Dialed before $wait ms")
      skip(4_000)
      val target = assertNotNull(withTimeoutOrNull(2_000) { dial.receive() },
        "Not dialed after $wait ms")
      assertEquals(endpoint, target.endpoint)
      failures.send(PeerV2Dialer.Failure(target, IOException("Connection refused")))
      // Its next dial counts from when the loop takes the failure, before the clock moves on.
      delay(100)
    }
  }

  /** The peer hangs up; returns once the loop let it go. */
  private suspend fun drop(peer: TorrentV2RemotePeer) {
    peer.hangUp()
    peer.closed.await()
    delay(100)
  }

  /** Runs [test] in a loop that has pieces 0 and 1 and downloads the rest from its peers. */
  private fun swarm(
    listenPort: Int = 0,
    stall: MutableStateFlow<TorrentStall?>? = null,
    test: suspend TorrentV2SwarmRig.() -> Unit,
  ) = runTest {
    withContext(Dispatchers.Default) {
      withTimeout(30_000) {
        val rig = TorrentV2SwarmRig(fixture, onCompleted = { error("Never completes") },
          listenPort = listenPort, stall = stall)
        try {
          rig.store.initialize()
          fixture.seed(rig.store, listOf(0, 1))
          rig.run { rig.test() }
          rig.checkReleased()
        } finally {
          rig.close()
        }
      }
    }
  }
}
