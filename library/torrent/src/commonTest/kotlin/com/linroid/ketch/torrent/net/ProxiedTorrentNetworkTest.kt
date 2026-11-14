package com.linroid.ketch.torrent.net

import com.linroid.ketch.api.ProxyAddress
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import okio.IOException
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Peer connections through a SOCKS5 or HTTP proxy on loopback, and what must not leave. */
class ProxiedTorrentNetworkTest {
  /** What a scripted proxy saw of the tunnels it was asked for. */
  private class Seen {
    val targets = mutableListOf<String>()
    val credentials = mutableListOf<String>()
  }

  private class Fixture(val network: TorrentNetwork, val peer: PeerEndpoint, val seen: Seen) {
    lateinit var proxy: PeerEndpoint

    fun through(type: ProxyAddress.Type, user: String? = null, password: String? = null) =
      ProxiedTorrentNetwork(network, ProxyAddress(type, proxy.host, proxy.port, user, password))
  }

  /** Runs [block] beside a peer that echoes what it is sent, 5 bytes at a time. */
  private fun fixture(
    proxy: suspend CoroutineScope.(Fixture, TorrentConnection) -> Unit,
    block: suspend (Fixture) -> Unit,
  ) = runTest {
    withContext(Dispatchers.Default) {
      withTimeout(20_000) {
        coroutineScope {
          val network = createTorrentNetwork()
          val peers = network.listen(PeerEndpoint("127.0.0.1", 0))
          val proxies = network.listen(PeerEndpoint("127.0.0.1", 0))
          val fixture = Fixture(network, peers.local, Seen())
          fixture.proxy = proxies.local
          val serving = launch {
            launch {
              while (isActive) {
                val connection = peers.accept()
                launch {
                  try {
                    while (isActive) connection.write(connection.readExactly(5))
                  } catch (_: IOException) {
                    connection.close()
                  }
                }
              }
            }
            while (isActive) {
              val connection = proxies.accept()
              launch {
                try {
                  proxy(fixture, connection)
                } catch (_: IOException) {
                  connection.close()
                }
              }
            }
          }
          try {
            block(fixture)
          } finally {
            serving.cancelAndJoin()
            peers.close()
            proxies.close()
            network.close()
          }
        }
      }
    }
  }

  /** Copies both ways between [client] and the test's peer until either side ends. */
  private suspend fun CoroutineScope.relay(fixture: Fixture, client: TorrentConnection) {
    val peer = fixture.network.connect(fixture.peer)
    for ((from, to) in listOf(client to peer, peer to client)) {
      launch {
        try {
          while (isActive) to.write(from.readExactly(5))
        } catch (_: IOException) {
          // Either end hung up: so does the other.
          from.close()
          to.close()
        }
      }
    }
  }

  /** A SOCKS5 proxy asking for [password] when set, tunneling every request to the test's peer. */
  private fun socks5(password: String? = null):
    suspend CoroutineScope.(Fixture, TorrentConnection) -> Unit = { fixture, client ->
    val greeting = client.readExactly(2)
    val methods = client.readExactly(greeting[1].toInt())
    if (password == null) {
      client.write(byteArrayOf(5, 0))
    } else {
      assertTrue(2.toByte() in methods)
      client.write(byteArrayOf(5, 2))
      val name = client.readExactly(client.readExactly(2)[1].toInt()).decodeToString()
      val secret = client.readExactly(client.readExactly(1)[0].toInt()).decodeToString()
      fixture.seen.credentials += "$name:$secret"
      client.write(byteArrayOf(1, if (secret == password) 0 else 1))
    }
    val request = client.readExactly(4)
    val host = when (request[3].toInt()) {
      1 -> numericHost(client.readExactly(4))
      4 -> numericHost(client.readExactly(16))
      else -> client.readExactly(client.readExactly(1)[0].toInt()).decodeToString()
    }
    val port = client.readExactly(2)
      .let { (it[0].toInt() and 0xff shl 8) or (it[1].toInt() and 0xff) }
    fixture.seen.targets += "$host:$port"
    if (host == "refused.example") {
      client.write(byteArrayOf(5, 5, 0, 1, 0, 0, 0, 0, 0, 0))
    } else {
      client.write(byteArrayOf(5, 0, 0, 1, 127, 0, 0, 1, 0, 80))
      relay(fixture, client)
    }
  }

