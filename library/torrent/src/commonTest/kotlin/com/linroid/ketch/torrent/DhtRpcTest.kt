package com.linroid.ketch.torrent

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okio.ByteString.Companion.toByteString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class DhtRpcTest {
  @Test
  fun ipv4_matchesTransactionAndEndpointAndTimesOutLostQueries() = runTest { exchange(false) }

  @Test
  fun ipv6_matchesTransactionAndEndpointAndTimesOutLostQueries() = runTest { exchange(true) }

  private suspend fun exchange(ipv6: Boolean) = withContext(Dispatchers.Default) {
    withTimeout(15_000) {
      coroutineScope {
        val network = createTorrentNetwork()
        val host = if (ipv6) "::1" else "127.0.0.1"
        val server = network.bindUdp(PeerEndpoint(host, 0))
        val client = network.bindUdp(PeerEndpoint(host, 0))
        val rpc = DhtRpc(client, this, onQuery = { _, _ -> null })
        rpc.start()
        try {
          val reference = async {
            val packet = server.receive()
            val request = DhtCodec.parse(packet.bytes)
            assertEquals("ping", request.query)
            val spoofer = network.bindUdp(PeerEndpoint(host, 0))
            try {
              spoofer.send(packet.remote, DhtCodec.error(request.transaction, 201))
            } finally {
              spoofer.close()
            }
            server.send(packet.remote, DhtCodec.error(byteArrayOf(9).toByteString(), 201))
            server.send(packet.remote, Bencode.encode(mapOf(
              "t" to request.transaction.toByteArray(), "y" to byteArrayOf(-1),
              "q" to "ping", "a" to mapOf("id" to ByteArray(20))
            )))
            server.send(packet.remote, DhtCodec.response(request.transaction,
              mapOf("id" to ByteArray(20) { 7 }), packet.remote))
          }
          val response = rpc.query(server.local, "ping", mapOf("id" to ByteArray(20)))
          assertEquals("r", assertNotNull(response).type)
          assertEquals(7.toByte(), response.body?.get("id")?.bytes?.first())
          reference.await()
          assertNull(rpc.query(server.local, "ping", mapOf("id" to ByteArray(20)), timeoutMs = 50))
        } finally {
          rpc.close()
          network.close()
        }
      }
    }
  }
}
