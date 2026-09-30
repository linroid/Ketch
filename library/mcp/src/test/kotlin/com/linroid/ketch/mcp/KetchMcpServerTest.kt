package com.linroid.ketch.mcp

import ai.koog.agents.core.tools.ToolRegistry
import com.linroid.ketch.api.DownloadConfig
import com.linroid.ketch.api.DownloadRequest
import com.linroid.ketch.api.DownloadTask
import com.linroid.ketch.api.KetchApi
import com.linroid.ketch.api.KetchStatus
import com.linroid.ketch.api.ResolvedSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.io.Buffer
import kotlinx.io.writeString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.ByteArrayOutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.seconds

class KetchMcpServerTest {

  // These two pass by returning before the timeout: the MCP stdio transport expects the
  // server to stop once the client closes its input.
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

  // Real threads and streams: the input stays open until both responses have arrived.
  @Test
  fun `startStdio answers a tool call on the given output`() = runBlocking {
    val requests = PipedOutputStream()
    val input = PipedInputStream(requests)
    val output = ByteArrayOutputStream()
    val server = launch(Dispatchers.IO) {
      KetchMcpServer(EmptyKetchApi()).startStdio(input, output)
    }
    try {
      val lines = listOf(INITIALIZE_REQUEST, INITIALIZED_NOTIFICATION, LIST_DOWNLOADS_CALL)
      requests.write(lines.joinToString("\n", postfix = "\n").toByteArray())
      requests.flush()
      val written = withTimeout(30.seconds) {
        var written: List<String>
        do {
          delay(50)
          written = output.toString().lines().filter { it.isNotEmpty() }
        } while (written.size < 2)
        written
      }

      val responses = written.map { Json.parseToJsonElement(it).jsonObject }
      assertEquals(listOf("1", "2"), responses.map { it.getValue("id").jsonPrimitive.content })
      val result = responses[1].getValue("result").jsonObject
      assertNull(result["isError"], "listDownloads failed: $result")
    } finally {
      requests.close()
      server.cancel()
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
