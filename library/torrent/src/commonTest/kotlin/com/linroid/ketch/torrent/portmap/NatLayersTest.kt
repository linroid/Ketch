package com.linroid.ketch.torrent.portmap

import com.linroid.ketch.torrent.net.LocalIpv4Network
import com.linroid.ketch.torrent.net.PeerEndpoint
import com.linroid.ketch.torrent.net.TorrentNetwork
import com.linroid.ketch.torrent.net.createTorrentNetwork
import com.linroid.ketch.torrent.net.numericAddress
import com.linroid.ketch.torrent.net.numericHost
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okio.Buffer
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * Port mapping through several NAT layers and PCP, against scripted gateways on loopback. One
 * NAT-PMP gateway stands in for a router and the NAT outside it: it answers its external
 * address from a list, so the first asks see a private address and later ones a public one.
 */
class NatLayersTest {
  private val loopback = byteArrayOf(127, 0, 0, 1)
  private val network127 = LocalIpv4Network(byteArrayOf(127, 0, 0, 2), 8)
  private val replyMs = 2_000L
  private val stopMs = 10_000L

  private fun ip(value: String): ByteArray = assertNotNull(numericAddress(value))

  private fun gateways(test: suspend CoroutineScope.(TorrentNetwork, CoroutineScope) -> Unit) =
    runTest {
      withContext(Dispatchers.Default) {
        withTimeout(30_000) {
          val network = createTorrentNetwork()
          val failures = MutableStateFlow(emptyList<Throwable>())
          val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default +
            CoroutineExceptionHandler { _, e -> failures.update { it + e } })
          try {
            coroutineScope { test(network, scope) }
          } finally {
            withContext(NonCancellable) { scope.coroutineContext.job.cancelAndJoin() }
            network.close()
          }
          failures.value.firstOrNull()?.let { throw it }
        }
      }
    }

  /**
   * A NAT-PMP gateway whose external address is the next of [addresses] at each ask (the last
   * once they run out), and which maps each internal port to itself plus [offset]. Records each
   * mapping as (opcode, internal port, lifetime).
   */
  private suspend fun natPmpGateway(
    network: TorrentNetwork,
    scope: CoroutineScope,
    addresses: List<String>,
    offset: Int,
    requests: MutableStateFlow<List<Triple<Int, Int, Int>>>,
  ): Int {
    val socket = network.bindUdp(PeerEndpoint("127.0.0.1", 0))
    var asked = 0
    scope.launch {
      try {
        while (true) {
          val datagram = socket.receive()
          val input = Buffer().write(datagram.bytes)
          input.readByte()
          val opcode = input.readByte().toInt()
          val reply = Buffer().writeByte(0).writeByte(128 + opcode).writeShort(0).writeInt(1)
          if (opcode == 0) {
            reply.write(ip(addresses[minOf(asked++, addresses.lastIndex)]))
          } else {
            input.readShort()
            val internal = input.readShort().toInt() and 0xffff
            val external = input.readShort().toInt() and 0xffff
            val lifetime = input.readInt()
            requests.update { it + Triple(opcode, internal, lifetime) }
            // UDP asks for the port TCP got, which it grants; TCP gets its port plus [offset].
            val granted = if (lifetime == 0) 0 else if (opcode == 1) external else internal + offset
            reply.writeShort(internal).writeShort(granted).writeInt(lifetime)
          }
          socket.send(datagram.remote, reply.readByteArray())
        }
      } finally {
        socket.close()
      }
    }
    return socket.local.port
  }

  /**
   * A PCP server granting each MAP its suggested port plus one at 20.0.113.9. Records each
   * request's client address, protocol, internal port and lifetime.
   */
  private suspend fun pcpServer(
    network: TorrentNetwork,
    scope: CoroutineScope,
    requests: MutableStateFlow<List<PcpRequest>>,
  ): Int {
    val socket = network.bindUdp(PeerEndpoint("127.0.0.1", 0))
    scope.launch {
      try {
        while (true) {
          val datagram = socket.receive()
          val input = Buffer().write(datagram.bytes)
          check(input.readByte().toInt() == 2 && input.readByte().toInt() == 1)
          input.readShort()
          val lifetime = input.readInt()
          val client = input.readByteArray(16).copyOfRange(12, 16)
          val nonce = input.readByteArray(12)
          val protocol = input.readByte().toInt() and 0xff
          input.skip(3)
          val internal = input.readShort().toInt() and 0xffff
          val suggested = input.readShort().toInt() and 0xffff
          requests.update { it + PcpRequest(numericHost(client), protocol, internal, lifetime) }
          val granted = if (lifetime == 0) 0 else suggested + 1
          val reply = Buffer()
            .writeByte(2).writeByte(0x81).writeByte(0).writeByte(0)
            .writeInt(lifetime).writeInt(1).write(ByteArray(12))
            .write(nonce).writeByte(protocol).write(ByteArray(3))
            .writeShort(internal).writeShort(granted)
            .write(ByteArray(10) + byteArrayOf(-1, -1) + ip("20.0.113.9"))
          socket.send(datagram.remote, reply.readByteArray())
        }
      } finally {
        socket.close()
      }
    }
    return socket.local.port
  }

  private data class PcpRequest(val client: String, val protocol: Int, val port: Int, val life: Int)

  @Test
  fun mapper_routerBehindAnotherNat_forwardsThePortThroughBoth() = gateways { network, scope ->
    val requests = MutableStateFlow(emptyList<Triple<Int, Int, Int>>())
    // The router's own external address is private; the gateway outside it has a public one.
    val port = natPmpGateway(network, scope, listOf("10.0.0.2", "20.0.113.7"), offset = 100,
      requests = requests)
    val layers = MutableStateFlow<PortMapLayers?>(null)
    val mapped = MutableStateFlow<Int?>(-1)
    val mapper = TorrentPortMapper(scope, tcpPort = 6881, udpPort = { 6881 },
      onMapped = { mapped.value = it }, onLayers = { layers.value = it },
      natPmp = NatPmpClient(network, port, retryMs = listOf(replyMs)),
      upnp = UpnpClient(network, ssdp = PeerEndpoint("127.0.0.1", 9), searchMs = 100),
      networks = { listOf(network127) },
      outerGateways = { address ->
        assertContentEquals(ip("10.0.0.2"), address)
        listOf(loopback)
      })
    mapper.start()
    // The outer NAT forwards the port the router got (6981) on to 7081, which peers are told.
    assertEquals(PortMapLayers(2, 7081, "20.0.113.7", public = true),
      layers.first { it != null })
    assertEquals(7081, mapped.value)
    mapper.stop(timeoutMs = stopMs)
    assertEquals(
      listOf(
        Triple(2, 6881, 3600), Triple(1, 6881, 3600),
        Triple(2, 6981, 3600), Triple(1, 6981, 3600),
        // Removed outermost first.
        Triple(2, 6981, 0), Triple(1, 6981, 0), Triple(2, 6881, 0), Triple(1, 6881, 0),
      ),
      requests.value,
    )
  }

  @Test
  fun mapper_nothingOutsideTheRouterAnswers_reportsItIsNotPublic() = gateways { network, scope ->
    val requests = MutableStateFlow(emptyList<Triple<Int, Int, Int>>())
    val port = natPmpGateway(network, scope, listOf("10.0.0.2"), offset = 0, requests = requests)
    val layers = MutableStateFlow<PortMapLayers?>(null)
    val mapper = TorrentPortMapper(scope, tcpPort = 6881, udpPort = { null },
      onMapped = {}, onLayers = { layers.value = it },
      natPmp = NatPmpClient(network, port, retryMs = listOf(replyMs)),
      upnp = UpnpClient(network, ssdp = PeerEndpoint("127.0.0.1", 9), searchMs = 100),
      networks = { listOf(network127) },
      outerGateways = { emptyList() })
    mapper.start()
    assertEquals(PortMapLayers(1, 6881, "10.0.0.2", public = false), layers.first { it != null })
    mapper.stop(timeoutMs = stopMs)
  }

  @Test
  fun mapper_pcpGateway_mapsForOurAddressOnItsNetwork() = gateways { network, scope ->
    val requests = MutableStateFlow(emptyList<PcpRequest>())
    val pcpPort = pcpServer(network, scope, requests)
    val silent = network.bindUdp(PeerEndpoint("127.0.0.1", 0))
    val layers = MutableStateFlow<PortMapLayers?>(null)
    val mapper = TorrentPortMapper(scope, tcpPort = 6881, udpPort = { 6881 },
      onMapped = {}, onLayers = { layers.value = it },
      // NAT-PMP goes unanswered, so PCP is asked.
      natPmp = NatPmpClient(network, silent.local.port, retryMs = listOf(100)),
      pcp = PcpClient(network, pcpPort, retryMs = listOf(replyMs)),
      upnp = UpnpClient(network, ssdp = PeerEndpoint("127.0.0.1", 9), searchMs = 100),
      networks = { listOf(network127) },
      outerGateways = { emptyList() })
    try {
      mapper.start()
      // TCP got 6882; UDP asked for 6882 too and got 6883, so it was removed again.
      assertEquals(PortMapLayers(1, 6882, "20.0.113.9", public = true),
        layers.first { it != null })
      mapper.stop(timeoutMs = stopMs)
      assertEquals(
        listOf(
          PcpRequest("127.0.0.2", 6, 6881, 3600),
          PcpRequest("127.0.0.2", 17, 6881, 3600),
          PcpRequest("127.0.0.2", 17, 6881, 0),
          PcpRequest("127.0.0.2", 6, 6881, 0),
        ),
        requests.value,
      )
    } finally {
      silent.close()
    }
  }

  @Test
  fun outerGatewayGuesses_tryTheEndsOfTheSubnetAndTheRangesFirstHost() {
    fun guesses(address: String) = outerGatewayGuesses(ip(address)).map(::numericHost)
    assertEquals(listOf("10.8.0.1", "10.8.0.254", "10.0.0.1"), guesses("10.8.0.6"))
    assertEquals(listOf("100.72.3.1", "100.72.3.254", "100.64.0.1"), guesses("100.72.3.9"))
    assertEquals(listOf("172.20.5.254", "172.20.0.1"), guesses("172.20.5.1"))
    assertEquals(listOf("192.168.1.1", "192.168.1.254"), guesses("192.168.1.20"))
  }
}