  /** An HTTP proxy that tunnels `CONNECT` requests carrying [authorization], when it asks one. */
  private fun http(authorization: String? = null):
    suspend CoroutineScope.(Fixture, TorrentConnection) -> Unit = { fixture, client ->
    val head = StringBuilder()
    while (!head.endsWith("\r\n\r\n")) head.append(client.readExactly(1)[0].toInt().toChar())
    val lines = head.lines()
    fixture.seen.targets += lines.first().split(' ')[1]
    val sent = lines.firstOrNull { it.startsWith("Proxy-Authorization: ") }?.substringAfter(": ")
    sent?.let { fixture.seen.credentials += it }
    if (authorization != null && sent != authorization) {
      client.write("HTTP/1.1 407 Proxy Authentication Required\r\n\r\n".encodeToByteArray())
    } else {
      client.write("HTTP/1.1 200 Connection established\r\n\r\n".encodeToByteArray())
      relay(fixture, client)
    }
  }

  private suspend fun TorrentConnection.echoes(text: String) {
    write(text.encodeToByteArray())
    assertContentEquals(text.encodeToByteArray(), readExactly(text.length))
  }

  @Test
  fun socks5_tunnelsToThePeer_byAddressAndByName() = fixture(socks5()) { fixture ->
    val network = fixture.through(ProxyAddress.Type.SOCKS5)

    val connection = network.connect(fixture.peer)
    assertEquals(fixture.peer, connection.remote)
    connection.echoes("hello")
    connection.echoes("again")
    connection.close()
    // A host name is the proxy's to resolve: no lookup happens here.
    val named = PeerEndpoint("peer.example", 6881)
    network.connect(named).apply {
      assertEquals(named, remote)
      echoes("named")
      close()
    }

    assertEquals(listOf("127.0.0.1:${fixture.peer.port}", "peer.example:6881"),
      fixture.seen.targets)
    assertFailsWith<IOException> { network.connect(PeerEndpoint("refused.example", 1)) }
  }

  @Test
  fun socks5_signsInWithItsPassword_andFailsWhenItIsRefused() = fixture(socks5("s3cret")) { f ->
    f.through(ProxyAddress.Type.SOCKS5, "ketch", "s3cret").connect(f.peer).apply {
      echoes("hello")
      close()
    }
    assertFailsWith<IOException> {
      f.through(ProxyAddress.Type.SOCKS5, "ketch", "wrong").connect(f.peer)
    }
    assertEquals(listOf("ketch:s3cret", "ketch:wrong"), f.seen.credentials)
    // Refused before any tunnel was asked for.
    assertEquals(1, f.seen.targets.size)
  }

  @Test
  fun httpConnect_tunnelsToThePeer_withItsCredentials() {
    val basic = "Basic " + Base64.Default.encode("ketch:s3cret".encodeToByteArray())
    fixture(http(basic)) { f ->
      f.through(ProxyAddress.Type.HTTP, "ketch", "s3cret").connect(f.peer).apply {
        echoes("hello")
        close()
      }
      assertEquals(listOf("127.0.0.1:${f.peer.port}"), f.seen.targets)
      assertEquals(listOf(basic), f.seen.credentials)
      assertFailsWith<IOException> { f.through(ProxyAddress.Type.HTTP).connect(f.peer) }
    }
  }

  @Test
  fun nothingGoesAroundTheProxy() = fixture(socks5()) { fixture ->
    val network = fixture.through(ProxyAddress.Type.SOCKS5)

    // Datagrams go nowhere and none arrives: DHT, uTP, UDP trackers and port mapping are silent.
    val udp = network.bindUdp(PeerEndpoint("0.0.0.0", 0))
    udp.send(fixture.peer, byteArrayOf(1, 2, 3))
    assertNull(withTimeoutOrNull(200) { udp.receive() })
    udp.close()
    // Peers could only reach the listener at the address the proxy hides.
    val listener = network.listen(PeerEndpoint("0.0.0.0", 0))
    assertEquals("127.0.0.1", listener.local.host)
    listener.close()
    assertTrue(fixture.seen.targets.isEmpty())
  }
}
