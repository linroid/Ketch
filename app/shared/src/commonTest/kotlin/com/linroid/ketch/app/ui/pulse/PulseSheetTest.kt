package com.linroid.ketch.app.ui.pulse

import com.linroid.ketch.api.SpeedLimit
import com.linroid.ketch.app.i18n.load
import com.linroid.ketch.app.i18n.verbatim
import com.linroid.ketch.app.state.DeviceHealth
import com.linroid.ketch.app.state.DevicePulse
import com.linroid.ketch.app.state.PulseCounts
import com.linroid.ketch.app.state.PulseState
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class PulseSheetTest {

  private fun pulse(
    health: DeviceHealth = DeviceHealth.Local(),
    downloading: Int = 0,
    speed: Long = 0,
  ) = PulseState(
    devices = listOf(
      DevicePulse(
        deviceId = "local",
        name = verbatim("This Mac"),
        health = health,
        counts = PulseCounts(downloading = downloading),
        failures = 0,
        speed = speed,
        cap = SpeedLimit.Unlimited,
        downloadedBytes = 0,
        sizeBytes = 0,
        sizesKnown = true,
        pendingBytes = 0,
        disk = null,
        history = emptyList(),
      )
    ),
  )

  @Test
  fun pulseSubtitle_downloading_readsSpeedActiveAndMode() = runTest {
    assertEquals(
      "↓ 4.2 MB/s · 2 active · Full speed",
      pulseSubtitle(pulse(downloading = 2, speed = 4_404_019), verbatim("Full speed")).load()
    )
  }

  @Test
  fun pulseSubtitle_idle_readsIdle() = runTest {
    assertEquals(
      "Idle · Slow lane · 1 MB/s",
      pulseSubtitle(pulse(), verbatim("Slow lane · 1 MB/s")).load()
    )
  }

  @Test
  fun pulseSubtitleVariants_downloading_dropTheCountThenTheMode() = runTest {
    val downloading = pulse(downloading = 2, speed = 4_404_019)
    val variants = pulseSubtitleVariants(downloading, verbatim("Full"))

    assertEquals(
      listOf("↓ 4.2 MB/s · 2 active · Full", "↓ 4.2 MB/s · Full", "↓ 4.2 MB/s"),
      variants.map { it.load() },
    )
  }

  @Test
  fun pulseSubtitleVariants_idle_dropsOnlyTheMode() = runTest {
    val variants = pulseSubtitleVariants(pulse(), verbatim("Full"))

    assertEquals(listOf("Idle · Full", "Idle"), variants.map { it.load() })
  }

  @Test
  fun pulseSubtitle_offline_namesTheConnection() = runTest {
    val offline = pulse(health = DeviceHealth.Offline(), downloading = 1)

    assertEquals("Offline · Full speed", pulseSubtitle(offline, verbatim("Full speed")).load())
  }
}
