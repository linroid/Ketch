package com.linroid.ketch.cli

import com.linroid.ketch.api.DownloadState
import com.linroid.ketch.core.Ketch
import com.linroid.ketch.core.engine.HttpEngine
import com.linroid.ketch.core.engine.ServerInfo
import com.linroid.ketch.server.KetchServer
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The attaching commands against a real server, as `ketch` runs them against the app. */
class AttachedCommandsTest {
  private val downloads = createTempDirectory("ketch-attached").toFile()
  private val ketch = Ketch(HangingHttpEngine())
  private val server = KetchServer(
    ketch,
    host = "127.0.0.1",
    port = 0,
    apiToken = "secret",
    mdnsEnabled = false,
  )
  private val endpoint: InstanceEndpoint

  init {
    server.start(wait = false)
    val port = runBlocking { server.port() }
    endpoint = InstanceEndpoint("the test server", "127.0.0.1", port, false, "secret", true)
  }

  @AfterTest
  fun cleanUp() {
    server.stop()
    ketch.close()
    downloads.deleteRecursively()
  }

  @Test
  fun `add with an idempotency key adds the download once`() {
    val key = listOf("--idempotency-key", "job-42", "https://example.com/a.iso", downloads.path + "/")

    val first = run("add", key)
    val second = run("add", key)

    assertEquals(InstanceExit.OK, first.status, first.err)
    assertEquals(first.out, second.out)
    assertEquals(1, ketch.tasks.value.size)
    val task = ketch.tasks.value.single()
    assertEquals(first.out.trim(), task.taskId)
    assertEquals(requestIdFor("job-42"), task.request.requestId)
    assertEquals(mapOf("ketch.origin" to "cli"), task.request.properties)
    assertEquals(downloads.path + "/", task.request.destination?.value)
  }

  @Test
  fun `the same key for another download is refused`() {
    run("add", listOf("--idempotency-key", "job-42", "https://example.com/a.iso"))

    val second = run("add", listOf("--idempotency-key", "job-42", "https://example.com/b.iso"))

    assertEquals(InstanceExit.FAILED, second.status)
    assertTrue("different download request" in second.err, second.err)
    assertEquals(1, ketch.tasks.value.size)
  }

  @Test
  fun `list prints the tasks as JSON, and pause pauses one by a prefix of its ID`() {
    val id = run("add", listOf("https://example.com/a.iso")).out.trim()

    val listed = run("list", listOf("--json"))
    val paused = run("pause", listOf(id.take(6)))

    val tasks = Json.parseToJsonElement(listed.out).jsonArray
    assertEquals(id, tasks.single().jsonObject.getValue("taskId").jsonPrimitive.content)
    assertEquals(InstanceExit.OK, paused.status, paused.err)
    assertTrue(paused.out.startsWith(id.take(SHORT_ID_LENGTH)), paused.out)
    val state = ketch.tasks.value.single().state.value
    assertTrue(state is DownloadState.Paused, "$state")
  }

  @Test
  fun `a wrong token is reported as such`() {
    val result = run("list", emptyList(), endpoint.copy(token = "wrong"))

    assertEquals(InstanceExit.FAILED, result.status)
    assertEquals("Error: the test server refused the access token.", result.err.trim())
  }

  private class Result(val status: Int, val out: String, val err: String)

  private fun run(
    command: String,
    args: List<String>,
    target: InstanceEndpoint = endpoint,
  ): Result {
    val parsed = parseInstanceArgs(command, args) as InstanceArgs.Run
    val out = ByteArrayOutputStream()
    val err = ByteArrayOutputStream()
    val status = runBlocking {
      runInstanceCommand(parsed.command, { target }, PrintStream(out), PrintStream(err))
    }
    return Result(status, out.toString(), err.toString())
  }
}

/** Never answers, so downloads stay where they are while the commands look at them. */
private class HangingHttpEngine : HttpEngine {
  override suspend fun head(url: String, headers: Map<String, String>): ServerInfo =
    awaitCancellation()

  override suspend fun download(
    url: String,
    range: LongRange?,
    headers: Map<String, String>,
    onData: suspend (ByteArray) -> Unit,
  ): Unit = awaitCancellation()

  override fun close() {}
}
