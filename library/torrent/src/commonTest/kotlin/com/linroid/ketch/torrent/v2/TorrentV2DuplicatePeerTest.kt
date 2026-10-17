package com.linroid.ketch.torrent.v2

import com.linroid.ketch.torrent.TorrentUploadPolicy
import com.linroid.ketch.torrent.TorrentV2Fixture
import com.linroid.ketch.torrent.net.PeerEndpoint
import com.linroid.ketch.torrent.peer.PeerMessage
import com.linroid.ketch.torrent.peer.PeerOrigin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * A v2 swarm keeps one connection to a peer it reaches at several addresses of one machine or
 * network, such as loopback from a magnet's `x.pe` and the LAN address local discovery found.
 */
@OptIn(ExperimentalAtomicApi::class)
class TorrentV2DuplicatePeerTest {
  private val fixture = TorrentV2Fixture.build(listOf("a" to 40_000, "b" to 5, "c" to 20_000))

  @Test
  fun peerDialedAtTwoLocalAddresses_keepsOneConnectionAndNeverDialsTheOther() = swarm {
    val loopback = PeerEndpoint("127.0.0.1", 6881)
    discovered.send(TorrentV2Discovered(loopback, PeerTopic.V2, PeerOrigin.EXPLICIT))
    val first = connect(5, dialed = dial.receive())
    assertIs<PeerMessage.Bitfield>(first.next())
    val lan = PeerEndpoint("192.168.31.11", 6881)
    discovered.send(TorrentV2Discovered(lan, PeerTopic.V2, PeerOrigin.LSD))
    val second = connect(5, dialed = dial.receive())
    assertNotNull(withTimeoutOrNull(5_000) { second.closed.await() },
      "Both connections to one peer were kept")
    // Not dialed again while the peer is connected at the other address. The selection change
    // returns once the loop passed the wake-up the clock's skip sent.
    skip(60_000)
    select()
    assertNull(dial.tryReceive().getOrNull())
    assertFalse(first.closed.isCompleted)
    first.send(PeerMessage.Control(PeerMessage.Signal.INTERESTED))
    val unchoke = PeerMessage.Control(PeerMessage.Signal.UNCHOKE)
    assertEquals(unchoke, first.next { it == unchoke })
    first.send(PeerMessage.Request(0, 0, 16_384))
    assertContentEquals(fixture.v2Piece(0).copyOfRange(0, 16_384), first.nextPiece().bytes)
  }

  /** Runs [test] in a loop that uploads while it downloads its last piece, which never comes. */
  private fun swarm(test: suspend TorrentV2SwarmRig.() -> Unit) = runTest {
    withContext(Dispatchers.Default) {
      withTimeout(20_000) {
        val rig = TorrentV2SwarmRig(fixture, onCompleted = { error("Never completes") })
        try {
          rig.store.initialize()
          fixture.seed(rig.store, listOf(0, 1, 2))
          rig.policy.store(TorrentUploadPolicy.WHILE_DOWNLOADING)
          rig.run { rig.test() }
          rig.checkReleased()
        } finally {
          rig.close()
        }
      }
    }
  }
}
