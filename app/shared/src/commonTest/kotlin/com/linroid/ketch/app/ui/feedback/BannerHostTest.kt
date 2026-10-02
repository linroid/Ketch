package com.linroid.ketch.app.ui.feedback

import com.linroid.ketch.app.feedback.AppMessage
import com.linroid.ketch.app.feedback.MessageAction
import com.linroid.ketch.app.feedback.MessageLevel
import com.linroid.ketch.app.feedback.MessagePlacement
import com.linroid.ketch.app.state.DeviceHealth
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

class BannerHostTest {

  private var retried = 0
  private var switched = 0
  private var tokenAsked = 0

  private fun banner(
    health: DeviceHealth?,
    connectingLong: Boolean = false,
    localName: String? = "This Mac",
  ) = deviceBanner(
    health = health,
    name = "NAS-Basement",
    connectingLong = connectingLong,
    localName = localName,
    onRetry = { retried++ },
    onSwitchToLocal = { switched++ },
    onEnterToken = { tokenAsked++ },
  )

  @Test
  fun deviceBanner_connectingBriefly_showsNothing() {
    assertNull(banner(DeviceHealth.Connecting))
  }

  @Test
  fun deviceBanner_connectingAWhile_saysSoWithASpinner() {
    val banner = banner(DeviceHealth.Connecting, connectingLong = true)

    assertEquals("Connecting to NAS-Basement…", banner?.text)
    assertEquals(BannerTone.Warning, banner?.tone)
    assertTrue(banner?.busy == true)
  }

  @Test
  fun deviceBanner_offline_offersRetryAndTheLocalDevice() {
    val banner = banner(DeviceHealth.Offline("Connection refused"))

    assertEquals("NAS-Basement is offline · retrying · Connection refused", banner?.text)
    assertEquals(BannerTone.Danger, banner?.tone)
    assertEquals(listOf("Retry now", "Switch to This Mac"), banner?.actions?.map { it.label })
    banner?.actions?.forEach { it.onClick() }
    assertEquals(1, retried)
    assertEquals(1, switched)
  }

  @Test
  fun deviceBanner_offlineWithoutLocalDevice_offersOnlyRetry() {
    val banner = banner(DeviceHealth.Offline(), localName = null)

    assertEquals("NAS-Basement is offline · retrying", banner?.text)
    assertEquals(listOf("Retry now"), banner?.actions?.map { it.label })
  }

  @Test
  fun deviceBanner_unauthorized_asksForATokenOnlyWhenClicked() {
    val banner = banner(DeviceHealth.Unauthorized)

    assertEquals("NAS-Basement needs a new access token", banner?.text)
    assertEquals(0, tokenAsked)
    banner?.actions?.single { it.label == "Enter token" }?.onClick()
    assertEquals(1, tokenAsked)
  }

  @Test
  fun deviceBanner_healthy_showsNothing() {
    assertNull(banner(DeviceHealth.Live))
    assertNull(banner(DeviceHealth.Local(sharingPort = 8642)))
    assertNull(banner(null))
  }

  @Test
  fun messageBanner_message_keepsItsActionsAndCanBeClosed() {
    var dismissed = false
    val message = AppMessage(
      id = 7,
      level = MessageLevel.Info,
      title = "Downloads pause when Ketch is in the background",
      actions = listOf(MessageAction("Use a computer instead") {}),
      at = Instant.fromEpochSeconds(0),
      placement = MessagePlacement.Banner,
    )

    val banner = messageBanner(message) { dismissed = true }
    banner.onDismiss?.invoke()

    assertEquals("message-7", banner.id)
    assertEquals(BannerTone.Info, banner.tone)
    assertEquals(listOf("Use a computer instead"), banner.actions.map { it.label })
    assertTrue(dismissed)
  }
}
