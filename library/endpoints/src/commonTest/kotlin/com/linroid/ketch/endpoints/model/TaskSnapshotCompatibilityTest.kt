package com.linroid.ketch.endpoints.model

import com.linroid.ketch.api.DownloadProgress
import com.linroid.ketch.api.DownloadState
import com.linroid.ketch.api.PauseReason
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.seconds

class TaskSnapshotCompatibilityTest {
  // Configured like RemoteKetch's, which decodes what servers send.
  private val json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    isLenient = true
    coerceInputValues = true
  }

  @Test
  fun decode_snapshotFromOlderServer_defaultsNewFields() {
    val response = json.decodeFromString<TasksResponse>(
      """
      {"tasks":[
        {"taskId":"paused","request":{"url":"https://example.com/a"},
         "state":{"type":"paused","progress":{"downloadedBytes":10,"totalBytes":100}},
         "segments":[],"createdAt":"2026-10-03T08:00:00Z"},
        {"taskId":"done","request":{"url":"https://example.com/b"},
         "state":{"type":"completed","outputPath":"/d/b.iso","totalBytes":100,
                  "downloadTime":"PT3S"},
         "segments":[],"createdAt":"2026-10-03T08:00:00Z"}
      ]}
      """.trimIndent(),
    )

    val (paused, done) = response.tasks
    assertEquals(
      DownloadState.Paused(DownloadProgress(10, 100), PauseReason.User),
      paused.state,
    )
    assertNull(paused.queuePosition)
    val completed = assertIs<DownloadState.Completed>(done.state)
    assertEquals(3.seconds, completed.downloadTime)
    assertNull(completed.completedAt)
    assertNull(done.queuePosition)
  }

  @Test
  fun decode_stateChangedFromOlderServer_hasNoQueuePosition() {
    val event = json.decodeFromString<TaskEvent>(
      """{"type":"state_changed","taskId":"a","state":{"type":"queued"}}""",
    )

    val changed = assertIs<TaskEvent.StateChanged>(event)
    assertEquals(DownloadState.Queued, changed.state)
    assertNull(changed.queuePosition)
  }

  @Test
  fun decode_pausedWithUnknownReasonType_decodesAsUser() {
    // A reason added by a newer server must not break the event stream.
    val event = json.decodeFromString<TaskEvent>(
      """
      {"type":"state_changed","taskId":"a",
       "state":{"type":"paused","progress":{"downloadedBytes":5,"totalBytes":10},
                "reason":{"type":"thermal","celsius":90}},
       "queuePosition":3}
      """.trimIndent(),
    )

    val changed = assertIs<TaskEvent.StateChanged>(event)
    assertEquals(
      DownloadState.Paused(DownloadProgress(5, 10), PauseReason.User),
      changed.state,
    )
    assertEquals(3, changed.queuePosition)
  }
}
