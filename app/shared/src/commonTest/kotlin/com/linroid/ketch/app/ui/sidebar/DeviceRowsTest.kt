package com.linroid.ketch.app.ui.sidebar

import com.linroid.ketch.api.SpeedLimit
import com.linroid.ketch.app.FakeKetchApi
import com.linroid.ketch.app.instance.DevicePresence
import com.linroid.ketch.app.instance.EmbeddedInstance
import com.linroid.ketch.app.state.DeviceHealth
import com.linroid.ketch.app.state.PulseCounts
import com.linroid.ketch.app.state.SpeedMode
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
  ) = DevicePresence(
    entry = EmbeddedInstance(FakeKetchApi(), "NAS-Basement"),
    name = "NAS-Basement",
    detail = "nas.local:8642",
    health = health,
    connected = connected,
    watched = true,
    status = null,
    statusAt = null,
    lastSeen = null,
    speed = speed,
    counts = counts,
    failures = 0,
    unseenFailures = 0,
    cap = SpeedLimit.Unlimited,
    disk = null,
    speedMode = speedMode,
    history = emptyList(),
  )

  @Test
  fun deviceLine_downloading_showsTheSpeed() {
    val line = deviceLine(device(counts = PulseCounts(downloading = 2), speed = 6_710_886))

    assertEquals(DeviceLine("6.4 MB/s"), line)
  }

  @Test
  fun deviceLine_downloadingInTheSlowLane_marksIt() {
    val line = deviceLine(
      device(counts = PulseCounts(downloading = 1), speed = 1024, speedMode = SpeedMode.SlowLane),
    )

    assertEquals(true, line.slowLane)
  }

  @Test
  fun deviceLine_onlyWaiting_countsTheWaitingTasks() {
    assertEquals(DeviceLine("2 waiting"), deviceLine(device(counts = PulseCounts(waiting = 2))))
  }

  @Test
  fun deviceLine_nothingActive_readsIdle() {
    assertEquals(DeviceLine("Idle"), deviceLine(device(counts = PulseCounts(done = 4))))
  }

  @Test
  fun deviceLine_offline_isAProblem() {
    assertEquals(
      DeviceLine("Offline", alert = true),
      deviceLine(device(health = DeviceHealth.Offline())),
    )
    assertEquals(
      DeviceLine("Needs token", alert = true),
      deviceLine(device(health = DeviceHealth.Unauthorized, connected = false)),
    )
  }

  @Test
  fun deviceLine_notKeptConnected_isNotAProblem() {
    val idle = device(health = DeviceHealth.Offline(), connected = false)

    assertEquals(DeviceLine("Off"), deviceLine(idle))
    assertNull(pennantHealth(idle))
  }
}
