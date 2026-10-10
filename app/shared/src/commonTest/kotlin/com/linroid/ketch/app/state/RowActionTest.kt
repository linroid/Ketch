package com.linroid.ketch.app.state

import com.linroid.ketch.api.DownloadProgress
import com.linroid.ketch.api.DownloadRequest
import com.linroid.ketch.api.DownloadSchedule
import com.linroid.ketch.api.DownloadState
import com.linroid.ketch.api.KetchError
import com.linroid.ketch.api.PauseReason
import com.linroid.ketch.app.i18n.load
import com.linroid.ketch.app.i18n.verbatim
import com.linroid.ketch.app.util.toCopy
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours

class RowActionTest {
  private val request = DownloadRequest("https://example.com/a.iso")
  private val local = DeviceInfo(verbatim("This Mac"), RowCapabilities.local(canTrash = true))
  private val remote = DeviceInfo(verbatim("NAS-Basement"), RowCapabilities.remote())
  private val progress = DownloadProgress(10, 100, 5)

  private val errors = listOf(
    KetchError.Network(),
    KetchError.Http(403),
    KetchError.Disk(),
    KetchError.Unsupported(),
    KetchError.FileChanged("ETag changed"),
    KetchError.CorruptResumeState(),
    KetchError.Canceled(),
    KetchError.SourceError("torrent"),
    KetchError.AuthenticationFailed("ftp"),
    KetchError.Unknown()
  )

  private val states: List<DownloadState> = listOf(
    DownloadState.Scheduled(DownloadSchedule.AfterDelay(1.hours)),
    DownloadState.Queued,
    DownloadState.Downloading(progress),
    DownloadState.Paused(progress),
    DownloadState.Completed("/tmp/a.iso"),
    DownloadState.Canceled
  ) + errors.map { DownloadState.Failed(it) }

  @Test
  fun taskActions_everyStateAndDevice_keepsMenuConsistent() {
    for (device in listOf(local, remote)) {
      for (state in states) {
        val actions = taskActions(request, state, device)
        val label = "$state on ${device.name}"

        actions.primary?.let { assertTrue(it in actions.menu, "primary in menu for $label") }
        if (state is DownloadState.Failed) {
          val fix = state.error.toCopy(request, retryCount = 0, device = device).primary
          assertEquals(fix, actions.primary, "error fix leads for $label")
        }
        assertEquals(actions.menu.distinct(), actions.menu, "no duplicates for $label")
        val firstDestructive = actions.menu.indexOfFirst { it.destructive }
        assertTrue(firstDestructive >= 0, "a way to remove for $label")
        assertTrue(
          actions.menu.drop(firstDestructive).all { it.destructive },
          "destructive items last for $label"
        )
      }
    }
  }

  @Test
  fun taskActions_remote_neverReschedules() {
    for (state in states) {
      val actions = taskActions(request, state, remote)

      assertFalse(RowAction.StartLater in actions.menu, "no Start later for $state")
      assertFalse(RowAction.Open in actions.menu, "no Open for $state")
    }
  }

  @Test
  fun taskActions_scheduledOnRemote_hidesStartNow() {
    val scheduled = DownloadState.Scheduled(DownloadSchedule.AfterDelay(1.hours))

    val actions = taskActions(request, scheduled, remote)

    assertNull(actions.primary)
    assertFalse(RowAction.StartNow in actions.menu)
  }

  @Test
  fun taskActions_scheduledLocally_startsNowOrLater() {
    val scheduled = DownloadState.Scheduled(DownloadSchedule.AfterDelay(1.hours))

    val actions = taskActions(request, scheduled, local)

    assertEquals(RowAction.StartNow, actions.primary)
    assertTrue(RowAction.StartLater in actions.menu)
  }

  @Test
  fun taskActions_restartOnlyFailures_downloadAgain() {
    val restartOnly = listOf(
      DownloadState.Canceled,
      DownloadState.Failed(KetchError.FileChanged("ETag changed")),
      DownloadState.Failed(KetchError.CorruptResumeState()),
      DownloadState.Failed(KetchError.Http(416)),
      DownloadState.Failed(KetchError.Canceled())
    )

    for (device in listOf(local, remote)) {
      for (state in restartOnly) {
        val actions = taskActions(request, state, device)

        assertEquals(RowAction.DownloadAgain, actions.primary, "primary for $state")
        assertFalse(RowAction.Retry in actions.menu, "no Retry for $state")
      }
    }
  }

