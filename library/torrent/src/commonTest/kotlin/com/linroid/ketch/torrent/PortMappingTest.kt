package com.linroid.ketch.torrent

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okio.Buffer
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** NAT-PMP and UPnP against scripted gateways on loopback. */
class PortMappingTest {
  private val loopback = byteArrayOf(127, 0, 0, 1)
  private val loopbackNetwork = LocalIpv4Network(loopback, 8)

  private fun ip(value: String): ByteArray = assertNotNull(numericAddress(value))

  private fun gateways(test: suspend CoroutineScope.(TorrentNetwork, CoroutineScope) -> Unit) =
    runTest {
      withContext(Dispatchers.Default) {
        withTimeout(30_000) {
          val network = createTorrentNetwork()
          val scope = CoroutineScope(Dispatchers.Default)
          try {
            coroutineScope { test(network, scope) }
          } finally {
            scope.cancel()
            network.close()
          }
        }
      }
    }

  /**
   * A NAT-PMP gateway: external address 203.0.113.7, mappings to the internal port plus
   * [offset], or the result code [refuse] when set. Records each request's opcode.
   */
  private suspend fun natPmpGateway(
    network: TorrentNetwork,
    scope: CoroutineScope,
    offset: Int = 0,
    /** Added to UDP mappings only, as a gateway that gives UDP another port. */
    udpOffset: Int = 0,
    refuse: Int = 0,
    requests: MutableList<Pair<Int, Int>> = mutableListOf(),
  ): Int {
    val socket = network.bindUdp(PeerEndpoint("127.0.0.1", 0))
    scope.launch {
      try {
        while (true) {
          val datagram = socket.receive()
          val input = Buffer().write(datagram.bytes)
          input.readByte()
          val opcode = input.readByte().toInt()
          val reply = Buffer().writeByte(0).writeByte(128 + opcode).writeShort(refuse).writeInt(1)
          if (opcode == 0) {
            reply.write(byteArrayOf(203.toByte(), 0, 113, 7))
          } else {
            input.readShort()
            val internal = input.readShort().toInt() and 0xffff
            val external = input.readShort().toInt() and 0xffff
            val lifetime = input.readInt()
            requests += opcode to lifetime
            reply.writeShort(internal)
              .writeShort(if (lifetime == 0) 0 else {
                // A port asked for that is not the internal one is granted, as a free port is.
                (external.takeIf { it != 0 && it != internal } ?: (internal + offset)) +
                  if (opcode == 1) udpOffset else 0
              })
              .writeInt(lifetime)
          }
          socket.send(datagram.remote, reply.readByteArray())
        }
      } finally {
        socket.close()
      }
    }
    return socket.local.port
  }

  @Test
  fun natPmp_externalAddressAndMappings() = gateways { network, scope ->
    val requests = mutableListOf<Pair<Int, Int>>()
    val port = natPmpGateway(network, scope, offset = 1000, requests = requests)
    val client = NatPmpClient(network, port)
    val (gateway, external) = assertNotNull(client.discover(listOf(loopback)))
    assertContentEquals(loopback, gateway)
    assertContentEquals(byteArrayOf(203.toByte(), 0, 113, 7), external)
    val tcp = assertNotNull(client.map(gateway, MappedProtocol.TCP, 6881, 6881, 3600))
    // The gateway picked another external port.
    assertEquals(PortMapping(MappedProtocol.TCP, 6881, 7881, 3600), tcp)
    assertNotNull(client.map(gateway, MappedProtocol.UDP, 6881, 7881, 3600))
    assertEquals(listOf(2 to 3600, 1 to 3600), requests)
  }

  @Test
  fun natPmp_refusalOrSilence_isNull() = gateways { network, scope ->
    val port = natPmpGateway(network, scope, refuse = 2)
    val client = NatPmpClient(network, port, retryMs = listOf(100))
    assertNull(client.map(loopback, MappedProtocol.TCP, 6881, 6881, 3600))
    // A port nobody answers on.
    val silent = network.bindUdp(PeerEndpoint("127.0.0.1", 0))
    try {
      assertNull(NatPmpClient(network, silent.local.port, retryMs = listOf(100))
        .externalAddress(loopback))
    } finally {
      silent.close()
    }
  }

