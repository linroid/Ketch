package com.linroid.ketch.app.feedback

import com.linroid.ketch.config.NotificationSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class OngoingPresentationTest {

  @Test
  fun of_defaultSettings_showsTheIconAndAsksForLiveUpdates() {
    val presentation = OngoingPresentation.of(NotificationSettings())

    assertEquals(OngoingPresentation(statusBarIcon = true, liveUpdates = true), presentation)
  }

  @Test
  fun of_settingsTurnedOff_followsThem() {
    val settings = NotificationSettings(statusBarIcon = false, liveUpdates = false)

    assertEquals(
      OngoingPresentation(statusBarIcon = false, liveUpdates = false),
      OngoingPresentation.of(settings),
    )
  }

  @Test
  fun channel_withStatusBarIcon_isTheOneThatIsNotSilent() {
    val presentation = OngoingPresentation(statusBarIcon = true)

    assertEquals(OngoingPresentation.CHANNEL_STATUS_BAR, presentation.channel)
    assertEquals(OngoingPresentation.CHANNEL_SILENT, presentation.unusedChannel)
  }

  @Test
  fun channel_withoutStatusBarIcon_isTheSilentOneOlderVersionsUsed() {
    val presentation = OngoingPresentation(statusBarIcon = false)

    assertEquals("downloads_active", presentation.channel)
    assertEquals(OngoingPresentation.CHANNEL_STATUS_BAR, presentation.unusedChannel)
  }

  @Test
  fun channels_differ_soAndroidTakesTheNewImportance() {
    assertNotEquals(OngoingPresentation.CHANNEL_SILENT, OngoingPresentation.CHANNEL_STATUS_BAR)
  }

  @Test
  fun isLiveUpdate_singleDownloadOnAndroid16_isOne() {
    val presentation = OngoingPresentation()

    assertTrue(presentation.isLiveUpdate(singleDownload = true, sdk = 36))
    assertTrue(presentation.isLiveUpdate(singleDownload = true, sdk = 37))
  }

  @Test
  fun isLiveUpdate_beforeAndroid16_isNot() {
    assertFalse(OngoingPresentation().isLiveUpdate(singleDownload = true, sdk = 35))
  }

  @Test
  fun isLiveUpdate_severalDownloads_isNot() {
    assertFalse(OngoingPresentation().isLiveUpdate(singleDownload = false, sdk = 36))
  }

  @Test
  fun isLiveUpdate_turnedOff_isNot() {
    val presentation = OngoingPresentation(liveUpdates = false)

    assertFalse(presentation.isLiveUpdate(singleDownload = true, sdk = 36))
  }
}
