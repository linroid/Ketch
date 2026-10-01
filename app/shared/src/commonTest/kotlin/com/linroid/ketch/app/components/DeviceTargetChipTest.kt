package com.linroid.ketch.app.components

import com.linroid.ketch.app.state.DeviceHealth
import kotlin.test.Test
import kotlin.test.assertEquals

class DeviceTargetChipTest {
  @Test
  fun deviceOptionCaption_onlineDevice_isItsSummary() {
    val option = DeviceOption("nas", "NAS-Basement", DeviceHealth.Live, summary = "2 active")

    assertEquals("2 active", deviceOptionCaption(option))
  }

  @Test
  fun deviceOptionCaption_unreachableDevice_saysWhyItCannotBePicked() {
    fun caption(health: DeviceHealth) =
      deviceOptionCaption(DeviceOption("nas", "NAS-Basement", health, summary = "2 active"))

    assertEquals("Offline", caption(DeviceHealth.Offline("timeout")))
    assertEquals("Connecting…", caption(DeviceHealth.Connecting))
    assertEquals("Needs a new access token", caption(DeviceHealth.Unauthorized))
  }
}
