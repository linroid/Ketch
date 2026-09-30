package com.linroid.ketch.mcp

import com.linroid.ketch.api.DownloadConfig
import com.linroid.ketch.api.DownloadRequest
import com.linroid.ketch.api.DownloadTask
import com.linroid.ketch.api.KetchApi
import com.linroid.ketch.api.KetchStatus
import com.linroid.ketch.api.ResolvedSource
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class KetchMcpServerTest {
  private val server = KetchMcpServer(UnusedKetchApi())

  @Test
  fun `startStdio returns when input is already at its end`() = runTest(timeout = 10.seconds) {
    server.startStdio(ByteArrayInputStream(ByteArray(0)), ByteArrayOutputStream())
  }

  @Test
  fun `startStdio answers requests until input ends`() = runTest(timeout = 10.seconds) {
    val requests = PipedOutputStream()
    val input = PipedInputStream(requests)
    val output = FirstLineOutputStream()
    val serving = launch(Dispatchers.IO) { server.startStdio(input, output) }

    requests.write((INITIALIZE + "\n").encodeToByteArray())
    requests.flush()
    val response = Json.parseToJsonElement(output.firstLine.await()).jsonObject
    assertEquals(1, response.getValue("id").jsonPrimitive.int)
    assertTrue("result" in response, "Unexpected response: $response")

    requests.close()
    serving.join()
  }

  /** Completes [firstLine] with the first line written to it. */
  private class FirstLineOutputStream : OutputStream() {
    val firstLine = CompletableDeferred<String>()
    private val buffer = ByteArrayOutputStream()

    override fun write(b: Int) {
      if (b == '\n'.code) {
        firstLine.complete(buffer.toString(Charsets.UTF_8))
      } else {
        buffer.write(b)
      }
    }
  }

  /** The server only calls [KetchApi] from tools, which these tests never call. */
  private class UnusedKetchApi : KetchApi {
    override val backendLabel: String = "Unused"
    override val tasks: StateFlow<List<DownloadTask>> = MutableStateFlow(emptyList())

    override suspend fun download(request: DownloadRequest): DownloadTask = unused()

    override suspend fun resolve(url: String, properties: Map<String, String>): ResolvedSource =
      unused()

    override suspend fun start() = unused()

    override suspend fun status(): KetchStatus = unused()

    override suspend fun updateConfig(config: DownloadConfig) = unused()

    override fun close() {}

    private fun unused(): Nothing = error("Not used by these tests")
  }

  private companion object {
    const val INITIALIZE = """{"jsonrpc":"2.0","id":1,"method":"initialize","params":""" +
      """{"protocolVersion":"2025-06-18","capabilities":{},""" +
      """"clientInfo":{"name":"test","version":"1"}}}"""
  }
}
