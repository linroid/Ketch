package com.linroid.ketch.cli

import com.linroid.ketch.api.Destination
import com.linroid.ketch.api.DownloadCondition
import com.linroid.ketch.api.DownloadConfig
import com.linroid.ketch.api.DownloadPriority
import com.linroid.ketch.api.DownloadProgress
import com.linroid.ketch.api.DownloadRequest
import com.linroid.ketch.api.DownloadSchedule
import com.linroid.ketch.api.DownloadState
import com.linroid.ketch.api.DownloadTask
import com.linroid.ketch.api.KetchApi
import com.linroid.ketch.api.KetchError
import com.linroid.ketch.api.KetchStatus
import com.linroid.ketch.api.ResolvedSource
import com.linroid.ketch.api.Segment
import com.linroid.ketch.api.SpeedLimit
import com.linroid.ketch.remote.ConnectionState
import com.linroid.ketch.remote.RemoteApiException
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.PrintStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Instant

class InstanceCommandsTest {
  private val endpoint = InstanceEndpoint("the Ketch app", "127.0.0.1", 5123, false, "t", true)
  private val requestId = "6f1c2b0e-8a1d-4c5e-9b7a-3d2f1e0c9b8a"

  @Test
  fun `submitDownload sends a request with an ID again after the connection fails`() = runTest {
    val ketch = FakeKetch(failures = mutableListOf(IOException("Connection reset")))

    val task = submitDownload(ketch, request(requestId), endpoint, retryDelay = Duration.ZERO)

    assertEquals(listOf(requestId, requestId), ketch.submitted.map { it.requestId })
    assertEquals(requestId, task.request.requestId)
  }

  @Test
  fun `submitDownload never sends a request without an ID twice`() = runTest {
    val ketch = FakeKetch(failures = mutableListOf(IOException("Connection reset")))

    val failure = assertFailsWith<CliFailure> {
      submitDownload(ketch, request(null), endpoint, retryDelay = Duration.ZERO)
    }

    assertEquals(1, ketch.submitted.size)
    assertTrue(failure.message.orEmpty().startsWith("Couldn't tell whether the Ketch app added"))
  }

  @Test
  fun `submitDownload names the request ID to retry with once it gives up`() = runTest {
    val failures = MutableList<Exception>(3) { IOException("Connection refused") }
    val ketch = FakeKetch(failures = failures)

    val failure = assertFailsWith<CliFailure> {
      submitDownload(ketch, request(requestId), endpoint, retryDelay = Duration.ZERO)
    }

    assertEquals(3, ketch.submitted.size)
    assertTrue("--idempotency-key $requestId" in failure.message.orEmpty())
  }

  @Test
  fun `submitDownload does not retry what the instance refused`() = runTest {
    val refusal = RemoteApiException(400, "bad_request", "Request ID already belongs to another")
    val ketch = FakeKetch(failures = mutableListOf(refusal))

    val failure = assertFailsWith<CliFailure> {
      submitDownload(ketch, request(requestId), endpoint, retryDelay = Duration.ZERO)
    }

    assertEquals(1, ketch.submitted.size)
    assertEquals(
      "the Ketch app refused the request: Request ID already belongs to another",
      failure.message,
    )
  }

  @Test
  fun `submitDownload retries an internal error of the instance`() = runTest {
    val ketch = FakeKetch(failures = mutableListOf(RemoteApiException(500, null, "HTTP 500")))

    submitDownload(ketch, request(requestId), endpoint, retryDelay = Duration.ZERO)

    assertEquals(2, ketch.submitted.size)
  }

  @Test
  fun `matchTasks takes full IDs and unique prefixes once each`() {
    val tasks = listOf(task("3f2a9c1e-1"), task("3f2b0000-2"), task("9c1e0000-3"))

    val (matched, problems) =
      matchTasks(tasks, listOf("9C1E", "3f2a9c1e-1", "3f2a", "3f2", "ffff", ""))

    assertEquals(listOf("9c1e0000-3", "3f2a9c1e-1"), matched.map { it.taskId })
    assertEquals(
      listOf(
        "'3f2' matches 2 tasks; give more of the task ID",
        "no task matches 'ffff'",
        "no task matches ''",
      ),
      problems,
    )
  }

  @Test
  fun `destinationFor resolves local paths against the working directory`() {
    val cwd = File("/home/me/work")
    val sep = File.separatorChar

    assertEquals(null, destinationFor(null, local = true, cwd))
    assertEquals(
      Destination(File("/home/me/work/a.iso").path),
      destinationFor("a.iso", local = true, cwd) { false },
    )
    assertEquals(
      Destination(File("/home/me/iso").path + sep),
      destinationFor("../iso/", local = true, cwd) { false },
    )
    assertEquals(
      Destination(File("/home/me/work/out").path + sep),
      destinationFor("out", local = true, cwd) { it.name == "out" },
    )
  }

  @Test
  fun `destinationFor passes another device's paths as they are`() {
    assertEquals(Destination("media/a.iso"), destinationFor("media/a.iso", local = false))
  }

