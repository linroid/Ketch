package com.linroid.ketch.mcp

import com.linroid.ketch.api.DownloadConfig
import com.linroid.ketch.api.DownloadRequest
import com.linroid.ketch.api.DownloadTask
import com.linroid.ketch.api.KetchApi
import com.linroid.ketch.api.KetchStatus
import com.linroid.ketch.api.ResolvedSource
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
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

class KetchMcpServerTest {
  @Test
  fun startStdio_givenStreams_answersOnOutput() = runTest {
    val requests = PipedOutputStream()
    val input = PipedInputStream(requests)
    val output = FirstLineOutputStream()
    val server = launch(Dispatchers.IO) {
      KetchMcpServer(UnusedKetchApi).startStdio(input, output)
    }

    // The input stays open: the transport closes on end of input, dropping pending replies
    withContext(Dispatchers.IO) {
      requests.write(INITIALIZE_REQUEST.encodeToByteArray())
      requests.flush()
    }
    val response = Json.parseToJsonElement(output.firstLine.await()).jsonObject

    assertEquals(1, response["id"]?.jsonPrimitive?.int)
    assertNotNull(response["result"])
    server.cancel()
    withContext(Dispatchers.IO) { requests.close() }
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

  /** The initialize handshake never touches Ketch. */
  private object UnusedKetchApi : KetchApi {
    override val backendLabel: String get() = error("unused")
    override val tasks: StateFlow<List<DownloadTask>> get() = error("unused")

    override suspend fun download(request: DownloadRequest): DownloadTask = error("unused")

    override suspend fun resolve(
      url: String,
      properties: Map<String, String>,
    ): ResolvedSource = error("unused")

    override suspend fun start() = error("unused")

    override suspend fun status(): KetchStatus = error("unused")

    override suspend fun updateConfig(config: DownloadConfig) = error("unused")

    override fun close() = error("unused")
  }

  private companion object {
    const val INITIALIZE_REQUEST =
      """{"jsonrpc":"2.0","id":1,"method":"initialize","params":""" +
        """{"protocolVersion":"2025-06-18","capabilities":{},""" +
        """"clientInfo":{"name":"test","version":"1"}}}""" + "\n"
  }
}
