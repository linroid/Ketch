package com.linroid.ketch.mcp

import ai.koog.agents.core.tools.ToolRegistry
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
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

  private companion object {
    const val INITIALIZE_REQUEST = """{"jsonrpc":"2.0","id":1,"method":"initialize",""" +
      """"params":{"protocolVersion":"2025-06-18","capabilities":{},""" +
      """"clientInfo":{"name":"test","version":"1"}}}"""
  }
}
