package com.linroid.ketch.app.feedback

import androidx.compose.ui.text.input.TextFieldValue
import com.linroid.ketch.api.DownloadRequest
import com.linroid.ketch.api.DownloadState
import com.linroid.ketch.api.KetchError
import com.linroid.ketch.app.FakeAiProvider
import com.linroid.ketch.app.feedback.ActivityRouting.Delivery
import com.linroid.ketch.app.state.AiCandidate
import com.linroid.ketch.app.state.AiDiscoverController
import com.linroid.ketch.app.state.AiSettingsController
import com.linroid.ketch.app.state.LOCAL_DEVICE_ID
import com.linroid.ketch.app.state.TaskKey
import com.linroid.ketch.config.AiSettings
import com.linroid.ketch.config.NotificationSettings
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TestTimeSource

class SuccessFeedbackTest {
  /** Each play, as whether it sounded and whether it vibrated. */
  private class RecordingPlayer : SuccessFeedbackPlayer {
    val plays = mutableListOf<Pair<Boolean, Boolean>>()

    override fun play(sound: Boolean, vibrate: Boolean) {
      plays += sound to vibrate
    }
  }

  private val player = RecordingPlayer()
  private val time = TestTimeSource()
  private val feedback = SuccessFeedback(player, time)

  private val completed = ActivityEvent.Completed(
    taskKey = TaskKey(LOCAL_DEVICE_ID, "t1"),
    request = DownloadRequest("https://example.com/ubuntu.iso"),
    state = DownloadState.Completed(outputPath = "/downloads/ubuntu.iso", totalBytes = 1024),
  )
  private val failed = ActivityEvent.Failed(
    taskKey = TaskKey(LOCAL_DEVICE_ID, "t2"),
    request = DownloadRequest("https://example.com/report.pdf"),
    state = DownloadState.Failed(KetchError.Http(403)),
  )
  private val toast = Delivery(toast = true, notify = false)
  private val notification = Delivery(toast = false, notify = true)

  private fun TestScope.discoverFinding(count: Int): AiDiscoverController {
    val provider = FakeAiProvider(
      candidates = List(count) {
        AiCandidate(
          url = "https://example.com/file$it.zip",
          title = "file$it.zip",
          sourceUrl = "https://example.com/",
          confidence = 0.9f,
          description = "",
        )
      },
    )
    val settings = AiSettingsController(factory = { provider }).apply {
      save(AiSettings(enabled = true))
    }
    return AiDiscoverController(settings, backgroundScope)
  }

  private fun AiDiscoverController.search(message: String) {
    newSession()
    draft.text = TextFieldValue(message)
    send()
  }

  @Test
  fun follow_resultsFound_playsWhatTheSettingsTurnOnAtTheTime() = runTest {
    val discover = discoverFinding(2)
    var settings = NotificationSettings()
    backgroundScope.launch { feedback.follow(discover) { settings } }
    runCurrent()

    discover.search("blender")
    runCurrent()
    time += SuccessFeedback.GAP
    settings = settings.copy(successSound = false)
    discover.search("gimp")
    runCurrent()
    time += SuccessFeedback.GAP
    settings = settings.copy(successVibration = false)
    discover.search("krita")
    runCurrent()

    assertEquals(listOf(true to true, false to true), player.plays)
  }

  @Test
  fun follow_nothingFound_staysQuiet() = runTest {
    val discover = discoverFinding(0)
    backgroundScope.launch { feedback.follow(discover) { NotificationSettings() } }
    runCurrent()

    discover.search("blender")
    runCurrent()

    assertEquals(emptyList(), player.plays)
  }

  @Test
  fun reported_finishedDownloadShownInApp_plays() {
    val soundOnly = NotificationSettings(successVibration = false)

    feedback.reported(completed, toast, soundOnly, notificationsAlert = true)

    assertEquals(listOf(true to false), player.plays)
  }

  @Test
  fun reported_notifiedWhereNotificationsAlertThemselves_leavesItToThem() {
    feedback.reported(completed, notification, NotificationSettings(), notificationsAlert = true)

    assertEquals(emptyList(), player.plays)
  }

  @Test
  fun reported_notifiedWhereNotificationsAreSilent_plays() {
    val batch = ActivityEvent.CompletedBatch(listOf(completed, completed))

    feedback.reported(batch, notification, NotificationSettings(), notificationsAlert = false)

    assertEquals(listOf(true to true), player.plays)
  }

  @Test
  fun reported_notReportedFeedbackOffOrNotAFinish_staysQuiet() {
    val quiet = Delivery(toast = false, notify = false)
    val off = NotificationSettings(successSound = false, successVibration = false)

    feedback.reported(completed, quiet, NotificationSettings(), notificationsAlert = false)
    feedback.reported(completed, toast, off, notificationsAlert = false)
    feedback.reported(failed, toast, NotificationSettings(), notificationsAlert = false)

    assertEquals(emptyList(), player.plays)
  }

  @Test
  fun reported_withinTheGap_playsOnce() {
    val settings = NotificationSettings()

    feedback.reported(completed, toast, settings, notificationsAlert = false)
    time += SuccessFeedback.GAP - 1.seconds / 10
    feedback.reported(completed, toast, settings, notificationsAlert = false)
    time += 1.seconds / 10
    feedback.reported(completed, toast, settings, notificationsAlert = false)

    assertEquals(2, player.plays.size)
  }

  @Test
  fun chime_peaksQuietlyAndStartsAndEndsSilent() {
    val pcm = Chime.pcm()

    val peak = pcm.maxOf { abs(it.toInt()) }
    assertTrue(abs(peak - Chime.PEAK * Short.MAX_VALUE) <= 1, "peak $peak")
    // Silent edges keep the speaker from clicking.
    assertEquals(0, pcm.first().toInt())
    assertTrue(abs(pcm.last().toInt()) <= 1, "last sample ${pcm.last()}")
  }

  @Test
  fun pcmBytes_littleEndian() {
    val pcm = Chime.pcm()
    val bytes = Chime.pcmBytes()

    assertEquals(pcm.size * 2, bytes.size)
    pcm.forEachIndexed { i, sample ->
      val decoded = (bytes[2 * i].toInt() and 0xFF) or (bytes[2 * i + 1].toInt() shl 8)
      assertEquals(sample, decoded.toShort())
    }
  }
}
