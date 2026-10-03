package com.linroid.ketch.app.ui.sidebar

import com.linroid.ketch.app.FakeKetchApi
import com.linroid.ketch.app.i18n.load
import com.linroid.ketch.app.i18n.verbatim
import com.linroid.ketch.app.instance.EmbeddedInstance
import com.linroid.ketch.app.state.DeviceHealth
import com.linroid.ketch.app.state.PulseCounts
import com.linroid.ketch.app.state.SpeedMode
import com.linroid.ketch.app.ui.shell.FleetFixtures.presence
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DeviceRowsTest {

  private fun device(
    health: DeviceHealth = DeviceHealth.Live,
    connected: Boolean = true,
    counts: PulseCounts = PulseCounts(),
    speed: Long = 0,
    speedMode: SpeedMode = SpeedMode.Full,
  ) = presence(
    entry = EmbeddedInstance(FakeKetchApi(), "NAS-Basement"),
    name = "NAS-Basement",
    detail = "nas.local:8642",
    health = health,
    connected = connected,
    speed = speed,
    counts = counts,
    speedMode = speedMode,
  )

  private fun expected(text: String, alert: Boolean = false) = DeviceLine(verbatim(text), alert)

  // The line with its text read in English, to compare with [expected].
  private suspend fun DeviceLine.loaded(): DeviceLine = copy(text = verbatim(text.load()))

  @Test
  fun deviceLine_downloading_showsTheSpeed() = runTest {
    val line = deviceLine(device(counts = PulseCounts(downloading = 2), speed = 6_710_886))

    assertEquals(expected("6.4 MB/s"), line.loaded())
  }

  @Test
  fun deviceLine_downloadingInTheSlowLane_marksIt() {
    val line = deviceLine(
      device(counts = PulseCounts(downloading = 1), speed = 1024, speedMode = SpeedMode.SlowLane),
    )

    assertEquals(true, line.slowLane)
  }

  @Test
  fun deviceLine_onlyWaiting_countsTheWaitingTasks() = runTest {
    val line = deviceLine(device(counts = PulseCounts(waiting = 2)))

    assertEquals(expected("2 waiting"), line.loaded())
  }

  @Test
  fun deviceLine_nothingActive_readsIdle() = runTest {
    assertEquals(expected("Idle"), deviceLine(device(counts = PulseCounts(done = 4))).loaded())
  }

  @Test
  fun deviceLine_offline_isAProblem() = runTest {
    assertEquals(
      expected("Offline", alert = true),
      deviceLine(device(health = DeviceHealth.Offline())).loaded(),
    )
    assertEquals(
      expected("Needs token", alert = true),
      deviceLine(device(health = DeviceHealth.Unauthorized, connected = false)).loaded(),
    )
  }

  @Test
  fun allDevicesLine_downloading_sumsTheReachableDevices() = runTest {
    val devices = listOf(
      device(counts = PulseCounts(downloading = 2), speed = 6_710_886),
      device(counts = PulseCounts(downloading = 1), speed = 2_831_155),
      device(health = DeviceHealth.Offline(), counts = PulseCounts(downloading = 1), speed = 99)
    )

    assertEquals(expected("9.1 MB/s"), allDevicesLine(devices).loaded())
  }

  @Test
  fun allDevicesLine_onlyWaiting_countsTheWaitingTasks() = runTest {
    val devices = listOf(device(counts = PulseCounts(waiting = 2)), device())

    assertEquals(expected("2 waiting"), allDevicesLine(devices).loaded())
  }

  @Test
  fun deviceShortcut_pastTheNinthDevice_isNull() {
    assertNull(deviceShortcut(10))
  }

  @Test
  fun deviceLine_notKeptConnected_isNotAProblem() = runTest {
    val idle = device(health = DeviceHealth.Offline(), connected = false)

    assertEquals(expected("Off"), deviceLine(idle).loaded())
    assertNull(pennantHealth(idle))
  }
}
