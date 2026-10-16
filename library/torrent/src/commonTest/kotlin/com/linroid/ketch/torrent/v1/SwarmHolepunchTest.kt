package com.linroid.ketch.torrent.v1

import com.linroid.ketch.torrent.net.PeerEndpoint
import com.linroid.ketch.torrent.peer.HolepunchError
import com.linroid.ketch.torrent.peer.HolepunchMessage
import com.linroid.ketch.torrent.peer.HolepunchType
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SwarmHolepunchTest {
  private val a = PeerEndpoint("198.51.100.1", 40001)
  private val aListen = PeerEndpoint("198.51.100.1", 6881)
  private val b = PeerEndpoint("203.0.113.2", 50002)
  private val bListen = PeerEndpoint("203.0.113.2", 6882)
  private var now = 0L
  private val holepunch = SwarmHolepunch { now }
  private val outA = Channel<HolepunchMessage>(16)
  private val outB = Channel<HolepunchMessage>(16)

  private suspend fun connect(supportsB: Boolean = true, utpA: Boolean = false) {
    holepunch.join(1, a, utp = utpA, outbox = outA)
    holepunch.update(1, aListen, supports = true)
    holepunch.join(2, b, utp = false, outbox = outB)
    holepunch.update(2, bListen, supports = supportsB)
  }

  @Test
  fun relay_sendsEachPeerTheOthersEndpoint() = runTest {
    // A reached us over uTP, so its uTP mapping is where B dials it; B by where it listens.
    connect(utpA = true)
    holepunch.relay(1, bListen)
    assertEquals(HolepunchMessage(HolepunchType.CONNECT, a), outB.tryReceive().getOrNull())
    assertEquals(HolepunchMessage(HolepunchType.CONNECT, bListen), outA.tryReceive().getOrNull())
  }

  @Test
  fun relay_targetNotConnectedOrWithoutSupport_answersWithAnError() = runTest {
    connect(supportsB = false)
    val stranger = PeerEndpoint("192.0.2.9", 6881)
    holepunch.relay(1, stranger)
    assertEquals(HolepunchMessage(HolepunchType.ERROR, stranger, HolepunchError.NOT_CONNECTED),
      outA.tryReceive().getOrNull())
    holepunch.relay(1, bListen)
    assertEquals(HolepunchMessage(HolepunchType.ERROR, bListen, HolepunchError.NO_SUPPORT),
      outA.tryReceive().getOrNull())
    holepunch.relay(1, aListen)
    assertEquals(HolepunchMessage(HolepunchType.ERROR, aListen, HolepunchError.NO_SELF),
      outA.tryReceive().getOrNull())
    assertNull(outB.tryReceive().getOrNull())
  }

  @Test
  fun relay_senderThatNeverOfferedTheExtension_isIgnored() = runTest {
    connect()
    holepunch.update(1, aListen, supports = false)
    holepunch.relay(1, bListen)
    assertNull(outA.tryReceive().getOrNull())
    assertNull(outB.tryReceive().getOrNull())
  }

  @Test
  fun summary_countsErrorsByKind_thenStartsOver() = runTest {
    connect()
    holepunch.failed(HolepunchError.NOT_CONNECTED)
    holepunch.failed(HolepunchError.NOT_CONNECTED)
    holepunch.failed(HolepunchError.NO_SUPPORT)
    assertEquals(
      "holepunch peers=2 relayed=0 asked=0 connects=0 errors=notConnected:2,noSupport:1",
      holepunch.summary(),
    )
    assertEquals("holepunch peers=2 relayed=0 asked=0 connects=0", holepunch.summary())
  }

  @Test
  fun rendezvous_asksTheLastIntroducerOnceInFiveMinutes() = runTest {
    connect()
    val target = PeerEndpoint("192.0.2.9", 6881)
    assertFalse(holepunch.rendezvous(target), "Nobody introduced it")
    holepunch.introduced(by = 2, listOf(target))
    assertTrue(holepunch.rendezvous(target))
    assertEquals(HolepunchMessage(HolepunchType.RENDEZVOUS, target), outB.tryReceive().getOrNull())
    now += 60_000
    assertFalse(holepunch.rendezvous(target), "Asked a minute ago")
    now += 5 * 60_000
    holepunch.leave(2)
    assertFalse(holepunch.rendezvous(target), "Its introducer left")
  }
}