  /** What the scripted UPnP gateway was asked, and how it answers. */
  private class Igd {
    val actions = MutableStateFlow(emptyList<String>())
    /** External ports another device holds: AddPortMapping answers 718 for them. */
    val taken = mutableSetOf<Int>()
    /** External ports held for UDP only. */
    val takenUdp = mutableSetOf<Int>()
  }

  /** An SSDP responder pointing at an HTTP server with an IGD description and SOAP control. */
  private suspend fun upnpGateway(network: TorrentNetwork, scope: CoroutineScope, igd: Igd): Int {
    val http = network.listen(PeerEndpoint("127.0.0.1", 0))
    val description = """<?xml version="1.0"?><root><device><deviceList><device><serviceList>
      <service><serviceType>urn:schemas-upnp-org:service:Layer3Forwarding:1</serviceType>
      <controlURL>/l3f</controlURL></service>
      <service><serviceType>urn:schemas-upnp-org:service:WANIPConnection:1</serviceType>
      <controlURL>/ctl/IPConn</controlURL></service>
      </serviceList></device></deviceList></device></root>"""
    scope.launch {
      try {
        while (true) {
          val connection = http.accept()
          launch {
            try {
              val (head, body) = readRequest(connection)
              val response = if (head.startsWith("GET /rootDesc.xml")) {
                200 to description
              } else {
                val action = Regex("#(\\w+)\"").find(head)?.groupValues?.get(1) ?: "?"
                val port = UpnpClient.tag(body, "NewExternalPort")?.toInt()
                igd.actions.update { it + "$action ${UpnpClient.tag(body, "NewProtocol")} $port" }
                val udp = UpnpClient.tag(body, "NewProtocol") == "UDP"
                val taken = port in igd.taken || udp && port in igd.takenUdp
                if (action == "AddPortMapping" && taken) {
                  500 to "<s:Envelope><s:Body><s:Fault><detail><UPnPError>" +
                    "<errorCode>718</errorCode><errorDescription>ConflictInMappingEntry" +
                    "</errorDescription></UPnPError></detail></s:Fault></s:Body></s:Envelope>"
                } else {
                  200 to "<s:Envelope><s:Body><u:${action}Response>" +
                    "<NewExternalIPAddress>203.0.113.7</NewExternalIPAddress>" +
                    "</u:${action}Response></s:Body></s:Envelope>"
                }
              }
              val bytes = response.second.encodeToByteArray()
              connection.write(("HTTP/1.1 ${response.first} OK\r\nContent-Length: ${bytes.size}" +
                "\r\n\r\n").encodeToByteArray() + bytes)
            } finally {
              connection.close()
            }
          }
        }
      } finally {
        http.close()
      }
    }
    val ssdp = network.bindUdp(PeerEndpoint("127.0.0.1", 0))
    scope.launch {
      try {
        while (true) {
          val search = ssdp.receive()
          ssdp.send(search.remote, ("HTTP/1.1 200 OK\r\nST: upnp:rootdevice\r\n" +
            "LOCATION: http://127.0.0.1:${http.local.port}/rootDesc.xml\r\n\r\n")
            .encodeToByteArray())
        }
      } finally {
        ssdp.close()
      }
    }
    return ssdp.local.port
  }

  /** The request line with headers, and the body, of one HTTP request. */
  private suspend fun readRequest(connection: TorrentConnection): Pair<String, String> {
    val head = Buffer()
    while (!head.snapshot().utf8().endsWith("\r\n\r\n")) head.write(connection.readExactly(1))
    val text = head.readUtf8()
    val length = UpnpClient.header(text, "Content-Length")?.toInt() ?: 0
    return text to connection.readExactly(length).decodeToString()
  }

