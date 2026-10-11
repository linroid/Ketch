package com.linroid.ketch.torrent.utp

import com.linroid.ketch.torrent.TorrentConfig
import com.linroid.ketch.torrent.engine.KotlinTorrentEngine
import com.linroid.ketch.torrent.net.PeerEndpoint
import com.linroid.ketch.torrent.net.TorrentConnection
import com.linroid.ketch.torrent.net.TorrentDatagram
import com.linroid.ketch.torrent.net.TorrentDatagramSocket
import com.linroid.ketch.torrent.net.TorrentNetwork
import com.linroid.ketch.torrent.net.TorrentUdpPort
import com.linroid.ketch.torrent.net.createTorrentNetwork
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.job
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okio.IOException
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** uTP endpoints over real loopback UDP ports. */
class UtpEndpointTest {
  /** A UDP port with a uTP endpoint whose incoming connections arrive in [incoming]. */
  private class Side(
    val port: TorrentUdpPort,
    val endpoint: UtpEndpoint,
    val incoming: Channel<TorrentConnection>,
  ) {
    val address: PeerEndpoint get() = PeerEndpoint("127.0.0.1", port.local.port)
  }

  private suspend fun side(
    network: TorrentNetwork,
    scope: CoroutineScope,
    dialOptions: UtpOptions = UtpOptions(synRetries = 2),
  ): Side {
    val incoming = Channel<TorrentConnection>(Channel.UNLIMITED)
    var endpoint: UtpEndpoint? = null
    val port = TorrentUdpPort(network.bindUdp(PeerEndpoint("127.0.0.1", 0)), scope,
      utp = { endpoint?.receive(it) })
    endpoint = UtpEndpoint(scope, port::send, onIncoming = { incoming.trySend(it).isSuccess },
      dialOptions = dialOptions)
    return Side(port, endpoint, incoming)
  }

  private fun endpoints(test: suspend CoroutineScope.(TorrentNetwork, CoroutineScope) -> Unit) =
    runTest {
      withContext(Dispatchers.Default) {
        withTimeout(30_000) {
          val network = createTorrentNetwork()
          val scope = CoroutineScope(Dispatchers.Default)
          try {
            coroutineScope { test(network, scope) }
          } finally {
            // Stopped before its sockets close, which fails rather than cancels their I/O.
            withContext(NonCancellable) { scope.coroutineContext.job.cancelAndJoin() }
            network.close()
          }
        }
      }
    }

  @Test
  fun dial_exchangesDataBothWays_andCloseEndsTheStream() = endpoints { network, scope ->
    val a = side(network, scope)
    val b = side(network, scope)
    val dialed = a.endpoint.dial(b.address)
    val accepted = b.incoming.receive()
    assertTrue(dialed.utp && accepted.utp)
    assertEquals(a.address.port, accepted.remote.port)
    val up = Random(1).nextBytes(2_000_000)
    val down = Random(2).nextBytes(300_000)
    coroutineScope {
      val writing = async { dialed.write(up) }
      val answering = async { accepted.write(down) }
      assertContentEquals(up, accepted.readExactly(up.size))
      assertContentEquals(down, dialed.readExactly(down.size))
      writing.await()
      answering.await()
    }
    dialed.close()
    assertFailsWith<IOException> { accepted.readExactly(1) }
  }

  @Test
  fun dial_silentPort_failsAfterItsSynRetries() = endpoints { network, scope ->
    // A UDP socket that never answers.
    val silent = network.bindUdp(PeerEndpoint("127.0.0.1", 0))
    val a = side(network, scope, dialOptions = UtpOptions(synRetries = 1))
    try {
      assertFailsWith<IOException> { a.endpoint.dial(PeerEndpoint("127.0.0.1", silent.local.port)) }
    } finally {
      silent.close()
    }
  }

  @Test
  fun refusedIncoming_resetsTheDialer() = endpoints { network, scope ->
    val a = side(network, scope)
    val incoming = CompletableDeferred<Unit>()
    var endpoint: UtpEndpoint? = null
    val port = TorrentUdpPort(network.bindUdp(PeerEndpoint("127.0.0.1", 0)), scope,
      utp = { endpoint?.receive(it) })
    endpoint = UtpEndpoint(scope, port::send, onIncoming = { incoming.complete(Unit); false })
    // The RESET may arrive before the dial saw the connection open, or after: either way the
    // connection fails.
    assertFailsWith<IOException> {
      a.endpoint.dial(PeerEndpoint("127.0.0.1", port.local.port)).readExactly(1)
    }
    incoming.await()
  }

