package com.linroid.kdown

import com.linroid.kdown.error.KDownError
import com.linroid.kdown.model.DownloadProgress
import com.linroid.kdown.model.DownloadState
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
    assertFalse(DownloadState.Paused.isTerminal)
  }

  @Test
  fun paused_isNotActive() {
    assertFalse(DownloadState.Paused.isActive)
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