  @Test
  fun `watchTasks prints a snapshot, then state and progress changes`() = runTest {
    val task = task("3f2a9c1e-1", DownloadState.Queued)
    val ketch = FakeKetch(tasks = listOf(task))
    val out = ByteArrayOutputStream()

    val status = async {
      watchTasks(ketch, connected(), listOf("3f2a"), PrintStream(out), PrintStream(out), "app")
    }
    runCurrent()
    task.state.value = DownloadState.Downloading(DownloadProgress(10, 100))
    runCurrent()
    task.state.value = DownloadState.Downloading(DownloadProgress(50, 100, 1000))
    runCurrent()
    task.state.value = DownloadState.Completed("/d/a.iso", 100)

    assertEquals(InstanceExit.OK, status.await())
    val lines = out.toString().lines().filter { it.isNotBlank() }.map {
      Json.parseToJsonElement(it).jsonObject
    }
    assertEquals(
      listOf("snapshot", "state", "progress", "state"),
      lines.map { it.getValue("event").jsonPrimitive.content },
    )
    assertEquals(
      listOf("queued", "downloading", "downloading", "completed"),
      lines.map { it.getValue("state").jsonPrimitive.content },
    )
  }

  @Test
  fun `watchTasks fails when a watched task fails or is removed`() = runTest {
    val failing = task("aaaa0000-1", DownloadState.Queued)
    val removed = task("bbbb0000-2", DownloadState.Queued)
    val ketch = FakeKetch(tasks = listOf(failing, removed))
    val out = ByteArrayOutputStream()

    val status = async {
      watchTasks(ketch, connected(), listOf("aaaa", "bbbb"), PrintStream(out), PrintStream(out), "")
    }
    runCurrent()
    failing.state.value = DownloadState.Failed(KetchError.Network(null))
    runCurrent()
    ketch.tasks.value = listOf(failing)

    assertEquals(InstanceExit.FAILED, status.await())
    assertTrue("""{"event":"removed","taskId":"bbbb0000-2"}""" in out.toString())
  }

  @Test
  fun `watchTasks ends when the connection is lost`() = runTest {
    val connection = connected()
    val err = ByteArrayOutputStream()

    val status = async {
      watchTasks(
        FakeKetch(listOf(task("a"))), connection, emptyList(),
        PrintStream(ByteArrayOutputStream()), PrintStream(err), "the Ketch app",
      )
    }
    runCurrent()
    connection.value = ConnectionState.Disconnected()

    assertEquals(InstanceExit.FAILED, status.await())
    assertTrue("lost the connection to the Ketch app" in err.toString())
  }

  @Test
  fun `watchTasks refuses an ID that matches no task`() = runTest {
    assertFailsWith<CliFailure> {
      watchTasks(
        FakeKetch(), connected(), listOf("nope"),
        PrintStream(ByteArrayOutputStream()), PrintStream(ByteArrayOutputStream()), "",
      )
    }
  }

  private fun connected() = MutableStateFlow<ConnectionState>(ConnectionState.Connected)

  private fun request(id: String?) =
    DownloadRequest(url = "https://example.com/a.iso", requestId = id)

  private fun task(id: String, state: DownloadState = DownloadState.Queued) =
    FakeTask(id, DownloadRequest(url = "https://example.com/$id.iso"), state)
}

private class FakeKetch(
  tasks: List<DownloadTask> = emptyList(),
  private val failures: MutableList<out Exception> = mutableListOf(),
) : KetchApi {
  val submitted = mutableListOf<DownloadRequest>()
  override val backendLabel = "Fake"
  override val tasks = MutableStateFlow(tasks)

  override suspend fun download(request: DownloadRequest): DownloadTask {
    submitted += request
    failures.removeFirstOrNull()?.let { throw it }
    return FakeTask("new", request, DownloadState.Queued)
  }

  override suspend fun resolve(url: String, properties: Map<String, String>): ResolvedSource =
    throw UnsupportedOperationException()

  override suspend fun start() = Unit

  override suspend fun status(): KetchStatus = throw UnsupportedOperationException()

  override suspend fun updateConfig(config: DownloadConfig) = Unit

  override fun close() = Unit
}

internal class FakeTask(
  override val taskId: String,
  override val request: DownloadRequest,
  initialState: DownloadState,
) : DownloadTask {
  override val requestState: StateFlow<DownloadRequest> = MutableStateFlow(request)
  override val createdAt: Instant = Instant.parse("2026-10-01T00:00:00Z")
  override val state = MutableStateFlow(initialState)
  override val segments: StateFlow<List<Segment>> = MutableStateFlow(emptyList())

  override suspend fun pause() = Unit
  override suspend fun resume(destination: Destination?) = Unit
  override suspend fun cancel() = Unit
  override suspend fun setSpeedLimit(limit: SpeedLimit) = Unit
  override suspend fun setPriority(priority: DownloadPriority) = Unit
  override suspend fun setConnections(connections: Int) = Unit
  override suspend fun reschedule(schedule: DownloadSchedule, conditions: List<DownloadCondition>) =
    Unit
  override suspend fun remove(deleteFiles: Boolean) = Unit
}
