package com.linroid.ketch.remote

import com.linroid.ketch.api.DownloadProgress
import com.linroid.ketch.api.DownloadRequest
import com.linroid.ketch.api.DownloadState
import com.linroid.ketch.api.PauseReason
import com.linroid.ketch.endpoints.model.TaskEvent
import com.linroid.ketch.endpoints.model.TaskSnapshot
import com.linroid.ketch.endpoints.model.TasksResponse
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.headersOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

class RemoteKetchSnapshotTest {
  private fun snapshot(id: String) = TaskSnapshot(
    taskId = id,
    request = DownloadRequest("https://example.com/$id"),
    state = DownloadState.Queued,
    createdAt = Instant.fromEpochMilliseconds(0),
  )

  @Test
  fun stalledSnapshotDoesNotBlockAddOrRemoveOrRestoreStaleMembership() = runTest {
    withContext(Dispatchers.Default) {
      withTimeout(5_000) {
        coroutineScope {
          val old = snapshot("old")
          val fresh = snapshot("fresh")
          val started = CompletableDeferred<Unit>()
          val release = CompletableDeferred<Unit>()
          var posts = 0
          var gets = 0
          val engine = MockEngine { request ->
            val body = when (request.method) {
              HttpMethod.Post -> Json.encodeToString(if (posts++ == 0) old else fresh)
              HttpMethod.Delete -> ""
              else -> {
                val tasks = if (gets++ == 0) {
                  started.complete(Unit)
                  release.await()
                  listOf(old)
                } else listOf(fresh)
                Json.encodeToString(TasksResponse(tasks))
              }
            }
            respond(body, headers = headersOf(HttpHeaders.ContentType, "application/json"))
          }
          val remote = RemoteKetch("localhost", 8642, null, false, engine)
          val original = remote.download(old.request)
          val refresh = async { remote.fetchAllTasks() }
          try {
            started.await()
            val added = withTimeout(1_000) { remote.download(fresh.request) }
            withTimeout(1_000) { original.remove(deleteFiles = false) }
            assertSame(added, remote.tasks.value.single())
            release.complete(Unit)
            refresh.await()
            assertSame(added, remote.tasks.value.single())
            assertEquals(2, gets)
          } finally {
            refresh.cancelAndJoin()
            remote.close()
            engine.close()
          }
        }
      }
    }
  }

  @Test
  fun snapshotRacePreservesReturnedTaskAndNewerSseState() = runTest {
    withContext(Dispatchers.Default) {
      withTimeout(5_000) {
        coroutineScope {
          val original = snapshot("task")
          val completed = original.copy(state = DownloadState.Completed("/output"))
          val started = CompletableDeferred<Unit>()
          val release = CompletableDeferred<Unit>()
          var gets = 0
          val engine = MockEngine { request ->
            val body = if (request.method == HttpMethod.Post) Json.encodeToString(original) else {
              val task = if (gets++ == 0) {
                started.complete(Unit)
                release.await()
                original
              } else completed
              Json.encodeToString(TasksResponse(listOf(task)))
            }
            respond(body, headers = headersOf(HttpHeaders.ContentType, "application/json"))
          }
          val remote = RemoteKetch("localhost", 8642, null, false, engine)
          val task = remote.download(original.request)
          val refresh = async { remote.fetchAllTasks() }
          try {
            started.await()
            remote.handleEvent(TaskEvent.StateChanged(task.taskId, completed.state))
            release.complete(Unit)
            refresh.await()
            assertSame(task, remote.tasks.value.single())
            assertEquals(completed.state, task.state.value)
            // A late duplicate POST response must also preserve the SSE-owned instance and state.
            assertSame(task, remote.download(original.request))
            assertEquals(completed.state, task.state.value)
          } finally {
            refresh.cancelAndJoin()
            remote.close()
            engine.close()
          }
        }
      }
    }
  }

  @Test
  fun fetchAllTasks_olderServerJson_loadsTasksWithDefaults() = runTest {
    // Written by hand as a server without queue positions, pause reasons or finish times
    // sends it.
    val body = """
      {"tasks":[
        {"taskId":"paused","request":{"url":"https://example.com/a"},
         "state":{"type":"paused","progress":{"downloadedBytes":10,"totalBytes":100}},
         "segments":[],"createdAt":"2026-10-03T08:00:00Z"},
        {"taskId":"done","request":{"url":"https://example.com/b"},
         "state":{"type":"completed","outputPath":"/d/b.iso","totalBytes":100,
                  "downloadTime":"PT3S"},
         "segments":[],"createdAt":"2026-10-03T08:00:00Z"}
      ]}
    """.trimIndent()
    val engine = MockEngine {
      respond(body, headers = headersOf(HttpHeaders.ContentType, "application/json"))
    }
    val remote = RemoteKetch("localhost", 8642, null, false, engine)
    try {
      remote.fetchAllTasks()

      val tasks = remote.tasks.value.associateBy { it.taskId }
      val paused = tasks.getValue("paused")
      assertEquals(
        DownloadState.Paused(DownloadProgress(10, 100), PauseReason.User),
        paused.state.value,
      )
      assertNull(paused.queuePosition.value)
      val done = assertIs<DownloadState.Completed>(tasks.getValue("done").state.value)
      assertEquals(3.seconds, done.downloadTime)
      assertNull(done.completedAt)
    } finally {
      remote.close()
      engine.close()
    }
  }

  @Test
  fun handleEvent_stateChangedThenProgress_updatesAndClearsPosition() = runTest {
    val waiting = snapshot("task").copy(queuePosition = 2)
    val engine = MockEngine {
      respond(
        Json.encodeToString(waiting),
        headers = headersOf(HttpHeaders.ContentType, "application/json"),
      )
    }
    val remote = RemoteKetch("localhost", 8642, null, false, engine)
    try {
      val task = remote.download(waiting.request)
      assertEquals(2, task.queuePosition.value)

      remote.handleEvent(
        TaskEvent.StateChanged(task.taskId, DownloadState.Queued, queuePosition = 1),
      )
      assertEquals(1, task.queuePosition.value)

      val downloading = DownloadState.Downloading(DownloadProgress(0, 100))
      remote.handleEvent(TaskEvent.Progress(task.taskId, downloading))
      assertEquals(downloading, task.state.value)
      assertNull(task.queuePosition.value)
    } finally {
      remote.close()
      engine.close()
    }
  }
}
