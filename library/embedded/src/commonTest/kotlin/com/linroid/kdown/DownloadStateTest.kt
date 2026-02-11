package com.linroid.kdown

import com.linroid.kdown.error.KDownError
import kotlinx.io.files.Path
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DownloadStateTest {

  @Test
  fun idle_isNotTerminal() {
    assertFalse(DownloadState.Idle.isTerminal)
  }

  @Test
  fun idle_isNotActive() {
    assertFalse(DownloadState.Idle.isActive)
  }

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
  fun pending_isNotTerminal() {
    assertFalse(DownloadState.Pending.isTerminal)
  }

  @Test
  fun pending_isActive() {
    assertTrue(DownloadState.Pending.isActive)
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
    assertTrue(DownloadState.Completed(Path("/path/file")).isTerminal)
  }

  @Test
  fun completed_isNotActive() {
    assertFalse(DownloadState.Completed(Path("/path/file")).isActive)
  }

  @Test
  fun failed_isTerminal() {
    assertTrue(DownloadState.Failed(KDownError.Network()).isTerminal)
  }

  @Test
  fun failed_isNotActive() {
    assertFalse(DownloadState.Failed(KDownError.Network()).isActive)
  }

  @Test
  fun canceled_isTerminal() {
    assertTrue(DownloadState.Canceled.isTerminal)
  }

  @Test
  fun canceled_isNotActive() {
    assertFalse(DownloadState.Canceled.isActive)
  }
}
