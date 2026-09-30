package com.linroid.ketch.mcp

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
import kotlinx.coroutines.withTimeout
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

  @Test
  fun startStdio_toolCall_writesOnlyJsonRpcResponsesToOutput() = runBlocking {
    val requests = PipedOutputStream()
    val input = PipedInputStream(requests)
    val output = ByteArrayOutputStream()
    val server = launch(Dispatchers.IO) {
      KetchMcpServer(EmptyKetchApi()).startStdio(input, output)
    }
    try {
      requests.write(REQUESTS.toByteArray())
      requests.flush()
      val lines = withTimeout(30.seconds) {
        var lines: List<String>
        do {
          delay(50)
          lines = output.toString().lines().filter { it.isNotEmpty() }
        } while (lines.size < 2)
        lines
      }

      val responses = lines.map { Json.parseToJsonElement(it).jsonObject }
      assertEquals(listOf("1", "2"), responses.map { it.getValue("id").jsonPrimitive.content })
      val result = responses[1].getValue("result").jsonObject
      assertNull(result["isError"], "listDownloads failed: $result")
    } finally {
      server.cancel()
      requests.close()
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
    val REQUESTS = listOf(
      """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":""" +
        """"2025-06-18","capabilities":{},"clientInfo":{"name":"test","version":"1"}}}""",
      """{"jsonrpc":"2.0","method":"notifications/initialized"}""",
      """{"jsonrpc":"2.0","id":2,"method":"tools/call",""" +
        """"params":{"name":"listDownloads","arguments":{}}}""",
    ).joinToString("\n", postfix = "\n")
  }
}
