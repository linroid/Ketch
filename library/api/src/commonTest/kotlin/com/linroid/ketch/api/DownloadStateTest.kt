package com.linroid.ketch.api

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class DownloadStateTest {

  @Test
  fun scheduled_isNotTerminal() {
    val state = DownloadState.Scheduled(DownloadSchedule.Immediate)
    assertFalse(state.isTerminal)
  }

  @Test
  fun scheduled_isNotActive() {
    val state = DownloadState.Scheduled(DownloadSchedule.Immediate)
    assertFalse(state.isActive)
  }

  @Test
  fun queued_isNotTerminal() {
    assertFalse(DownloadState.Queued.isTerminal)
  }

  @Test
  fun queued_isNotActive() {
    assertFalse(DownloadState.Queued.isActive)
  }

  @Test
  fun downloading_isNotTerminal() {
    val state = DownloadState.Downloading(DownloadProgress(50, 100))
    assertFalse(state.isTerminal)
  }

  @Test
  fun downloading_isActive() {
    val state = DownloadState.Downloading(DownloadProgress(50, 100))
    assertTrue(state.isActive)
  }

  @Test
  fun paused_isNotTerminal() {
    assertFalse(DownloadState.Paused(DownloadProgress(50, 100)).isTerminal)
  }

  @Test
  fun paused_isNotActive() {
    assertFalse(DownloadState.Paused(DownloadProgress(50, 100)).isActive)
  }

  @Test
  fun completed_isTerminal() {
    assertTrue(DownloadState.Completed("/path/file").isTerminal)
  }

  @Test
  fun completed_isNotActive() {
    assertFalse(DownloadState.Completed("/path/file").isActive)
  }

  @Test
  fun failed_isTerminal() {
    assertTrue(DownloadState.Failed(KetchError.Network()).isTerminal)
  }

  @Test
  fun failed_isNotActive() {
    assertFalse(DownloadState.Failed(KetchError.Network()).isActive)
  }

  @Test
  fun canceled_isTerminal() {
    assertTrue(DownloadState.Canceled.isTerminal)
  }

  @Test
  fun canceled_isNotActive() {
    assertFalse(DownloadState.Canceled.isActive)
  }

  @Test
  fun paused_jsonWithoutReason_decodesAsUserPause() {
    // Older servers and records send no reason.
    val json = """{"type":"paused","progress":{"downloadedBytes":10,"totalBytes":100}}"""

    val state = Json.decodeFromString(DownloadState.serializer(), json)

    assertEquals(DownloadState.Paused(DownloadProgress(10, 100), PauseReason.User), state)
  }

  @Test
  fun pauseReason_preempted_encodesTypeAndTaskId() {
    val json = Json.encodeToString(PauseReason.serializer(), PauseReason.Preempted("b"))

    assertEquals("""{"type":"preempted","byTaskId":"b"}""", json)
  }

  @Test
  fun pauseReason_unknownType_decodesAsUser() {
    // A newer server may send a reason this version does not know.
    val json = """
      {"type":"paused","progress":{"downloadedBytes":10,"totalBytes":100},
       "reason":{"type":"thermal","celsius":90}}
    """.trimIndent()

    val state = Json { ignoreUnknownKeys = true }
      .decodeFromString(DownloadState.serializer(), json)

    assertEquals(PauseReason.User, (state as DownloadState.Paused).reason)
  }

  @Test
  fun pauseReason_unknownTypeWithItsOwnFields_decodesAsUserWithDefaultJson() {
    // Json.Default rejects unknown keys; the reason's own fields must not trip it.
    val reason = Json.decodeFromString(
      PauseReason.serializer(),
      """{"type":"thermal","celsius":90}""",
    )
    val preempted = Json.decodeFromString(
      PauseReason.serializer(),
      """{"type":"preempted","byTaskId":"b","since":"2026-10-03T08:00:00Z"}""",
    )

    assertEquals(PauseReason.User, reason)
    assertEquals(PauseReason.Preempted("b"), preempted)
  }

  @Test
  fun pauseReason_preemptedWithoutTaskId_decodesAsUser() {
    val reason = Json.decodeFromString(PauseReason.serializer(), """{"type":"preempted"}""")

    assertEquals(PauseReason.User, reason)
  }

  @Test
  fun completed_jsonWithoutCompletedAt_decodesNull() {
    // Written before finish times were recorded.
    val json = """
      {"type":"completed","outputPath":"/d/a.iso","totalBytes":100,"downloadTime":"PT3S"}
    """.trimIndent()

    val state = Json.decodeFromString(DownloadState.serializer(), json)

    val completed = state as DownloadState.Completed
    assertEquals(3.seconds, completed.downloadTime)
    assertNull(completed.completedAt)
  }
}
