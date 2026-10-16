package com.linroid.ketch.app.state

import com.linroid.ketch.api.DownloadProgress
import com.linroid.ketch.api.DownloadState
import com.linroid.ketch.api.Segment
import com.linroid.ketch.app.RecordingConfigStore
import com.linroid.ketch.app.feedback.MessageLevel
import com.linroid.ketch.app.i18n.UiText
import com.linroid.ketch.app.instance.InstanceFactory
import com.linroid.ketch.app.instance.InstanceManager
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

/**
 * The app on a test scheduler's virtual time, stepped a frame of 1/60 s at a time as a recording
 * would: its samplers, its histories and its rates see the virtual time pass, never the wall
 * clock's.
 */
class AppControllerTest {

  private val api = RecordingKetchApi()

  @Test
  fun virtualFrames_taskList_samplesEveryQuarterSecond() = runTest {
    api.add(downloading(downloaded = 0))
    withController { controller ->
      frames(until = 249)
      assertTrue(controller.taskList.rows.value.isEmpty())

      frames(until = 250)
      assertEquals(1, controller.taskList.rows.value.size)
    }
  }

  @Test
  fun virtualFrames_pulse_recordsTheSpeedOnceASecond() = runTest {
    api.add(downloading(downloaded = 0))
    withController { controller ->
      frames(until = 999)
      assertEquals(emptyList(), controller.pulse.devices.value.single().history)

      frames(until = 1_000)
      assertEquals(listOf(SPEED), controller.pulse.devices.value.single().history)
    }
  }

  @Test
  fun virtualFrames_connectionRate_dividesByTheVirtualTime() = runTest {
    val download = api.add(downloading(downloaded = 0))
    download.segments.value = listOf(segment(downloaded = 0))
    withController { controller ->
      frames(until = 600)
      download.segments.value = listOf(segment(downloaded = 3_000))

      // The rows, and the rates sampled from them, show the change by the next second.
      frames(until = 1_250)

      val key = controller.taskList.rows.value.single().key
      assertEquals(3_000, controller.speedHistory.rates(key).single().bytesPerSecond)
    }
  }

  @Test
  fun post_message_isStampedByTheControllersClock() = runTest {
    withController { controller ->
      frames(until = 500)

      val message = controller.messages.post(MessageLevel.Info, UiText.Verbatim("Hello"))

      assertEquals(ListFixtures.START + 500.milliseconds, message.at)
    }
  }

  /**
   * Runs [block] with an [AppController], and the devices under it, on this test's scheduler,
   * then closes them.
   */
  private suspend fun TestScope.withController(block: suspend TestScope.(AppController) -> Unit) {
    val dispatcher = StandardTestDispatcher(testScheduler)
    val clock = ListFixtures.clock(this)
    val manager = InstanceManager(
      factory = InstanceFactory(deviceName = "This Mac", embeddedFactory = { api }),
      configStore = RecordingConfigStore(),
      context = dispatcher,
      clock = clock,
    )
    val controller = AppController(
      instanceManager = manager,
      context = dispatcher,
      clock = clock,
      listDispatcher = dispatcher,
      timeSource = testScheduler.timeSource,
    )
    try {
      runCurrent()
      block(controller)
    } finally {
      controller.close()
      manager.close()
    }
  }

  /**
   * Renders frames of 1/60 s until the virtual time reaches [until] milliseconds, letting what
   * each frame's time starts run. Frames end on the whole millisecond at or before each 1/60 s,
   * since the scheduler counts whole milliseconds, so they never drift from it.
   */
  private fun TestScope.frames(until: Long) {
    var frame = testScheduler.currentTime * FPS / 1_000 + 1
    while (testScheduler.currentTime < until) {
      val at = minOf(frame * 1_000 / FPS, until)
      advanceTimeBy((at - testScheduler.currentTime).milliseconds)
      runCurrent()
      frame++
    }
  }

  private fun downloading(downloaded: Long): DownloadState.Downloading =
    DownloadState.Downloading(
      DownloadProgress(downloadedBytes = downloaded, totalBytes = SIZE, bytesPerSecond = SPEED),
    )

  private fun segment(downloaded: Long): Segment =
    Segment(index = 0, start = 0, end = SIZE - 1, downloadedBytes = downloaded)

  private companion object {
    const val FPS = 60L
    const val SIZE = 1_000_000L
    const val SPEED = 2_048L
  }
}
