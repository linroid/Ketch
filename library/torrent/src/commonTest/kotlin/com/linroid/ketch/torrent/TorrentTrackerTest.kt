package com.linroid.ketch.torrent

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okio.Buffer
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class TorrentTrackerTest {
  private val request = TrackerAnnounce(InfoHash.fromBytes(ByteArray(20) { it.toByte() }),
    ByteArray(20) { (255 - it).toByte() }, 6881, 123, 456, event = TrackerEvent.STARTED)

  @Test
  fun httpQuery_preservesPasskeyAndEncodesBinaryWithoutUtf8Conversion() {
    val url = TorrentTracker.httpUrl("https://tracker/secret?passkey=a%2Bb", request,
      byteArrayOf(0, 255.toByte(), 32))
    assertTrue(url.startsWith("https://tracker/secret?passkey=a%2Bb&info_hash=%00%01%02"))
    assertTrue("peer_id=%ff%fe%fd" in url)
    assertTrue("&event=started&trackerid=%00%ff%20" in url)
    assertTrue("&downloaded=123&left=456&uploaded=0" in url)
  }

  @Test
  fun httpResponse_readsBothFamiliesAndMinimumInterval() {
    val response = TorrentTracker.parseHttp(Bencode.encode(mapOf(
      "interval" to 30L, "min interval" to 60L,
      "peers" to byteArrayOf(127, 0, 0, 1, 0x1a, 0xe1.toByte()),
      "peers6" to (ByteArray(15) + byteArrayOf(1, 0x1a, 0xe1.toByte()))
    )))
    assertEquals(60L, response.intervalSeconds)
    assertEquals(listOf(PeerEndpoint("127.0.0.1", 6881),
      PeerEndpoint("0:0:0:0:0:0:0:1", 6881)), response.peers)
    assertFailsWith<IllegalArgumentException> {
      TorrentTracker.compactPeers(byteArrayOf(1), false)
    }
    val error = assertFailsWith<IllegalArgumentException> {
      TorrentTracker.parseHttp(Bencode.encode(mapOf("failure reason" to "secret passkey")))
    }
    assertEquals("Tracker rejected announce", error.message)
  }

  @Test
  fun tiers_exhaustFailedTierAndRememberSuccessfulTrackerId() = runTest {
    val calls = mutableListOf<String>()
    val tracker = TrackerTiers(listOf(listOf("a", "b"), listOf("c"))) { url, _, id ->
      calls += url
      if (url != "c") error("offline")
      if (calls.size > 3) assertContentEquals(byteArrayOf(7), id)
      TrackerResponse(emptyList(), 60, byteArrayOf(7))
    }
    tracker.announce(request)
    assertEquals(setOf("a", "b"), calls.take(2).toSet())
    assertEquals("c", calls.last())
    tracker.announce(request)
    assertEquals(6, calls.size)
  }

  @Test
  fun udp_ipv4_validatesTransactionAndCarriesAnnounceFields() = runTest { udp(false) }

  @Test
  fun udp_ipv6_validatesTransactionAndCarriesAnnounceFields() = runTest { udp(true) }

  @Test
  fun udp_lostConnectResponse_retriesAndIgnoresSpoofedEndpoint() = runTest {
    udp(false, dropFirst = true)
  }

  private suspend fun udp(ipv6: Boolean, dropFirst: Boolean = false) = withContext(Dispatchers.Default) {
    withTimeout(15_000) {
      coroutineScope {
        val network = createTorrentNetwork()
        val http = TorrentHttp.default()
        val socket = network.bindUdp(PeerEndpoint(if (ipv6) "::1" else "127.0.0.1", 0))
        try {
          val server = async {
            if (dropFirst) socket.receive()
            val connect = socket.receive()
            val packet = Buffer().write(connect.bytes)
            assertEquals(0x41727101980L, packet.readLong())
            assertEquals(0, packet.readInt())
            val id = packet.readInt()
            val spoofer = network.bindUdp(PeerEndpoint(if (ipv6) "::1" else "127.0.0.1", 0))
            try {
              spoofer.send(connect.remote, Buffer().writeInt(3).writeInt(id).readByteArray())
            } finally {
              spoofer.close()
            }
            socket.send(connect.remote, Buffer().writeInt(0).writeInt(id xor 1)
              .writeLong(99).readByteArray())
            socket.send(connect.remote, Buffer().writeInt(0).writeInt(id)
              .writeLong(99).readByteArray())
            val announce = socket.receive()
            val body = Buffer().write(announce.bytes)
            assertEquals(99L, body.readLong())
            assertEquals(1, body.readInt())
            val transaction = body.readInt()
            assertContentEquals(request.infoHash.toBytes(), body.readByteArray(20))
            assertContentEquals(request.peerId, body.readByteArray(20))
            assertEquals(123L, body.readLong())
            assertEquals(456L, body.readLong())
            assertEquals(0L, body.readLong())
            assertEquals(2, body.readInt())
            body.skip(12)
            assertEquals(6881, body.readShort().toInt() and 65535)
            assertEquals(2, body.readByte().toInt())
            val length = body.readByte().toInt() and 255
            assertEquals("/announce?key=a%2Bb", body.readUtf8(length.toLong()))
            val address = if (ipv6) ByteArray(15) + byteArrayOf(1)
              else byteArrayOf(127, 0, 0, 1)
            socket.send(announce.remote, Buffer().writeInt(1).writeInt(transaction)
              .writeInt(60).writeInt(0).writeInt(1).write(address).writeShort(6881)
              .readByteArray())
          }
          val host = if (ipv6) "[::1]" else "127.0.0.1"
          val response = TorrentTracker(http, network,
            retryDelaysMs = if (dropFirst) listOf(100, 5000) else listOf(5000)).announce(
            "udp://$host:${socket.local.port}/announce?key=a%2Bb", request
          )
          assertEquals(60L, response.intervalSeconds)
          assertEquals(6881, response.peers.single().port)
          server.await()
        } finally {
          network.close()
          http.close()
        }
      }
    }
  }
}
