package com.linroid.ketch.app.ui.shell

import com.linroid.ketch.app.state.DeviceHealth
import com.linroid.ketch.app.state.PulseCounts
import com.linroid.ketch.app.state.SpeedMode
import com.linroid.ketch.app.ui.shell.FleetFixtures.mac
import com.linroid.ketch.app.ui.shell.FleetFixtures.nas
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

class DeviceSwitcherTest {
  private val now = Instant.parse("2026-10-01T14:30:00Z")

  @Test
  fun switcherDetail_downloading_showsSpeedActivityAndFailures() {
    val device = mac(
      counts = PulseCounts(downloading = 2),
      speed = 6_710_886,
      failures = 1,
      speedMode = SpeedMode.SlowLane,
    )

    assertEquals("6.4 MB/s · Slow lane · 2 active · 1 failed", switcherDetail(device, now))
  }

  @Test
  fun switcherDetail_onlyWaiting_countsTheWaitingTasks() {
    assertEquals("3 waiting", switcherDetail(nas(counts = PulseCounts(waiting = 3)), now))
  }

  @Test
  fun switcherDetail_nothingToDo_readsIdle() {
    assertEquals("Idle", switcherDetail(nas(counts = PulseCounts(done = 4)), now))
  }

  @Test
  fun switcherDetail_offline_saysWhenItWasLastSeen() {
    val offline = nas(health = DeviceHealth.Offline(), lastSeen = now - 2.hours)

    assertEquals("Offline · last seen 2 h ago", switcherDetail(offline, now))
  }

  @Test
  fun switcherDetail_unauthorized_asksForAToken() {
    val locked = nas(health = DeviceHealth.Unauthorized)

    assertEquals("Needs a new access token", switcherDetail(locked, now))
  }

  @Test
  fun switcherDetail_notKeptConnected_saysSo() {
    val idle = nas(health = DeviceHealth.Offline(), connected = false)

    assertEquals("Not connected", switcherDetail(idle, now))
  }

  @Test
  fun allDevicesDetail_downloading_sumsTheOnlineDevices() {
    val devices = listOf(
      mac(counts = PulseCounts(downloading = 2), speed = 6_710_886),
      nas(counts = PulseCounts(downloading = 1), speed = 2_831_155)
    )

    assertEquals("↓ 9.1 MB/s · 3 active", allDevicesDetail(devices))
  }

  @Test
  fun allDevicesDetail_oneOffline_saysHowManyAreOnline() {
    val devices = listOf(
      mac(counts = PulseCounts(waiting = 2)),
      nas(health = DeviceHealth.Offline())
    )

    assertEquals("2 waiting · 1 of 2 online", allDevicesDetail(devices))
  }
}
