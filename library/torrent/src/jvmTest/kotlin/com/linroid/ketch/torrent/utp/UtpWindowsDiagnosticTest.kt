package com.linroid.ketch.torrent.utp

import com.linroid.ketch.api.log.KetchLogger
import com.linroid.ketch.api.log.LogLevel
import com.linroid.ketch.api.log.Logger
import com.linroid.ketch.torrent.TorrentConfig
import com.linroid.ketch.torrent.engine.KotlinTorrentEngine
import com.linroid.ketch.torrent.net.PeerEndpoint
import com.linroid.ketch.torrent.net.TorrentConnection
import com.linroid.ketch.torrent.net.TorrentUdpPort
import com.linroid.ketch.torrent.net.createTorrentNetwork
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import java.net.InetSocketAddress
import java.net.StandardProtocolFamily
import java.nio.ByteBuffer
import java.nio.channels.DatagramChannel
import kotlin.test.Test

/** Temporary: prints how this system routes loopback UDP, for a Windows-only failure. */
class UtpWindowsDiagnosticTest {
  private fun p(text: String) = println("DIAG $text")

  @Test
  fun rawDualStack() {
    for (round in 1..3) try {
      val v4 = DatagramChannel.open().bind(InetSocketAddress("0.0.0.0", 0))
      val port = (v4.localAddress as InetSocketAddress).port
      p("round $round v4 bound ${v4.localAddress}")
      val v6 = try {
        DatagramChannel.open().bind(InetSocketAddress("::", port)).also {
          p("v6 bound ${it.localAddress}")
        }
      } catch (e: Exception) { p("v6 bind failed: $e"); null }
      val v6only = try {
        DatagramChannel.open(StandardProtocolFamily.INET6).bind(InetSocketAddress("::", port))
          .also { p("INET6 bound ${it.localAddress}") }
      } catch (e: Exception) { p("INET6 bind failed: $e"); null }
      val sender = DatagramChannel.open().bind(InetSocketAddress("127.0.0.1", 0))
      sender.send(ByteBuffer.wrap(byteArrayOf(1)), InetSocketAddress("127.0.0.1", port))
      Thread.sleep(200)
      listOf("v4" to v4, "v6" to v6, "INET6" to v6only).forEach { (name, c) ->
        if (c == null) return@forEach
        c.configureBlocking(false)
        p("$name received from ${c.receive(ByteBuffer.allocate(16))}")
      }
      listOfNotNull(v4, v6, v6only, sender).forEach { it.close() }
    } catch (e: Exception) { p("round $round failed: $e") }
  }

  @Test
  fun specificThenWildcard() {
    try {
      val specific = DatagramChannel.open().bind(InetSocketAddress("127.0.0.1", 0))
      val port = (specific.localAddress as InetSocketAddress).port
      val wildcard = try {
        DatagramChannel.open().bind(InetSocketAddress("0.0.0.0", port))
          .also { p("wildcard bound beside specific at $port") }
      } catch (e: Exception) { p("wildcard bind beside specific failed: $e"); null }
      val sender = DatagramChannel.open().bind(InetSocketAddress("127.0.0.1", 0))
      sender.send(ByteBuffer.wrap(byteArrayOf(1)), InetSocketAddress("127.0.0.1", port))
      Thread.sleep(200)
      listOf("specific" to specific, "wildcard" to wildcard).forEach { (name, c) ->
        if (c == null) return@forEach
        c.configureBlocking(false)
        p("$name received from ${c.receive(ByteBuffer.allocate(16))}")
      }
      listOfNotNull(specific, wildcard, sender).forEach { it.close() }
    } catch (e: Exception) { p("specificThenWildcard failed: $e") }
  }

  @Test
  fun engineDial() = runBlocking(Dispatchers.Default) {
    var failures = 0
    try {
      for (round in 1..200) {
        if (round == 2) KetchLogger.setLogger(Logger.console(LogLevel.INFO))
        val network = createTorrentNetwork()
        val scope = CoroutineScope(Dispatchers.Default)
        val engine = KotlinTorrentEngine(TorrentConfig(dhtEnabled = false),
          rawNetwork = network, listenHost = "127.0.0.1")
        try {
          engine.start()
          val incoming = Channel<TorrentConnection>(Channel.UNLIMITED)
          var endpoint: UtpEndpoint? = null
          val port = TorrentUdpPort(network.bindUdp(PeerEndpoint("127.0.0.1", 0)), scope,
            utp = { endpoint?.receive(it) })
          endpoint = UtpEndpoint(scope, port::send, onIncoming = { incoming.trySend(it).isSuccess },
            dialOptions = UtpOptions(synRetries = 1))
          try {
            endpoint.dial(PeerEndpoint("127.0.0.1", engine.listenPort)).close()
          } catch (e: Exception) {
            failures++
            p("round $round dial failed: $e (local ${port.local})")
            try {
              DatagramChannel.open().bind(InetSocketAddress("127.0.0.1", engine.listenPort)).close()
              p("127.0.0.1:${engine.listenPort} was free for UDP")
            } catch (b: Exception) { p("127.0.0.1:${engine.listenPort} held: $b") }
            if (System.getProperty("os.name").startsWith("Windows")) {
              val out = ProcessBuilder("netstat", "-ano", "-p", "UDP").redirectErrorStream(true)
                .start().inputStream.bufferedReader().readText()
              out.lines().filter { ":${engine.listenPort} " in it }.forEach { p("netstat $it") }
            }
          }
        } catch (e: Exception) {
          p("round $round failed: $e")
        } finally {
          engine.stop()
          scope.cancel()
          network.close()
        }
      }
    } finally {
      KetchLogger.setLogger(Logger.None)
      p("failures $failures of 200")
    }
  }
}
