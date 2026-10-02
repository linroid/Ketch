package com.linroid.ketch.app

import com.linroid.ketch.api.DownloadState
import com.linroid.ketch.app.state.RecordingKetchApi
import com.linroid.ketch.app.state.RecordingTask
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ContinuedDownloadsTest {

  private val processing = RecordingProcessing()
  private var inFront = true
  private val continued = ContinuedDownloads(processing) { inFront }
  private val progress = BackgroundProgress("Downloading file.bin", "10 B of 100 B", 100)

  @Test
  fun update_downloadStartsInFront_beginsWithItsProgress() {
    continued.update(progress)

    assertEquals(listOf("begin Downloading file.bin"), processing.calls)
    assertTrue(continued.running.value)
  }

  @Test
  fun update_inBackground_doesNotBegin() {
    inFront = false

    continued.update(progress)

    assertTrue(processing.calls.isEmpty())
    assertFalse(continued.running.value)
  }

  @Test
  fun update_whileRunning_updatesTheProgress() {
    continued.update(progress)

    continued.update(progress.copy(permille = 400))

    assertEquals(listOf("begin Downloading file.bin", "update 400"), processing.calls)
  }

  @Test
  fun update_nothingLeft_endsSuccessfully() {
    continued.update(progress)

    continued.update(null)

    assertEquals("end true", processing.calls.last())
    assertFalse(continued.running.value)
  }

  @Test
  fun update_refused_asksAgainOnlyAfterDownloadsStop() {
    processing.accepts = false
    continued.update(progress)
    continued.update(progress)
    processing.accepts = true

    continued.update(null)
    continued.update(progress)

    assertEquals(
      listOf("begin Downloading file.bin", "begin Downloading file.bin"),
      processing.calls,
    )
    assertTrue(continued.running.value)
  }

  @Test
  fun expire_whileRunning_stopsWithoutAskingAgain() {
    continued.update(progress)

    continued.expire()
    continued.update(progress)

    assertEquals(listOf("begin Downloading file.bin"), processing.calls)
    assertFalse(continued.running.value)
  }

  @Test
  fun close_whileRunning_endsUnsuccessfully() {
    continued.update(progress)

    continued.close()

    assertEquals("end false", processing.calls.last())
    assertFalse(continued.running.value)
  }

  @Test
  fun close_notRunning_endsNothing() {
    continued.close()

    assertTrue(processing.calls.isEmpty())
  }

  @Test
  fun backgroundProgress_oneDownload_namesItsFile() {
    val api = RecordingKetchApi()
    api.add(DownloadState.Downloading(RecordingTask.PROGRESS))

    val shown = backgroundProgress(api.tasks.value)

    assertEquals("Downloading file1.bin", shown?.title)
    assertEquals(100, shown?.permille)
  }

  @Test
  fun backgroundProgress_severalDownloads_countsThem() {
    val api = RecordingKetchApi()
    repeat(3) { api.add(DownloadState.Downloading(RecordingTask.PROGRESS)) }

    assertEquals("Downloading 3 files", backgroundProgress(api.tasks.value)?.title)
  }

  @Test
  fun backgroundProgress_onlyWaiting_isIndeterminate() {
    val api = RecordingKetchApi()
    api.add(DownloadState.Queued)

    val shown = backgroundProgress(api.tasks.value)

    assertEquals("Waiting to download 1 file", shown?.title)
    assertEquals("", shown?.subtitle)
    assertEquals(-1, shown?.permille)
  }

  @Test
  fun backgroundProgress_nothingActive_isNull() {
    val api = RecordingKetchApi()
    api.add(DownloadState.Paused(RecordingTask.PROGRESS))

    assertNull(backgroundProgress(api.tasks.value))
  }

  private class RecordingProcessing : ContinuedProcessing {
    val calls = mutableListOf<String>()
    var accepts = true

    override fun begin(progress: BackgroundProgress): Boolean {
      calls += "begin ${progress.title}"
      return accepts
    }

    override fun update(progress: BackgroundProgress) {
      calls += "update ${progress.permille}"
    }

    override fun end(success: Boolean) {
      calls += "end $success"
    }
  }
}