  @Test
  fun udpPort_reportsASocketThatFailed_butNotOneClosed() = endpoints { network, scope ->
    val failed = CompletableDeferred<Unit>()
    val failing = object : TorrentDatagramSocket {
      override val local = PeerEndpoint("127.0.0.1", 1)
      override suspend fun send(remote: PeerEndpoint, bytes: ByteArray) = Unit
      override suspend fun receive(): TorrentDatagram = throw IOException("Socket broke")
      override fun close() = Unit
    }
    TorrentUdpPort(failing, scope, utp = null, onStopped = { failed.complete(Unit) })
    failed.await()
    var stopped = false
    val port = TorrentUdpPort(network.bindUdp(PeerEndpoint("127.0.0.1", 0)), scope, utp = null,
      onStopped = { stopped = true })
    port.close()
    delay(100)
    assertEquals(false, stopped)
  }

  @Test
  fun fallback_asksForTheEndpointOnEveryFailedDial() = endpoints { network, scope ->
    val b = side(network, scope)
    val a = side(network, scope)
    var lookups = 0
    val refused = object : TorrentNetwork by network {
      override suspend fun connect(remote: PeerEndpoint): TorrentConnection =
        throw IOException("Connection refused")
    }
    // The lookup is where a port that stopped is bound again; it runs for each dial.
    val fallback = TorrentUtpFallback(refused) { lookups++; a.endpoint }
    fallback.connect(b.address).close()
    fallback.connect(b.address).close()
    assertEquals(2, lookups)
  }

  @Test
  fun udpPort_survivesReceiveErrors_andClosingTheEndpointFailsItsConnections() =
    endpoints { network, scope ->
      // A socket that fails a few receives, as Windows does after an ICMP error, then works.
      val real = network.bindUdp(PeerEndpoint("127.0.0.1", 0))
      var errors = 3
      val flaky = object : TorrentDatagramSocket by real {
        override suspend fun receive(): TorrentDatagram {
          if (errors-- > 0) throw IOException("Connection reset by peer")
          return real.receive()
        }
      }
      var stopped = false
      val port = TorrentUdpPort(flaky, scope, utp = null, onStopped = { stopped = true })
      val dht = port.openDht()
      val sender = network.bindUdp(PeerEndpoint("127.0.0.1", 0))
      try {
        sender.send(PeerEndpoint("127.0.0.1", real.local.port), "d1:y1:qe".encodeToByteArray())
        assertEquals("d1:y1:qe", dht.receive().bytes.decodeToString())
        assertEquals(false, stopped)
      } finally {
        sender.close()
        port.close()
      }
      val a = side(network, scope)
      val b = side(network, scope)
      val connection = a.endpoint.dial(b.address)
      a.endpoint.close()
      assertFailsWith<IOException> { connection.readExactly(1) }
    }

  @Test
  fun engine_utpSwitchedOff_refusesNewUtpConnections() = endpoints { network, scope ->
    val engine = KotlinTorrentEngine(TorrentConfig(dhtEnabled = false), rawNetwork = network,
      listenHost = "127.0.0.1")
    try {
      engine.start()
      val a = side(network, scope, dialOptions = UtpOptions(synRetries = 1))
      val target = PeerEndpoint("127.0.0.1", engine.listenPort)
      // On: the engine answers uTP on its listen port.
      a.endpoint.dial(target).close()
      engine.setUtpEnabled(false)
      assertFailsWith<IOException> { a.endpoint.dial(target).readExactly(1) }
    } finally {
      engine.stop()
    }
  }

