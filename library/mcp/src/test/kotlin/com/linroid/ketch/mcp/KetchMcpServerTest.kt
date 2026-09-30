package com.linroid.ketch.mcp

import ai.koog.agents.core.tools.ToolRegistry
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.io.Buffer
import kotlinx.io.IOException
import kotlinx.io.RawSink
import kotlinx.io.RawSource
import kotlinx.io.readString
import kotlinx.io.writeString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import java.util.concurrent.CountDownLatch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

// Each test also checks that serveStdio returns before the timeout: the MCP stdio transport
// expects the server to stop once the client closes its input.
class KetchMcpServerTest {

  @Test
  fun `serveStdio answers a request sent just before the input ends`() =
    runTest(timeout = 10.seconds) {
      val output = Buffer()
      serveStdio(ToolRegistry {}, input(INITIALIZE), output)

      val replies = replies(output)
      assertEquals(listOf(JsonPrimitive(1)), replies.map { it["id"] })
      assertTrue("result" in replies.single())
    }

  @Test
  fun `serveStdio answers every request read before the input ends in order`() =
    runTest(timeout = 10.seconds) {
      val output = Buffer()
      serveStdio(ToolRegistry {}, input(INITIALIZE, INITIALIZED, TOOLS_LIST, PING), output)

      val replies = replies(output)
      assertEquals(listOf(1, 2, 3).map(::JsonPrimitive), replies.map { it["id"] })
      assertTrue(replies.all { "result" in it })
    }

  @Test
  fun `serveStdio returns when the input has already ended`() =
    runTest(timeout = 10.seconds) {
      val output = Buffer()
      serveStdio(ToolRegistry {}, Buffer(), output)

      assertEquals("", output.readString())
    }

  @Test
  fun `serveStdio stops and closes the input when cancelled while the input is still open`() =
    runTest(timeout = 10.seconds) {
      val input = OpenInput()
      try {
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
          serveStdio(ToolRegistry {}, input, Buffer())
        }
        job.cancel()
        job.join()

        assertTrue(input.isClosed)
      } finally {
        input.close()
      }
    }

  @Test
  fun `serveStdio stops and closes the input when writing a reply fails`() =
    runTest(timeout = 10.seconds) {
      val input = OpenInput(INITIALIZE)
      val brokenOutput = object : RawSink {
        override fun write(source: Buffer, byteCount: Long) = throw IOException("Broken pipe")
        override fun flush() {}
        override fun close() {}
      }
      try {
        serveStdio(ToolRegistry {}, input, brokenOutput)

        assertTrue(input.isClosed)
      } finally {
        input.close()
      }
    }

  private fun input(vararg lines: String): Buffer =
    Buffer().apply { lines.forEach { writeString(it + "\n") } }

  /**
   * Input from a client that sends [lines] and then keeps its end open. Like a socket, a blocked
   * read ends when the stream is closed.
   */
  private class OpenInput(vararg lines: String) : RawSource {
    private val pending = Buffer().apply { lines.forEach { writeString(it + "\n") } }
    private val closed = CountDownLatch(1)
    val isClosed: Boolean get() = closed.count == 0L

    override fun readAtMostTo(sink: Buffer, byteCount: Long): Long {
      if (!pending.exhausted()) return pending.readAtMostTo(sink, byteCount)
      closed.await()
      throw IOException("Stream closed")
    }

    override fun close() = closed.countDown()
  }

  private fun replies(output: Buffer): List<JsonObject> =
    output.readString().lines().filter { it.isNotBlank() }
      .map { Json.parseToJsonElement(it).jsonObject }

  private companion object {
    const val INITIALIZE = """{"jsonrpc":"2.0","id":1,"method":"initialize",""" +
      """"params":{"protocolVersion":"2025-06-18","capabilities":{},""" +
      """"clientInfo":{"name":"test","version":"1"}}}"""
    const val INITIALIZED = """{"jsonrpc":"2.0","method":"notifications/initialized"}"""
    const val TOOLS_LIST = """{"jsonrpc":"2.0","id":2,"method":"tools/list"}"""
    const val PING = """{"jsonrpc":"2.0","id":3,"method":"ping"}"""
  }
}