  @Test
  fun upnp_discoversTheWanServiceAndMapsPorts() = gateways { network, scope ->
    val igd = Igd()
    val ssdp = upnpGateway(network, scope, igd)
    val client = UpnpClient(network, ssdp = PeerEndpoint("127.0.0.1", ssdp), searchMs = 500)
    val gateway = assertNotNull(client.discover(listOf(loopbackNetwork)))
    assertEquals("/ctl/IPConn", gateway.controlPath)
    assertEquals("urn:schemas-upnp-org:service:WANIPConnection:1", gateway.serviceType)
    assertEquals("203.0.113.7", client.externalAddress(gateway))
    client.addPortMapping(gateway, MappedProtocol.TCP, 6881, 6881, "127.0.0.1", 3600)
    igd.taken += 6882
    val conflict = assertFailsWith<UpnpException> {
      client.addPortMapping(gateway, MappedProtocol.UDP, 6882, 6881, "127.0.0.1", 3600)
    }
    assertEquals(718, conflict.code)
    client.deletePortMapping(gateway, MappedProtocol.TCP, 6881)
    assertEquals(listOf("GetExternalIPAddress null null", "AddPortMapping TCP 6881",
      "AddPortMapping UDP 6882", "DeletePortMapping TCP 6881"), igd.actions.value)
  }

  @Test
  fun upnp_gatewayOffOurNetworks_isIgnored() = gateways { network, scope ->
    val ssdp = upnpGateway(network, scope, Igd())
    val client = UpnpClient(network, ssdp = PeerEndpoint("127.0.0.1", ssdp), searchMs = 500)
    assertNull(client.discover(listOf(LocalIpv4Network(ip("192.168.1.2"), 24))))
  }

  @Test
  fun mapper_fallsBackToUpnp_skipsATakenPort_announcesAndRemoves() = gateways { network, scope ->
    val igd = Igd().apply { taken += 6881 }
    val ssdp = upnpGateway(network, scope, igd)
    // NAT-PMP is silent: nobody listens where it asks.
    val silent = network.bindUdp(PeerEndpoint("127.0.0.1", 0))
    val mapped = MutableStateFlow<Int?>(-1)
    val mapper = TorrentPortMapper(scope, tcpPort = 6881, udpPort = { 6881 },
      onMapped = { mapped.value = it },
      natPmp = NatPmpClient(network, silent.local.port, retryMs = listOf(50)),
      upnp = UpnpClient(network, ssdp = PeerEndpoint("127.0.0.1", ssdp), searchMs = 300),
      networks = { listOf(loopbackNetwork) })
    try {
      mapper.start()
      // 6881 is taken, so the next candidate, for TCP and UDP alike.
      assertEquals(6888, mapped.first { it != -1 })
      mapper.stop()
      val actions = igd.actions.value
      assertEquals(listOf("AddPortMapping TCP 6881", "AddPortMapping TCP 6888",
        "AddPortMapping UDP 6888", "DeletePortMapping TCP 6888", "DeletePortMapping UDP 6888"),
        actions)
    } finally {
      silent.close()
    }
  }

  @Test
  fun mapper_natPmp_mapsBothProtocolsToOneExternalPort_andRemovesThem() =
    gateways { network, scope ->
      val requests = mutableListOf<Pair<Int, Int>>()
      val port = natPmpGateway(network, scope, offset = 100, requests = requests)
      val mapped = MutableStateFlow<Int?>(-1)
      val mapper = TorrentPortMapper(scope, tcpPort = 6881, udpPort = { 6881 },
        onMapped = { mapped.value = it },
        natPmp = NatPmpClient(network, port, retryMs = listOf(200)),
        upnp = UpnpClient(network, ssdp = PeerEndpoint("127.0.0.1", 9), searchMs = 100),
        // The gateway guesses for 127.0.0.2 include 127.0.0.1, where the gateway answers.
        networks = { listOf(LocalIpv4Network(ip("127.0.0.2"), 8)) })
      mapper.start()
      // The gateway gave TCP another port; UDP asked for that same port.
      assertEquals(6981, mapped.first { it != -1 })
      mapper.stop()
      assertEquals(listOf(2 to 3600, 1 to 3600, 2 to 0, 1 to 0), requests)
    }