  @Test
  fun taskActions_failed_leadsWithErrorFix() {
    val actions = taskActions(request, DownloadState.Failed(KetchError.Http(401)), local)

    assertEquals(RowAction.EditLink, actions.primary)
    assertEquals(
      listOf(RowAction.EditLink, RowAction.CopyLink, RowAction.Retry),
      actions.menu.take(3)
    )
    assertFalse(RowAction.RetryWithOptions in actions.menu)
  }

  @Test
  fun taskActions_failedNetwork_retries() {
    val actions = taskActions(request, DownloadState.Failed(KetchError.Network()), remote)

    assertEquals(RowAction.Retry, actions.primary)
    assertEquals(listOf(RowAction.Retry), actions.hover)
    assertTrue(RowAction.RetryWithOptions in actions.menu)
  }

  @Test
  fun taskActions_downloading_pausesOrReconnects() {
    val downloading = DownloadState.Downloading(progress)

    val running = taskActions(request, downloading, local)
    val stalled = taskActions(request, downloading, local, stalled = true)

    assertEquals(RowAction.Pause, running.primary)
    assertEquals(RowAction.Reconnect, stalled.primary)
    assertEquals(listOf(RowAction.Reconnect, RowAction.Pause), stalled.menu.take(2))
  }

  @Test
  fun taskActions_queued_startsNowAndPauses() {
    val actions = taskActions(request, DownloadState.Queued, remote)

    assertEquals(RowAction.StartNow, actions.primary)
    assertEquals(listOf(RowAction.Pause, RowAction.StartNow), actions.menu.take(2))
  }

  @Test
  fun taskActions_starting_pausesWithoutStartNow() {
    val actions = taskActions(request, DownloadState.Queued, remote, starting = true)

    assertEquals(RowAction.Pause, actions.primary)
    assertTrue(RowAction.StartNow !in actions.menu)
  }

  @Test
  fun taskActions_preempted_primaryIsStartNow() {
    val preempted = DownloadState.Paused(progress, PauseReason.Preempted("urgent"))

    val actions = taskActions(request, preempted, local)

    assertEquals(RowAction.StartNow, actions.primary)
    assertTrue(RowAction.Pause in actions.menu)
    assertFalse(RowAction.Resume in actions.menu)
  }

  @Test
  fun taskActions_completedLocal_opensAndTrashes() {
    val actions = taskActions(request, DownloadState.Completed("/tmp/a.iso"), local)

    assertEquals(RowAction.Open, actions.primary)
    assertEquals(listOf(RowAction.Open, RowAction.ShowInFolder), actions.hover)
    assertEquals(RowAction.RemoveAndTrash, actions.menu.last())
  }

  @Test
  fun taskActions_completedRemote_copiesPathAndDeletes() {
    val actions = taskActions(request, DownloadState.Completed("/srv/a.iso"), remote)

    assertEquals(RowAction.CopyPath, actions.primary)
    assertEquals(RowAction.RemoveAndDelete, actions.menu.last())
  }

  @Test
  fun taskActions_completedFileMissing_downloadsAgainOnlyLocally() {
    val completed = DownloadState.Completed("/tmp/a.iso")

    val missing = taskActions(request, completed, local, fileMissing = true)
    val remoteMissing = taskActions(request, completed, remote, fileMissing = true)

    assertEquals(RowAction.DownloadAgain, missing.primary)
    assertFalse(RowAction.Open in missing.menu)
    assertEquals(RowAction.CopyPath, remoteMissing.primary)
  }

  @Test
  fun retryWithConnections_label_countsConnections() = runTest {
    assertEquals("Retry with 1 connection", RowAction.RetryWithConnections(1).label.load())
    assertEquals("Retry with 2 connections", RowAction.RetryWithConnections(2).label.load())
  }
}
