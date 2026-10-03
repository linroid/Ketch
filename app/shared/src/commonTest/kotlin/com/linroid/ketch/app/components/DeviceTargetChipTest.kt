package com.linroid.ketch.app.components

import com.linroid.ketch.app.i18n.load
import com.linroid.ketch.app.i18n.verbatim
import com.linroid.ketch.app.state.DeviceHealth
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class DeviceTargetChipTest {
  @Test
  fun deviceOptionCaption_onlineDevice_isItsSummary() = runTest {
    val option = DeviceOption(
      id = "nas",
      name = verbatim("NAS-Basement"),
      health = DeviceHealth.Live,
      pennantName = "NAS-Basement",
      summary = verbatim("2 active"),
    )

    assertEquals("2 active", deviceOptionCaption(option).load())
  }

  @Test
  fun deviceOptionCaption_unreachableDevice_saysWhyItCannotBePicked() = runTest {
    fun caption(health: DeviceHealth) =
      deviceOptionCaption(
        DeviceOption("nas", verbatim("NAS-Basement"), health, "NAS-Basement", verbatim("2 active")),
      )

    assertEquals("Offline", caption(DeviceHealth.Offline("timeout")).load())
    assertEquals("Connecting…", caption(DeviceHealth.Connecting).load())
    assertEquals("Needs a new access token", caption(DeviceHealth.Unauthorized).load())
  }
}
