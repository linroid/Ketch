package com.linroid.ketch.torrent.dht

import com.linroid.ketch.torrent.net.PeerEndpoint
import com.linroid.ketch.torrent.net.TorrentDatagram
import com.linroid.ketch.torrent.net.TorrentDatagramSocket
import com.linroid.ketch.torrent.net.numericAddress
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class DhtBootstrapRouterTest {
  @Test
  fun bootstrap_routerAtReservedAddress_introducesNodesWithoutJoiningTheTable() = runTest {
    // Some networks resolve router names into reserved ranges such as 198.18.0.0/15.
    val router = PeerEndpoint("198.18.12.115", 6881)
    val introduced = PeerEndpoint("8.8.8.8", 6881)
    val introducedId = DhtIdentity.create(numericAddress(introduced.host)!!)
    val packets = Channel<TorrentDatagram>(64)
    val asked = mutableListOf<Pair<PeerEndpoint, String?>>()
    val socket = object : TorrentDatagramSocket {
      override val local = PeerEndpoint("0.0.0.0", 6881)
      override suspend fun send(remote: PeerEndpoint, bytes: ByteArray) {
        val query = DhtCodec.parse(bytes)
        asked += remote to query.query
        val body = if (remote == router) {
          mapOf("id" to ByteArray(20), "nodes" to DhtCodec.compactNodes(
            listOf(DhtContact(introducedId, introduced))))
        } else {
          mapOf("id" to introducedId.toByteArray(), "nodes" to ByteArray(0))
        }
        packets.send(TorrentDatagram(remote, DhtCodec.response(query.transaction, body, remote)))
      }
      override suspend fun receive(): TorrentDatagram = packets.receive()
      override fun close() { packets.close() }
    }
    val node = DhtNode(socket, backgroundScope)
    node.start()
    try {
      node.bootstrap(emptyList(), routers = listOf(router))

      assertEquals(1, node.contactCount())
      assertEquals(listOf(introduced),
        DhtRoutingTable.restore(node.snapshot()).second.map { it.endpoint })
      assertEquals(listOf<Pair<PeerEndpoint, String?>>(router to "find_node"),
        asked.filter { it.first == router })
    } finally {
      node.close()
    }
  }

  @Test
  fun bootstrap_manyRouters_asksEveryOne() = runTest {
    val routers = (1..20).map { PeerEndpoint("198.18.0.$it", 6881) }
    val introduced = PeerEndpoint("8.8.8.8", 6881)
    val introducedId = DhtIdentity.create(numericAddress(introduced.host)!!)
    val packets = Channel<TorrentDatagram>(64)
    val socket = object : TorrentDatagramSocket {
      override val local = PeerEndpoint("0.0.0.0", 6881)
      override suspend fun send(remote: PeerEndpoint, bytes: ByteArray) {
        val query = DhtCodec.parse(bytes)
        // Only the last router answers.
        val body = when (remote) {
          routers.last() -> mapOf("id" to ByteArray(20), "nodes" to DhtCodec.compactNodes(
            listOf(DhtContact(introducedId, introduced))))
          introduced -> mapOf("id" to introducedId.toByteArray(), "nodes" to ByteArray(0))
          else -> return
        }
        packets.send(TorrentDatagram(remote, DhtCodec.response(query.transaction, body, remote)))
      }
      override suspend fun receive(): TorrentDatagram = packets.receive()
      override fun close() { packets.close() }
    }
    val node = DhtNode(socket, backgroundScope, queryTimeoutMs = 50)
    node.start()
    try {
      node.bootstrap(emptyList(), routers = routers)
      assertEquals(1, node.contactCount())
    } finally {
      node.close()
    }
  }

  @Test
  fun introduce_answeringPeerNode_joinsTheTable_andReservedOnesAreNotAsked() = runTest {
    val peerNode = PeerEndpoint("8.8.8.8", 6881)
    val peerId = DhtIdentity.create(numericAddress(peerNode.host)!!)
    val packets = Channel<TorrentDatagram>(64)
    val asked = mutableListOf<PeerEndpoint>()
    val socket = object : TorrentDatagramSocket {
      override val local = PeerEndpoint("0.0.0.0", 6881)
      override suspend fun send(remote: PeerEndpoint, bytes: ByteArray) {
        asked += remote
        val query = DhtCodec.parse(bytes)
        val body = mapOf("id" to peerId.toByteArray())
        packets.send(TorrentDatagram(remote, DhtCodec.response(query.transaction, body, remote)))
      }
      override suspend fun receive(): TorrentDatagram = packets.receive()
      override fun close() { packets.close() }
    }
    val node = DhtNode(socket, backgroundScope)
    node.start()
    try {
      assertEquals(false, node.introduce(PeerEndpoint("192.168.1.2", 6881)))
      assertEquals(true, node.introduce(peerNode))
      assertEquals(1, node.contactCount())
      assertEquals(listOf(peerNode), asked)
    } finally {
      node.close()
    }
  }

  @Test
  fun bootstrap_savedNodeAtReservedAddress_isNotAsked() = runTest {
    val asked = mutableListOf<PeerEndpoint>()
    val socket = object : TorrentDatagramSocket {
      override val local = PeerEndpoint("0.0.0.0", 6881)
      override suspend fun send(remote: PeerEndpoint, bytes: ByteArray) { asked += remote }
      override suspend fun receive(): TorrentDatagram = Channel<TorrentDatagram>().receive()
      override fun close() {}
    }
    val node = DhtNode(socket, backgroundScope, queryTimeoutMs = 10)
    node.start()
    try {
      node.bootstrap(listOf(PeerEndpoint("198.18.0.1", 6881)))
      assertEquals(emptyList(), asked)
    } finally {
      node.close()
    }
  }
}
