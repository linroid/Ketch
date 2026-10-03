package com.linroid.ketch.mcp

import ai.koog.agents.core.tools.ToolRegistry
import com.linroid.ketch.api.DownloadConfig
import com.linroid.ketch.api.DownloadRequest
import com.linroid.ketch.api.DownloadTask
import com.linroid.ketch.api.KetchApi
import com.linroid.ketch.api.KetchStatus
import com.linroid.ketch.api.ResolvedSource
import com.linroid.ketch.api.SystemInfo
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.io.Buffer
import kotlinx.io.IOException
import kotlinx.io.RawSink
import kotlinx.io.RawSource
import kotlinx.io.asSink
import kotlinx.io.asSource
import kotlinx.io.readString
import kotlinx.io.writeString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.CountDownLatch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail
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
  fun `serveStdio writes its responses to the output`() =
    runTest(timeout = 10.seconds) {
      val requests = PipedOutputStream()
      val input = PipedInputStream(requests).asSource()
      val output = FirstLineOutputStream()
      val server = launch(Dispatchers.IO) {
        serveStdio(ToolRegistry {}, input, output.asSink())
      }

      // The input stays open until the response arrives: replies must not wait for its end
      withContext(Dispatchers.IO) {
        requests.write((INITIALIZE + "\n").encodeToByteArray())
        requests.flush()
      }
      val response = Json.parseToJsonElement(output.firstLine.await()).jsonObject

      assertEquals(1, response["id"]?.jsonPrimitive?.int)
      assertNotNull(response["result"])
      withContext(Dispatchers.IO) { requests.close() }
      server.join()
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

  // The next two tests repeat a race: when a session ended right after it started, Server.close()
  // could wait forever for a notification collector that had not started yet. serveStdio runs
  // outside the test's scope, so a hang fails the test instead of keeping it from completing.
  @Test
  fun `serveStdio returns every time it is cancelled as the session starts`() = runBlocking {
    val scope = CoroutineScope(Dispatchers.Default)
    repeat(RACE_ROUNDS) { round ->
      val input = OpenInput()
      try {
        val serving = scope.launch(start = CoroutineStart.UNDISPATCHED) {
          serveStdio(ToolRegistry {}, input, Buffer())
        }
        serving.cancel()
        withTimeoutOrNull(10.seconds) { serving.join() } ?: fail("Hung in round $round")
      } finally {
        input.close()
      }
    }
  }

  @Test
  fun `serveStdio returns every time the input has already ended`() = runBlocking {
    val scope = CoroutineScope(Dispatchers.Default)
    repeat(RACE_ROUNDS) { round ->
      val serving = scope.launch { serveStdio(ToolRegistry {}, Buffer(), Buffer()) }
      withTimeoutOrNull(10.seconds) { serving.join() } ?: fail("Hung in round $round")
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

  @Test
  fun `startStdio answers a call to a KetchToolSet tool`() =
    runTest(timeout = 10.seconds) {
      val requests = listOf(INITIALIZE, INITIALIZED, LIST_DOWNLOADS)
      val output = ByteArrayOutputStream()
      KetchMcpServer(FakeKetchApi())
        .startStdio(requests.joinToString("\n", postfix = "\n").byteInputStream(), output)

      val replies = replies(Buffer().apply { write(output.toByteArray()) })
      assertEquals(listOf(1, 2).map(::JsonPrimitive), replies.map { it["id"] })
      val result = replies[1].getValue("result").jsonObject
      assertNull(result["isError"], "listDownloads failed: $result")
    }

  @Test
  fun `tools list requires only the parameters without a default value`() =
    runTest(timeout = 10.seconds) {
      val tools = result(FakeKetchApi(), TOOLS_LIST).getValue("tools").jsonArray
      val schemas = tools.associate { tool ->
        val name = tool.jsonObject.getValue("name").jsonPrimitive.content
        name to tool.jsonObject.getValue("inputSchema").jsonObject
      }

      assertEquals(
        mapOf(
          "listDownloads" to emptyList(),
          "getDownload" to listOf("taskId"),
          "startDownload" to listOf("url"),
          "pauseDownload" to listOf("taskId"),
          "resumeDownload" to listOf("taskId"),
          "cancelDownload" to listOf("taskId"),
          "removeDownload" to listOf("taskId"),
          "resolveUrl" to listOf("url"),
          "getStatus" to emptyList(),
          "setSpeedLimit" to listOf("taskId", "speedLimit"),
          "setPriority" to listOf("taskId", "priority"),
          "updateConfig" to emptyList(),
        ),
        schemas.mapValues { (_, schema) ->
          schema.getValue("required").jsonArray.map { it.jsonPrimitive.content }
        },
      )
      // Optional parameters are still described
      assertEquals(
        setOf("url", "destination", "connections", "priority", "speedLimit"),
        schemas.getValue("startDownload").getValue("properties").jsonObject.keys,
      )
    }

  @Test
  fun `tools call returns the JSON of a tool without encoding it again`() =
    runTest(timeout = 10.seconds) {
      val status = Json.parseToJsonElement(toolText(FakeKetchApi(), "getStatus"))

      assertEquals("Ketch", assertIs<JsonObject>(status)["name"]?.jsonPrimitive?.content)
    }

  @Test
  fun `tools call uses the default of a parameter the client leaves out`() =
    runTest(timeout = 10.seconds) {
      val ketch = FakeKetchApi()
      toolText(ketch, "updateConfig", buildJsonObject { put("maxConcurrentDownloads", 7) })

      assertEquals(DownloadConfig(maxConcurrentDownloads = 7), ketch.config)
    }

  /** The result a [KetchMcpServer] for [ketch] gives [request], sent after the handshake. */
  private suspend fun result(ketch: KetchApi, request: String): JsonObject {
    val requests = listOf(INITIALIZE, INITIALIZED, request)
    val output = ByteArrayOutputStream()
    KetchMcpServer(ketch)
      .startStdio(requests.joinToString("\n", postfix = "\n").byteInputStream(), output)

    val reply = replies(Buffer().apply { write(output.toByteArray()) }).last()
    return assertNotNull(reply["result"], "Request failed: $reply").jsonObject
  }

  /** Calls the tool [name] of a [KetchMcpServer] for [ketch] and returns the text of its result. */
  private suspend fun toolText(
    ketch: KetchApi,
    name: String,
    arguments: JsonObject = JsonObject(emptyMap()),
  ): String {
    val request = buildJsonObject {
      put("jsonrpc", "2.0")
      put("id", 2)
      put("method", "tools/call")
      put(
        "params",
        buildJsonObject {
          put("name", name)
          put("arguments", arguments)
        },
      )
    }
    val content = result(ketch, request.toString()).getValue("content").jsonArray.single()
    return content.jsonObject.getValue("text").jsonPrimitive.content
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

  private class FakeKetchApi : KetchApi {
    var config = DownloadConfig()
      private set

    override val backendLabel = "Fake"
    override val tasks: StateFlow<List<DownloadTask>> = MutableStateFlow(emptyList())

    override suspend fun download(request: DownloadRequest): DownloadTask =
      throw UnsupportedOperationException()

    override suspend fun resolve(url: String, properties: Map<String, String>): ResolvedSource =
      throw UnsupportedOperationException()

    override suspend fun resolveContent(content: ByteArray, fileName: String?): ResolvedSource =
      throw UnsupportedOperationException()

    override suspend fun start() {}

    override suspend fun status() = KetchStatus(
      name = "Ketch",
      version = "1.0.0",
      revision = "abc1234",
      uptime = 0,
      config = config,
      system = SystemInfo(
        os = "TestOS",
        arch = "test",
        separator = "/",
        javaVersion = "21",
        availableProcessors = 1,
        maxMemory = 0,
        totalMemory = 0,
        freeMemory = 0,
        downloadDirectory = "/downloads",
        totalSpace = 0,
        freeSpace = 0,
        usableSpace = 0,
      ),
    )

    override suspend fun updateConfig(config: DownloadConfig) {
      this.config = config
    }

    override fun close() {}
  }

  private fun replies(output: Buffer): List<JsonObject> =
    output.readString().lines().filter { it.isNotBlank() }
      .map { Json.parseToJsonElement(it).jsonObject }

  private companion object {
    const val RACE_ROUNDS = 2000
    const val INITIALIZE = """{"jsonrpc":"2.0","id":1,"method":"initialize",""" +
      """"params":{"protocolVersion":"2025-06-18","capabilities":{},""" +
      """"clientInfo":{"name":"test","version":"1"}}}"""
    const val INITIALIZED = """{"jsonrpc":"2.0","method":"notifications/initialized"}"""
    const val TOOLS_LIST = """{"jsonrpc":"2.0","id":2,"method":"tools/list"}"""
    const val PING = """{"jsonrpc":"2.0","id":3,"method":"ping"}"""
    const val LIST_DOWNLOADS = """{"jsonrpc":"2.0","id":2,"method":"tools/call",""" +
      """"params":{"name":"listDownloads","arguments":{}}}"""
  }
}
