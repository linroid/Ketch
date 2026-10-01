package com.linroid.ketch.app.ui.pulse

import com.linroid.ketch.api.SpeedLimit
import com.linroid.ketch.app.state.DeviceHealth
import com.linroid.ketch.app.state.DevicePulse
import com.linroid.ketch.app.state.PulseCounts
import com.linroid.ketch.app.state.PulseState
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
        name = "This Mac",
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
  fun pulseSubtitle_downloading_readsSpeedActiveAndMode() {
    assertEquals(
      "↓ 4.2 MB/s · 2 active · Full speed",
      pulseSubtitle(pulse(downloading = 2, speed = 4_404_019), "Full speed")
    )
  }

  @Test
  fun pulseSubtitle_idle_readsIdle() {
    assertEquals("Idle · Slow lane · 1 MB/s", pulseSubtitle(pulse(), "Slow lane · 1 MB/s"))
  }

  @Test
  fun pulseSubtitle_offline_namesTheConnection() {
    assertEquals(
      "Offline · Full speed",
      pulseSubtitle(pulse(health = DeviceHealth.Offline(), downloading = 1), "Full speed")
    )
  }
}