  @Test
  fun fallback_peersKnownForUtp_skipTcp() = endpoints { network, scope ->
    val b = side(network, scope)
    val a = side(network, scope)
    var tcp = 0
    val refused = object : TorrentNetwork by network {
      override suspend fun connect(remote: PeerEndpoint): TorrentConnection {
        tcp++
        throw IOException("Connection refused")
      }
    }
    val fallback = TorrentUtpFallback(refused) { a.endpoint }
    // Peer exchange said it speaks uTP: no TCP connect first.
    fallback.preferUtp(listOf(b.address))
    fallback.connect(b.address).close()
    assertEquals(0, tcp)
    // Another peer reached over uTP after TCP failed is dialed over uTP next time.
    val c = side(network, scope)
    fallback.connect(c.address).close()
    fallback.connect(c.address).close()
    assertEquals(1, tcp)
  }

  @Test
  fun fallback_fullEndpoint_keepsThePeerPreferred() = endpoints { network, scope ->
    val b = side(network, scope)
    val incoming = Channel<TorrentConnection>(Channel.UNLIMITED)
    var endpoint: UtpEndpoint? = null
    val port = TorrentUdpPort(network.bindUdp(PeerEndpoint("127.0.0.1", 0)), scope,
      utp = { endpoint?.receive(it) })
    // Room for one connection only.
    endpoint = UtpEndpoint(scope, port::send, onIncoming = { incoming.trySend(it).isSuccess },
      maxConnections = 1)
    var tcp = 0
    val refused = object : TorrentNetwork by network {
      override suspend fun connect(remote: PeerEndpoint): TorrentConnection {
        tcp++
        throw IOException("Connection refused")
      }
    }
    val fallback = TorrentUtpFallback(refused) { endpoint }
    fallback.preferUtp(listOf(b.address))
    val first = fallback.connect(b.address)
    // Full: the second dial cannot use uTP and falls through to TCP, which is refused.
    assertFailsWith<IOException> { fallback.connect(b.address) }
    assertEquals(1, tcp)
    first.close()
    // Once there is room, the peer is still dialed over uTP first: no TCP connect this time.
    withTimeout(5_000) { while (assertNotNull(endpoint).connectionCount > 0) delay(50) }
    fallback.connect(b.address).close()
    assertEquals(1, tcp)
  }

  @Test
  fun fallback_peerNoLongerOnUtp_usesTcpAndIsForgotten() = endpoints { network, scope ->
    val a = side(network, scope, dialOptions = UtpOptions(synRetries = 0))
    // TCP works; nothing answers uTP there. A silent socket holds the UDP port, as Windows may
    // give a UDP socket, such as our own endpoint's, the TCP listener's port number.
    var listener = network.listen(PeerEndpoint("127.0.0.1", 0))
    var silent = runCatching { network.bindUdp(PeerEndpoint("127.0.0.1", listener.local.port)) }
    while (silent.isFailure) {
      listener.close()
      listener = network.listen(PeerEndpoint("127.0.0.1", 0))
      silent = runCatching { network.bindUdp(PeerEndpoint("127.0.0.1", listener.local.port)) }
    }
    val target = PeerEndpoint("127.0.0.1", listener.local.port)
    var tcp = 0
    val counting = object : TorrentNetwork by network {
      override suspend fun connect(remote: PeerEndpoint): TorrentConnection {
        tcp++
        return network.connect(remote)
      }
    }
    try {
      val fallback = TorrentUtpFallback(counting) { a.endpoint }
      fallback.preferUtp(listOf(target))
      fallback.connect(target).close()
      fallback.connect(target).close()
      // uTP was tried once, then forgotten: both dials ended on TCP.
      assertEquals(2, tcp)
    } finally {
      silent.getOrThrow().close()
      listener.close()
    }
  }

  @Test
  fun udpPort_givesDhtEverythingButUtp() = endpoints { network, scope ->
    val b = side(network, scope)
    val dht = b.port.openDht()
    val sender = network.bindUdp(PeerEndpoint("127.0.0.1", 0))
    try {
      sender.send(b.address, "d1:y1:qe".encodeToByteArray())
      assertEquals("d1:y1:qe", dht.receive().bytes.decodeToString())
      // A replaced view stops receiving; the newest gets the next message.
      val replacement = b.port.openDht()
      sender.send(b.address, "d1:y1:re".encodeToByteArray())
      assertEquals("d1:y1:re", replacement.receive().bytes.decodeToString())
      assertFailsWith<IOException> { dht.receive() }
    } finally {
      sender.close()
    }
  }
}
