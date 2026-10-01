package com.linroid.ketch.app.desktop

import com.linroid.ketch.api.KetchApi
import java.lang.reflect.Proxy
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class BrowserExtensionServerTest {
  /** The server never calls the API itself, so any instance will do. */
  private val api = Proxy.newProxyInstance(
    KetchApi::class.java.classLoader,
    arrayOf(KetchApi::class.java),
  ) { _, method, _ -> error("Unexpected call to ${method.name}") } as KetchApi

  @Test
  fun connect_startsTheServerOnceAndRepliesWithItsAddressAndToken() {
    val tokens = mutableListOf<String>()
    val server = BrowserExtensionServer { _, token ->
      tokens += token
      BrowserExtensionServer.Started(port = 5123, token = token, stop = { })
    }
    server.attach(api)

    val first = server.connect()
    val second = server.connect()

    assertEquals(1, tokens.size)
    assertEquals(64, tokens.single().length)
    assertEquals("""{"url":"http://127.0.0.1:5123","token":"${tokens.single()}"}""", first)
    assertEquals(first, second)
  }

  @Test
  fun connect_beforeTheAppIsReady_waitsForIt() {
    val server = BrowserExtensionServer { _, token ->
      BrowserExtensionServer.Started(port = 5123, token = token, stop = { })
    }
    val reply = Executors.newSingleThreadExecutor().submit<String> { server.connect(5.seconds) }
    Thread.sleep(50)
    server.attach(api)

    assertTrue(reply.get(5, TimeUnit.SECONDS).startsWith("""{"url":"http://127.0.0.1:5123""""))
  }

  @Test
  fun connect_whenTheAppNeverGetsReady_repliesWithAnError() {
    val server = BrowserExtensionServer { _, _ -> error("Must not start without the app") }
    assertEquals(
      """{"error":"not_ready","message":"Ketch is still starting"}""",
      server.connect(10.milliseconds),
    )
  }

  @Test
  fun connect_afterTheServerFailedToStart_triesAgain() {
    var attempts = 0
    val server = BrowserExtensionServer { _, token ->
      if (++attempts == 1) throw IllegalStateException("port in use")
      BrowserExtensionServer.Started(port = 5123, token = token, stop = { })
    }
    server.attach(api)

    assertEquals(
      """{"error":"server_failed","message":"Couldn't start the connection: port in use"}""",
      server.connect(),
    )
    assertTrue(server.connect().contains("5123"))
  }

  @Test
  fun connect_eachTimeTheExtensionConnects_reportsIt() {
    var connections = 0
    val server = BrowserExtensionServer(onConnect = { connections++ }) { _, token ->
      BrowserExtensionServer.Started(port = 5123, token = token, stop = { })
    }
    server.attach(api)

    server.connect()
    server.connect()

    assertEquals(2, connections)
  }

  @Test
  fun connect_whenTheServerFails_reportsNoConnection() {
    var connections = 0
    val server = BrowserExtensionServer(onConnect = { connections++ }) { _, _ ->
      throw IllegalStateException("port in use")
    }
    server.attach(api)

    server.connect()

    assertEquals(0, connections)
  }

  @Test
  fun close_stopsTheServer() {
    var stopped = false
    val server = BrowserExtensionServer { _, token ->
      BrowserExtensionServer.Started(port = 5123, token = token, stop = { stopped = true })
    }
    server.attach(api)
    server.connect()

    server.close()

    assertTrue(stopped)
  }
}
