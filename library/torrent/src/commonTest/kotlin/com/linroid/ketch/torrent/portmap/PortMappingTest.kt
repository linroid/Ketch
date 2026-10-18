package com.linroid.ketch.torrent.portmap

import com.linroid.ketch.torrent.net.LocalIpv4Network
import com.linroid.ketch.torrent.net.PeerEndpoint
import com.linroid.ketch.torrent.net.TorrentConnection
import com.linroid.ketch.torrent.net.TorrentNetwork
import com.linroid.ketch.torrent.net.createTorrentNetwork
import com.linroid.ketch.torrent.net.numericAddress
import com.linroid.ketch.torrent.net.numericHost
import com.linroid.ketch.torrent.net.privateIpv4
import com.linroid.ketch.torrent.webseed.drain
import com.linroid.ketch.torrent.webseed.withInfoLog
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.job
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

  /**
   * One reply window, long enough for a loaded test machine: a request sent again would be
   * recorded twice, and a gateway missed on the first pass is only asked again after minutes.
   * It stays well under the 5 s waits, as the silent gateway guess takes the whole window.
   */
  private val replyMs = 2_000L

  /** Room for every removal to wait out its whole [replyMs] window. */
  private val stopMs = 10_000L

  private fun ip(value: String): ByteArray = assertNotNull(numericAddress(value))

  /**
   * Runs [test] with a network and a scope for the gateways and mappers it starts. A failure in
   * that scope fails the test rather than reaching the global handler, which fails whichever
   * test runs then, or ends the process on Native. The scope's coroutines have finished before
   * the network closes: a socket closed under a running accept or receive throws rather than
   * cancels, even once the coroutine was asked to stop.
   */
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

  @Test
  fun natPmp_externalAddressAndMappings() = gateways { network, scope ->
    val requests = MutableStateFlow(emptyList<Pair<Int, Int>>())
    val port = natPmpGateway(network, scope, offset = 1000, requests = requests)
    val client = NatPmpClient(network, port)
    val (gateway, external) = assertNotNull(client.discover(listOf(loopback)))
    assertContentEquals(loopback, gateway)
    assertContentEquals(byteArrayOf(203.toByte(), 0, 113, 7), external)
    val tcp = assertNotNull(client.map(gateway, MappedProtocol.TCP, 6881, 6881, 3600))
    // The gateway picked another external port.
    assertEquals(PortMapping(MappedProtocol.TCP, 6881, 7881, 3600), tcp)
    assertNotNull(client.map(gateway, MappedProtocol.UDP, 6881, 7881, 3600))
    assertEquals(listOf(2 to 3600, 1 to 3600), requests.value)
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
    /** Refuses leases other than permanent ones (725), as some routers do. */
    var permanentOnly = false
    /** The fault DeletePortMapping answers, when set. */
    var deleteFault: Int? = null
  }

  /** A SOAP fault with UPnP error [code]. */
  private fun upnpFault(code: Int) = 500 to "<s:Envelope><s:Body><s:Fault><detail><UPnPError>" +
    "<errorCode>$code</errorCode><errorDescription>Refused</errorDescription></UPnPError>" +
    "</detail></s:Fault></s:Body></s:Envelope>"

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
                val lease = UpnpClient.tag(body, "NewLeaseDuration")
                val deleteFault = igd.deleteFault
                if (action == "AddPortMapping" && taken) {
                  upnpFault(718)
                } else if (action == "AddPortMapping" && igd.permanentOnly && lease != "0") {
                  upnpFault(725)
                } else if (action == "DeletePortMapping" && deleteFault != null) {
                  upnpFault(deleteFault)
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
    val address = MutableStateFlow<String?>(null)
    val mapper = TorrentPortMapper(scope, tcpPort = 6881, udpPort = { 6881 },
      onMapped = { mapped.value = it }, onExternalAddress = { address.value = it },
      natPmp = NatPmpClient(network, silent.local.port, retryMs = listOf(50)),
      upnp = UpnpClient(network, ssdp = PeerEndpoint("127.0.0.1", ssdp), searchMs = 300),
      networks = { listOf(loopbackNetwork) })
    try {
      mapper.start()
      // 6881 is taken, so the next candidate, for TCP and UDP alike.
      assertEquals(6888, mapped.first { it != -1 })
      // Peers at the router's public address are this engine; the engine leaves them out.
      assertEquals("203.0.113.7", address.value)
      mapper.stop(timeoutMs = stopMs)
      val actions = igd.actions.value
      assertEquals(listOf("AddPortMapping TCP 6881", "AddPortMapping TCP 6888",
        "AddPortMapping UDP 6888", "GetExternalIPAddress null null"), actions.take(4))
      // Removed side by side, in no particular order.
      assertEquals(setOf("DeletePortMapping TCP 6888", "DeletePortMapping UDP 6888"),
        actions.drop(4).toSet())
    } finally {
      silent.close()
    }
  }

  @Test
  fun mapper_natPmp_mapsBothProtocolsToOneExternalPort_andRemovesThem() =
    gateways { network, scope ->
      val requests = MutableStateFlow(emptyList<Pair<Int, Int>>())
      val port = natPmpGateway(network, scope, offset = 100, requests = requests)
      val mapped = MutableStateFlow<Int?>(-1)
      val address = MutableStateFlow<String?>(null)
      val mapper = TorrentPortMapper(scope, tcpPort = 6881, udpPort = { 6881 },
        onMapped = { mapped.value = it }, onExternalAddress = { address.value = it },
        natPmp = NatPmpClient(network, port, retryMs = listOf(replyMs)),
        upnp = UpnpClient(network, ssdp = PeerEndpoint("127.0.0.1", 9), searchMs = 100),
        // The gateway guesses for 127.0.0.2 include 127.0.0.1, where the gateway answers.
        networks = { listOf(LocalIpv4Network(ip("127.0.0.2"), 8)) })
      mapper.start()
      // The gateway gave TCP another port; UDP asked for that same port.
      assertEquals(6981, mapped.first { it != -1 })
      assertEquals("203.0.113.7", address.value)
      mapper.stop(timeoutMs = stopMs)
      assertEquals(listOf(2 to 3600, 1 to 3600), requests.value.take(2))
      // Removed side by side, in no particular order.
      assertEquals(setOf(2 to 0, 1 to 0), requests.value.drop(2).toSet())
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
        mapper.stop(timeoutMs = stopMs)
        val actions = igd.actions.value
        assertEquals(listOf("AddPortMapping TCP 6881", "AddPortMapping UDP 6881",
          "DeletePortMapping TCP 6881", "AddPortMapping TCP 6888", "AddPortMapping UDP 6888",
          "GetExternalIPAddress null null"), actions.take(6))
        assertEquals(setOf("DeletePortMapping TCP 6888", "DeletePortMapping UDP 6888"),
          actions.drop(6).toSet())
      } finally {
        silent.close()
      }
    }

  @Test
  fun mapper_upnpRenewalRefused_stillRemovesTheHeldMapping() = gateways { network, scope ->
    val igd = Igd()
    val ssdp = upnpGateway(network, scope, igd)
    val silent = network.bindUdp(PeerEndpoint("127.0.0.1", 0))
    val mapped = MutableStateFlow<Int?>(-1)
    var udp: Int? = null
    val mapper = TorrentPortMapper(scope, tcpPort = 6881, udpPort = { udp },
      onMapped = { mapped.value = it },
      natPmp = NatPmpClient(network, silent.local.port, retryMs = listOf(50)),
      upnp = UpnpClient(network, ssdp = PeerEndpoint("127.0.0.1", ssdp), searchMs = 300),
      networks = { listOf(loopbackNetwork) },
      udpCheckMs = 100)
    try {
      mapper.start()
      assertEquals(6881, mapped.first { it != -1 })
      // The renewal DHT's port starts finds 6881 refused, as when another device took it.
      igd.taken += 6881
      udp = 6881
      assertEquals(6888, mapped.first { it == 6888 })
      mapper.stop(timeoutMs = stopMs)
      // The router still held TCP 6881, so it is removed rather than left to its lease.
      assertTrue("DeletePortMapping TCP 6881" in igd.actions.value, "${igd.actions.value}")
    } finally {
      silent.close()
    }
  }

  @Test
  fun mapper_natPmpUdpOnAnotherPort_isRemoved() = gateways { network, scope ->
    val requests = MutableStateFlow(emptyList<Pair<Int, Int>>())
    val port = natPmpGateway(network, scope, udpOffset = 5, requests = requests)
    val mapped = MutableStateFlow<Int?>(-1)
    val mapper = TorrentPortMapper(scope, tcpPort = 6881, udpPort = { 6881 },
      onMapped = { mapped.value = it },
      natPmp = NatPmpClient(network, port, retryMs = listOf(replyMs)),
      upnp = UpnpClient(network, ssdp = PeerEndpoint("127.0.0.1", 9), searchMs = 100),
      networks = { listOf(LocalIpv4Network(ip("127.0.0.2"), 8)) })
    mapper.start()
    assertEquals(6881, mapped.first { it != -1 })
    mapper.stop(timeoutMs = stopMs)
    // TCP, UDP on 6886 then removed; at stop only TCP is left to remove.
    assertEquals(listOf(2 to 3600, 1 to 3600, 1 to 0, 2 to 0), requests.value)
  }

  @Test
  fun mapper_stoppedBeforeTheGatewayReplies_removesWhatItAskedFor() = gateways { network, scope ->
    val requests = MutableStateFlow(emptyList<Pair<Int, Int>>())
    val port = natPmpGateway(network, scope, silentGrants = true, requests = requests)
    val mapper = TorrentPortMapper(scope, tcpPort = 6881, udpPort = { 6881 },
      onMapped = {},
      natPmp = NatPmpClient(network, port, retryMs = listOf(replyMs)),
      upnp = UpnpClient(network, ssdp = PeerEndpoint("127.0.0.1", 9), searchMs = 100),
      networks = { listOf(LocalIpv4Network(ip("127.0.0.2"), 8)) })
    mapper.start()
    // The gateway mapped TCP; its reply never comes before the engine stops.
    requests.first { it.isNotEmpty() }
    mapper.stop(timeoutMs = stopMs)
    assertEquals(listOf(2 to 3600, 2 to 0), requests.value)
  }

  /** Maps TCP port 6881 alone through the NAT-PMP gateway on [port], and returns its mapper. */
  private suspend fun mapThroughNatPmp(network: TorrentNetwork, scope: CoroutineScope, port: Int) =
    CompletableDeferred<Int?>().let { mapped ->
      TorrentPortMapper(scope, tcpPort = 6881, udpPort = { null },
        onMapped = { mapped.complete(it) },
        natPmp = NatPmpClient(network, port, retryMs = listOf(replyMs)),
        upnp = UpnpClient(network, ssdp = PeerEndpoint("127.0.0.1", 9), searchMs = 100),
        networks = { listOf(LocalIpv4Network(ip("127.0.0.2"), 8)) })
        .also {
          it.start()
          assertEquals(6881, mapped.await())
        }
    }

  /**
   * Maps TCP port 6881 alone through the UPnP gateway answering SSDP on [ssdp], NAT-PMP being
   * silent on [silent], and returns its mapper.
   */
  private suspend fun mapThroughUpnp(
    network: TorrentNetwork,
    scope: CoroutineScope,
    ssdp: Int,
    silent: Int,
  ) = CompletableDeferred<Int?>().let { mapped ->
    TorrentPortMapper(scope, tcpPort = 6881, udpPort = { null },
      onMapped = { mapped.complete(it) },
      natPmp = NatPmpClient(network, silent, retryMs = listOf(50)),
      upnp = UpnpClient(network, ssdp = PeerEndpoint("127.0.0.1", ssdp), searchMs = 300),
      networks = { listOf(loopbackNetwork) })
      .also {
        it.start()
        assertEquals(6881, mapped.await())
      }
  }

  /** The lines about removing port mappings among [lines] so far. */
  private fun removals(lines: Channel<String>) =
    lines.drain().filter { "mapping of port" in it }

  @Test
  fun mapper_stop_logsOnceThatTheMappingIsRemoved() = gateways { network, scope ->
    val port = natPmpGateway(network, scope)
    withInfoLog { lines ->
      val mapper = mapThroughNatPmp(network, scope, port)
      mapper.stop(timeoutMs = stopMs)
      // Removed and forgotten: another stop has nothing to remove or tell.
      mapper.stop(timeoutMs = stopMs)
      assertEquals(
        listOf("[TorrentEngine] Removed the mapping of port 6881 through 1 NAT layer(s)"),
        removals(lines),
      )
    }
  }

  @Test
  fun mapper_stop_unconfirmedRemoval_saysTheGatewayForwardsItUntilItsLeaseEnds() =
    gateways { network, scope ->
      val port = natPmpGateway(network, scope, silentRemovals = true)
      withInfoLog { lines ->
        mapThroughNatPmp(network, scope, port).stop(timeoutMs = stopMs)
        assertEquals(listOf("[TorrentEngine] Removing the mapping of port 6881: 0 of 1 NAT " +
          "layer(s) confirmed it; the others may still forward the port until their lease ends"),
          removals(lines))
      }
    }

  @Test
  fun mapper_stop_timedOut_saysWhatTheGatewaysStillForward() = gateways { network, scope ->
    val requests = MutableStateFlow(emptyList<Pair<Int, Int>>())
    val port = natPmpGateway(network, scope, silentRemovals = true, requests = requests)
    withInfoLog { lines ->
      mapThroughNatPmp(network, scope, port).stop(timeoutMs = 300)
      assertEquals(listOf("[TorrentEngine] Removing the mapping of port 6881 timed out after " +
        "300ms: 0 of 1 NAT layer(s) confirmed it; the others may still forward the port until " +
        "their lease ends"), removals(lines))
      assertEquals(listOf(2 to 3600, 2 to 0), requests.first { it.size == 2 })
    }
  }

  @Test
  fun mapper_stop_permanentLeaseLeft_saysTheRouterForwardsItUntilItDropsIt() =
    gateways { network, scope ->
      val igd = Igd().apply {
        permanentOnly = true
        deleteFault = 501
      }
      val ssdp = upnpGateway(network, scope, igd)
      val silent = network.bindUdp(PeerEndpoint("127.0.0.1", 0))
      try {
        withInfoLog { lines ->
          mapThroughUpnp(network, scope, ssdp, silent.local.port).stop(timeoutMs = stopMs)
          // A lease of 0 never ends.
          assertEquals(listOf("[TorrentEngine] Removing the mapping of port 6881: 0 of 1 NAT " +
            "layer(s) confirmed it; the others may still forward the port until the router drops " +
            "it, as UPnP leased it for good"), removals(lines))
        }
        assertEquals(listOf("AddPortMapping TCP 6881", "AddPortMapping TCP 6881",
          "GetExternalIPAddress null null", "DeletePortMapping TCP 6881"), igd.actions.value)
      } finally {
        silent.close()
      }
    }

  @Test
  fun mapper_stop_duringTheFirstPass_saysTheGatewayMayStillForwardThePort() =
    gateways { network, scope ->
      val requests = MutableStateFlow(emptyList<Pair<Int, Int>>())
      val port = natPmpGateway(network, scope, silentGrants = true, silentRemovals = true,
        requests = requests)
      withInfoLog { lines ->
        val mapper = TorrentPortMapper(scope, tcpPort = 6881, udpPort = { 6881 },
          onMapped = {},
          natPmp = NatPmpClient(network, port, retryMs = listOf(replyMs)),
          upnp = UpnpClient(network, ssdp = PeerEndpoint("127.0.0.1", 9), searchMs = 100),
          networks = { listOf(LocalIpv4Network(ip("127.0.0.2"), 8)) })
        mapper.start()
        // The gateway mapped TCP, but no reply says so before the engine stops, nor after.
        requests.first { it.isNotEmpty() }
        mapper.stop(timeoutMs = stopMs)
        assertEquals(listOf("[TorrentEngine] Removing the mapping of port 6881: 0 of 1 NAT " +
          "layer(s) confirmed it; the others may still forward the port until their lease ends"),
          removals(lines))
        assertEquals(listOf(2 to 3600, 2 to 0), requests.value)
      }
    }

  @Test
  fun mapper_stop_upnpRouterHoldsNoSuchEntry_countsTheMappingRemoved() =
    gateways { network, scope ->
      // NoSuchEntryInArray: the router lost the mapping already.
      val ssdp = upnpGateway(network, scope, Igd().apply { deleteFault = 714 })
      val silent = network.bindUdp(PeerEndpoint("127.0.0.1", 0))
      try {
        withInfoLog { lines ->
          mapThroughUpnp(network, scope, ssdp, silent.local.port).stop(timeoutMs = stopMs)
          assertEquals(
            listOf("[TorrentEngine] Removed the mapping of port 6881 through 1 NAT layer(s)"),
            removals(lines),
          )
        }
      } finally {
        silent.close()
      }
    }

  @Test
  fun mapper_udpBoundLater_isMappedWithoutWaitingForRenewal() = gateways { network, scope ->
    val requests = MutableStateFlow(emptyList<Pair<Int, Int>>())
    val port = natPmpGateway(network, scope, requests = requests)
    var udp: Int? = null
    val mapper = TorrentPortMapper(scope, tcpPort = 6881, udpPort = { udp },
      onMapped = {},
      natPmp = NatPmpClient(network, port, retryMs = listOf(replyMs)),
      upnp = UpnpClient(network, ssdp = PeerEndpoint("127.0.0.1", 9), searchMs = 100),
      networks = { listOf(LocalIpv4Network(ip("127.0.0.2"), 8)) },
      udpCheckMs = 100)
    mapper.start()
    requests.first { it.isNotEmpty() }
    assertEquals(listOf(2 to 3600), requests.value)
    // DHT binds its socket after the first pass.
    udp = 6881
    requests.first { list -> list.any { it.first == 1 } }
    mapper.stop(timeoutMs = stopMs)
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
      mapper.stop(timeoutMs = stopMs)
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
