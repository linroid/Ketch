package com.linroid.ketch.torrent.v2

import com.linroid.ketch.torrent.TorrentUploadPolicy
import com.linroid.ketch.torrent.TorrentV2Fixture
import com.linroid.ketch.torrent.dht.DhtCodec
import com.linroid.ketch.torrent.metainfo.Bencode
import com.linroid.ketch.torrent.net.PeerEndpoint
import com.linroid.ketch.torrent.peer.HOLEPUNCH_EXTENSION
import com.linroid.ketch.torrent.peer.HolepunchMessage
import com.linroid.ketch.torrent.peer.HolepunchType
import com.linroid.ketch.torrent.peer.PEX_FLAG_HOLEPUNCH
import com.linroid.ketch.torrent.peer.PeerExtensions
import com.linroid.ketch.torrent.peer.PeerIdentityHandshake
import com.linroid.ketch.torrent.peer.PeerMessage
import com.linroid.ketch.torrent.v2.peer.PeerV2Dialer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import okio.IOException
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Hole punching (BEP 55) in a v2 session's loop, with scripted peers: it relays a rendezvous,
 * also between a hybrid's v1 and v2 routes, dials a peer a relay names over uTP first, and asks
 * the peer that introduced an endpoint it cannot reach to punch through.
 */
@OptIn(ExperimentalAtomicApi::class)
class TorrentV2HolepunchTest {
  private val fixture = TorrentV2Fixture.build(listOf("a" to 100_000))
  private val target = PeerEndpoint("127.0.0.3", 6883)

  private fun TorrentV2RemotePeer.greet(listenPort: Int? = null) {
    val handshake = buildMap<String, Any> {
      put("m", mapOf(HOLEPUNCH_EXTENSION to PUNCH_ID, "ut_pex" to 4L))
      listenPort?.let { put("p", it.toLong()) }
    }
    send(PeerMessage.Extended(0, Bencode.encode(handshake)))
  }

  private fun TorrentV2RemotePeer.sendPunch(message: HolepunchMessage) =
    send(PeerMessage.Extended(PeerExtensions.HOLEPUNCH, message.encode()))

  private suspend fun TorrentV2RemotePeer.nextPunch(): HolepunchMessage {
    val message = next { it is PeerMessage.Extended && it.id == PUNCH_ID.toInt() }
    val punch = HolepunchMessage.decode(assertIs<PeerMessage.Extended>(message).payload)
    return checkNotNull(punch)
  }

  private suspend fun TorrentV2RemotePeer.offered(): Set<String> {
    val message = next { it is PeerMessage.Extended && it.id == 0 }
    val handshake = Bencode.parse(assertIs<PeerMessage.Extended>(message).payload)
    return checkNotNull(handshake["m"]?.dictionary).keys.map { it.utf8() }.toSet()
  }

  @Test
  fun rendezvous_betweenTwoPeers_sendsEachTheOther() = swarm {
    val first = connect(1, host = "127.0.0.1", extensions = true)
    val second = connect(2, host = "127.0.0.2", extensions = true)
    assertTrue(HOLEPUNCH_EXTENSION in first.offered())
    first.greet(6881)
    second.greet(6882)
    assertBothIntroduced(first, second)
  }

  @Test
  fun rendezvous_acrossAHybridsRoutes_sendsEachTheOther() = swarm {
    val first = connect(1, PeerIdentityHandshake.Mode.V1, "127.0.0.1", extensions = true)
    val second = connect(2, host = "127.0.0.2", extensions = true)
    first.greet(6881)
    second.greet(6882)
    assertBothIntroduced(first, second)
  }

  /** Asks again until the loop read [second]'s handshake, which [first]'s says nothing about. */
  private suspend fun assertBothIntroduced(
    first: TorrentV2RemotePeer,
    second: TorrentV2RemotePeer,
  ) {
    val listen = PeerEndpoint("127.0.0.2", 6882)
    while (true) {
      first.sendPunch(HolepunchMessage(HolepunchType.RENDEZVOUS, listen))
      if (first.nextPunch().type == HolepunchType.CONNECT) break
      delay(10)
    }
    assertEquals(HolepunchMessage(HolepunchType.CONNECT, PeerEndpoint("127.0.0.1", 6881)),
      second.nextPunch())
  }

  @Test
  fun connect_fromARelay_dialsThePeerOverUtpFirst() = swarm {
    val relay = connect(1, host = "127.0.0.1", extensions = true)
    relay.greet()
    relay.sendPunch(HolepunchMessage(HolepunchType.CONNECT, target))
    assertEquals(target, dial.receive().endpoint)
    assertEquals(target, utpPeers.receive())
  }

  @Test
  fun connects_fromOnePeer_areLimitedToEightAMinute() = swarm {
    val relay = connect(1, host = "127.0.0.1", extensions = true)
    relay.greet()
    val targets = (1..12).map { PeerEndpoint("127.0.0.3", 20_000 + it) }
    for (peer in targets) relay.sendPunch(HolepunchMessage(HolepunchType.CONNECT, peer))
    assertEquals(targets.take(8), List(8) { utpPeers.receive() })
    assertNull(withTimeoutOrNull(300) { utpPeers.receive() })
  }

  @Test
  fun utpOff_neitherOffersNorActsOnHolepunch() = swarm(utp = false) {
    val relay = connect(1, host = "127.0.0.1", extensions = true)
    assertFalse(HOLEPUNCH_EXTENSION in relay.offered())
    relay.greet()
    relay.sendPunch(HolepunchMessage(HolepunchType.CONNECT, target))
    assertNull(withTimeoutOrNull(300) { dial.receive() })
  }

  @Test
  fun unreachableIntroducedPeer_asksItsIntroducerToPunch() = swarm {
    val relay = connect(1, host = "127.0.0.1", extensions = true)
    relay.greet()
    relay.send(PeerMessage.Extended(PeerExtensions.PEX, Bencode.encode(mapOf(
      "added" to DhtCodec.compactEndpoint(target),
      "added.f" to byteArrayOf(PEX_FLAG_HOLEPUNCH.toByte())))))
    assertEquals(target, utpPeers.receive())
    val dialed = dial.receive()
    assertEquals(target, dialed.endpoint)
    failures.send(PeerV2Dialer.Failure(dialed, IOException("Connection refused")))
    assertEquals(HolepunchMessage(HolepunchType.RENDEZVOUS, target), relay.nextPunch())
  }

  /** Runs [test] in a loop that uploads while it downloads its last piece, which never comes. */
  private fun swarm(utp: Boolean = true, test: suspend TorrentV2SwarmRig.() -> Unit) = runTest {
    withContext(Dispatchers.Default) {
      withTimeout(20_000) {
        val rig = TorrentV2SwarmRig(fixture, onCompleted = { error("Never completes") },
          utpEnabled = utp, allowLocalPeers = true)
        try {
          rig.store.initialize()
          fixture.seed(rig.store, listOf(0))
          rig.policy.store(TorrentUploadPolicy.WHILE_DOWNLOADING)
          rig.run { rig.test() }
          rig.checkReleased()
        } finally {
          rig.close()
        }
      }
    }
  }

  private companion object {
    /** The ID the scripted peers take holepunch messages under. */
    const val PUNCH_ID = 7L
  }
}
