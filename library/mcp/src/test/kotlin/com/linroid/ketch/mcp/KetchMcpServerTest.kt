package com.linroid.ketch.mcp

import ai.koog.agents.core.tools.ToolRegistry
import com.linroid.ketch.api.DownloadConfig
import com.linroid.ketch.api.DownloadRequest
import com.linroid.ketch.api.DownloadTask
import com.linroid.ketch.api.KetchApi
import com.linroid.ketch.api.KetchStatus
import com.linroid.ketch.api.ResolvedSource
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.io.Buffer
import kotlinx.io.asSink
import kotlinx.io.asSource
import kotlinx.io.buffered
import kotlinx.io.writeString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.seconds

// The "returns" tests pass by returning before the timeout: the MCP stdio transport expects
// the server to stop once the client closes its input.
class KetchMcpServerTest {

  @Test
  fun `serveStdio returns when the client closes its input`() =
    runTest(timeout = 10.seconds) {
      val input = Buffer().apply { writeString(INITIALIZE_REQUEST + "\n") }
      serveStdio(ToolRegistry {}, input, Buffer())
    }

  @Test
  fun `serveStdio returns when the input is already at its end`() =
    runTest(timeout = 10.seconds) {
      serveStdio(ToolRegistry {}, Buffer(), Buffer())
    }

  @Test
  fun `serveStdio writes its responses to the output`() =
    runTest(timeout = 10.seconds) {
      val requests = PipedOutputStream()
      val input = PipedInputStream(requests).asSource().buffered()
      val output = FirstLineOutputStream()
      val server = launch(Dispatchers.IO) {
        serveStdio(ToolRegistry {}, input, output.asSink().buffered())
      }

      // The input stays open until the response arrives: the server stops at its end
      withContext(Dispatchers.IO) {
        requests.write((INITIALIZE_REQUEST + "\n").encodeToByteArray())
        requests.flush()
      }
      val response = Json.parseToJsonElement(output.firstLine.await()).jsonObject

      assertEquals(1, response["id"]?.jsonPrimitive?.int)
      assertNotNull(response["result"])
      withContext(Dispatchers.IO) { requests.close() }
      server.join()
    }

  // Real threads and streams: the input stays open until each response has arrived.
  @Test
  fun `startStdio answers a tool call on the given output`() = runBlocking {
    val requests = PipedOutputStream()
    val input = PipedInputStream(requests)
    val output = ByteArrayOutputStream()
    val server = launch(Dispatchers.IO) {
      KetchMcpServer(EmptyKetchApi()).startStdio(input, output)
    }
    try {
      // A client sends nothing else until the server has answered initialize.
      requests.send(INITIALIZE_REQUEST)
      awaitLines(output, 1)
      requests.send(INITIALIZED_NOTIFICATION, LIST_DOWNLOADS_CALL)
      val responses = awaitLines(output, 2).map { Json.parseToJsonElement(it).jsonObject }

      assertEquals(listOf(1, 2), responses.map { it["id"]?.jsonPrimitive?.int })
      val result = responses[1].getValue("result").jsonObject
      assertNull(result["isError"], "listDownloads failed: $result")
    } finally {
      requests.close()
      server.cancel()
    }
  }

  private fun PipedOutputStream.send(vararg messages: String) {
    write(messages.joinToString("\n", postfix = "\n").toByteArray())
    flush()
  }

  /** Waits until [output] holds [count] complete lines and returns them. */
  private suspend fun awaitLines(output: ByteArrayOutputStream, count: Int): List<String> =
    withTimeout(30.seconds) {
      var lines: List<String>
      do {
        delay(50)
        lines = output.toString().substringBeforeLast('\n', "").lines()
      } while (lines.size < count || lines.last().isEmpty())
      lines
    }

  /** Collects bytes until the first newline, which ends one JSON-RPC message. */
  private class FirstLineOutputStream : OutputStream() {
    private val buffer = ByteArrayOutputStream()
    val firstLine = CompletableDeferred<String>()

    @Synchronized
    override fun write(b: Int) {
      if (b == '\n'.code) {
        firstLine.complete(buffer.toString(Charsets.UTF_8))
      } else {
        buffer.write(b)
      }
    }
  }

  private class EmptyKetchApi : KetchApi {
    override val backendLabel = "Empty"
    override val tasks: StateFlow<List<DownloadTask>> = MutableStateFlow(emptyList())

    override suspend fun download(request: DownloadRequest): DownloadTask =
      throw UnsupportedOperationException()

    override suspend fun resolve(url: String, properties: Map<String, String>): ResolvedSource =
      throw UnsupportedOperationException()

    override suspend fun resolveContent(content: ByteArray, fileName: String?): ResolvedSource =
      throw UnsupportedOperationException()

    override suspend fun start() {}

    override suspend fun status(): KetchStatus = throw UnsupportedOperationException()

    override suspend fun updateConfig(config: DownloadConfig) {}

    override fun close() {}
  }

  private companion object {
    const val INITIALIZE_REQUEST = """{"jsonrpc":"2.0","id":1,"method":"initialize",""" +
      """"params":{"protocolVersion":"2025-06-18","capabilities":{},""" +
      """"clientInfo":{"name":"test","version":"1"}}}"""
    const val INITIALIZED_NOTIFICATION =
      """{"jsonrpc":"2.0","method":"notifications/initialized"}"""
    const val LIST_DOWNLOADS_CALL = """{"jsonrpc":"2.0","id":2,"method":"tools/call",""" +
      """"params":{"name":"listDownloads","arguments":{}}}"""
  }
}
