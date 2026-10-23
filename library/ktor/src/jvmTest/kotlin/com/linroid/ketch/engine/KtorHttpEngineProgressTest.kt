package com.linroid.ketch.engine

import com.linroid.ketch.core.engine.TransferWatchdog
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import java.net.InetAddress
import java.net.InetSocketAddress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.milliseconds

class KtorHttpEngineProgressTest {
  @Test
  fun request_answerArrivingSlowlyUnderAWatchdog_isNoStall() = runBlocking {
    // Real time: the answer takes about a second, the watchdog allows 400 ms without data.
    val server = HttpServer.create(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0)
    server.createContext("/dav") { exchange ->
      exchange.sendResponseHeaders(207, 0)
      exchange.responseBody.use { body ->
        repeat(10) {
          body.write(ByteArray(100))
          body.flush()
          Thread.sleep(100)
        }
      }
    }
    server.start()
    val engine = KtorHttpEngine()
    try {
      val url = "http://127.0.0.1:${server.address.port}/dav"

      val reply = TransferWatchdog(400.milliseconds, 0).watch { engine.request("PROPFIND", url) }

      assertEquals(207, reply.status)
      assertEquals(1000, reply.body.size)
    } finally {
      engine.close()
      server.stop(0)
    }
  }
}
