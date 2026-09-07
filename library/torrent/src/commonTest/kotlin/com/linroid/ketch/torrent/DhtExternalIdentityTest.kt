package com.linroid.ketch.torrent

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.runTest
import okio.ByteString.Companion.toByteString
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DhtExternalIdentityTest {
  @Test
  fun externalIdentity_requiresThreeIndependentNetworkReports() = runTest {
    val observed = PeerEndpoint("8.8.4.4", 6881)
    val packets = Channel<TorrentDatagram>(64)
    val socket = object : TorrentDatagramSocket {
      override val local = PeerEndpoint("0.0.0.0", 6881)
      override suspend fun send(remote: PeerEndpoint, bytes: ByteArray) {
        val query = DhtCodec.parse(bytes)
        val id = DhtIdentity.create(numericAddress(remote.host)!!)
        val body = mapOf("id" to id.toByteArray(), "nodes" to ByteArray(0))
        packets.send(TorrentDatagram(remote, DhtCodec.response(query.transaction, body, observed)))
      }
      override suspend fun receive(): TorrentDatagram = packets.receive()
      override fun close() { packets.close() }
    }
    val node = DhtNode(socket, backgroundScope, id = ByteArray(20).toByteString())
    node.start()
    try {
      node.bootstrap(listOf(PeerEndpoint("1.1.1.1", 6881), PeerEndpoint("9.9.9.9", 6881)))
      assertFalse(DhtIdentity.valid(DhtRoutingTable.restore(node.snapshot()).first,
        numericAddress(observed.host)!!))
      node.bootstrap(listOf(PeerEndpoint("8.8.8.8", 6881)))
      assertTrue(DhtIdentity.valid(DhtRoutingTable.restore(node.snapshot()).first,
        numericAddress(observed.host)!!))
    } finally {
      node.close()
    }
  }
}
