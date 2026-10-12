package com.linroid.ketch.server

import com.linroid.ketch.api.KetchApi
import com.linroid.ketch.api.ResolveInput
import com.linroid.ketch.api.ResolvedSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.net.Socket
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.time.Duration.Companion.seconds

/** A resolve waits as long as it takes, so the server stops it when its client disconnects. */
class ResolveDisconnectTest {

  @Test
  fun resolve_stopsWhenTheClientDisconnects() = serving { ketch, port ->
    Socket("127.0.0.1", port).use { socket ->
      socket.getOutputStream().write(resolveRequest(port, close = false))
      withTimeout(5.seconds) { ketch.started.await() }
    }
    withTimeout(5.seconds) { ketch.stopped.await() }
  }

  @Test
  fun resolve_withConnectionClose_answersOnceFound() = serving { ketch, port ->
    Socket("127.0.0.1", port).use { socket ->
      socket.getOutputStream().write(resolveRequest(port, close = true))
      withTimeout(5.seconds) { ketch.started.await() }
      delay(500)
      assertFalse(ketch.stopped.isCompleted)

      ketch.found.complete(Unit)

      val status = withContext(Dispatchers.IO) {
        socket.getInputStream().bufferedReader().readLine()
      }
      assertEquals("HTTP/1.1 200 OK", status)
    }
  }

  private fun serving(block: suspend (WaitingKetch, Int) -> Unit) = runBlocking {
    val delegate = createTestKetch()
    val ketch = WaitingKetch(delegate)
    val server = KetchServer(ketch, host = "127.0.0.1", port = 0, mdnsEnabled = false)
    server.start(wait = false)
    try {
      block(ketch, server.port())
    } finally {
      server.stop()
      delegate.close()
    }
  }

  private fun resolveRequest(port: Int, close: Boolean): ByteArray {
    val body = """{"url":"https://example.com/file.bin"}"""
    return buildString {
      append("POST /api/resolve HTTP/1.1\r\n")
      append("Host: 127.0.0.1:$port\r\n")
      append("Content-Type: application/json\r\n")
      append("Content-Length: ${body.length}\r\n")
      if (close) append("Connection: close\r\n")
      append("\r\n")
      append(body)
    }.encodeToByteArray()
  }

  /** Resolves only once [found] completes, noting when it starts and when it is cancelled. */
  private class WaitingKetch(delegate: KetchApi) : KetchApi by delegate {
    val started = CompletableDeferred<Unit>()
    val stopped = CompletableDeferred<Unit>()
    val found = CompletableDeferred<Unit>()

    override suspend fun resolve(input: ResolveInput): ResolvedSource {
      started.complete(Unit)
      try {
        found.await()
      } catch (e: CancellationException) {
        stopped.complete(Unit)
        throw e
      }
      return ResolvedSource(
        url = (input as ResolveInput.Url).url, sourceType = "http", totalBytes = 0,
        supportsResume = false, suggestedFileName = "file.bin", maxSegments = 1,
      )
    }
  }
}