  @Test
  fun mapper_upnpRefusingUdp_removesTheTcpMappingBeforeTheNextPort() =
    gateways { network, scope ->
      val igd = Igd().apply { takenUdp += 6881 }
      val ssdp = upnpGateway(network, scope, igd)
      val silent = network.bindUdp(PeerEndpoint("127.0.0.1", 0))
      val mapped = MutableStateFlow<Int?>(-1)
      val mapper = TorrentPortMapper(scope, tcpPort = 6881, udpPort = { 6881 },
        onMapped = { mapped.value = it },
        natPmp = NatPmpClient(network, silent.local.port, retryMs = listOf(50)),
        upnp = UpnpClient(network, ssdp = PeerEndpoint("127.0.0.1", ssdp), searchMs = 300),
        networks = { listOf(loopbackNetwork) })
      try {
        mapper.start()
        assertEquals(6888, mapped.first { it != -1 })
        mapper.stop()
        assertEquals(listOf("AddPortMapping TCP 6881", "AddPortMapping UDP 6881",
          "DeletePortMapping TCP 6881", "AddPortMapping TCP 6888", "AddPortMapping UDP 6888",
          "DeletePortMapping TCP 6888", "DeletePortMapping UDP 6888"), igd.actions.value)
      } finally {
        silent.close()
      }
    }

  @Test
  fun mapper_natPmpUdpOnAnotherPort_isRemoved() = gateways { network, scope ->
    val requests = mutableListOf<Pair<Int, Int>>()
    val port = natPmpGateway(network, scope, udpOffset = 5, requests = requests)
    val mapped = MutableStateFlow<Int?>(-1)
    val mapper = TorrentPortMapper(scope, tcpPort = 6881, udpPort = { 6881 },
      onMapped = { mapped.value = it },
      natPmp = NatPmpClient(network, port, retryMs = listOf(200)),
      upnp = UpnpClient(network, ssdp = PeerEndpoint("127.0.0.1", 9), searchMs = 100),
      networks = { listOf(LocalIpv4Network(ip("127.0.0.2"), 8)) })
    mapper.start()
    assertEquals(6881, mapped.first { it != -1 })
    mapper.stop()
    // TCP, UDP on 6886 then removed; at stop only TCP is left to remove.
    assertEquals(listOf(2 to 3600, 1 to 3600, 1 to 0, 2 to 0), requests)
  }

  @Test
  fun mapper_udpBoundLater_isMappedWithoutWaitingForRenewal() = gateways { network, scope ->
    val requests = mutableListOf<Pair<Int, Int>>()
    val port = natPmpGateway(network, scope, requests = requests)
    var udp: Int? = null
    val mapper = TorrentPortMapper(scope, tcpPort = 6881, udpPort = { udp },
      onMapped = {},
      natPmp = NatPmpClient(network, port, retryMs = listOf(200)),
      upnp = UpnpClient(network, ssdp = PeerEndpoint("127.0.0.1", 9), searchMs = 100),
      networks = { listOf(LocalIpv4Network(ip("127.0.0.2"), 8)) },
      udpCheckMs = 100)
    mapper.start()
    withTimeout(5_000) { while (requests.isEmpty()) delay(20) }
    assertEquals(listOf(2 to 3600), requests.toList())
    // DHT binds its socket after the first pass.
    udp = 6881
    withTimeout(5_000) { while (requests.none { it.first == 1 }) delay(20) }
    mapper.stop()
  }

  @Test
  fun mapper_withoutAGateway_reportsNone() = gateways { network, scope ->
    val silent = network.bindUdp(PeerEndpoint("127.0.0.1", 0))
    val reported = CompletableDeferred<Int?>()
    val mapper = TorrentPortMapper(scope, tcpPort = 6881, udpPort = { null },
      onMapped = { reported.complete(it) },
      natPmp = NatPmpClient(network, silent.local.port, retryMs = listOf(50)),
      upnp = UpnpClient(network, ssdp = PeerEndpoint("127.0.0.1", silent.local.port),
        searchMs = 200),
      networks = { listOf(loopbackNetwork) })
    try {
      mapper.start()
      assertNull(reported.await())
      mapper.stop()
    } finally {
      silent.close()
    }
  }

  @Test
  fun localNetwork_containsAndGatewayGuesses() {
    val home = LocalIpv4Network(ip("192.168.31.11"), 24)
    assertTrue(home.contains(ip("192.168.31.1")))
    assertEquals(false, home.contains(ip("192.168.32.1")))
    assertEquals(listOf("192.168.31.1", "192.168.31.254"),
      home.gatewayGuesses().map(::numericHost))
    assertTrue(privateIpv4(byteArrayOf(100, 64, 0, 1)))
    assertEquals(false, privateIpv4(byteArrayOf(8, 8, 8, 8)))
  }
}
