package com.linroid.ketch.app.ui.shell

import com.linroid.ketch.api.SpeedLimit
import com.linroid.ketch.app.FakeKetchApi
import com.linroid.ketch.app.instance.DevicePresence
import com.linroid.ketch.app.instance.EmbeddedInstance
import com.linroid.ketch.app.instance.InstanceEntry
import com.linroid.ketch.app.instance.RemoteInstance
import com.linroid.ketch.app.state.DeviceHealth
import com.linroid.ketch.app.state.DiskSpace
import com.linroid.ketch.app.state.PulseCounts
import com.linroid.ketch.app.state.SpeedMode
import com.linroid.ketch.config.RemoteConfig
import com.linroid.ketch.remote.ConnectionState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.time.Instant

/** Presences of the devices the shell's fleet tests show. */
internal object FleetFixtures {
  const val NAS_ID = "nas.local:8642"

  /** 1.8 TB free of 3.6 TB, in the binary units the app shows. */
  val NasDisk = DiskSpace(1_979_120_929_996, 4_000_787_030_016, "/volume1/downloads")

  /** This device, "This Mac" on "MacBook Pro". */
  fun mac(
    counts: PulseCounts = PulseCounts(),
    speed: Long = 0,
    failures: Int = 0,
    speedMode: SpeedMode = SpeedMode.Full,
  ): DevicePresence = presence(
    entry = EmbeddedInstance(FakeKetchApi(), "MacBook Pro"),
    name = "This Mac",
    detail = "MacBook Pro",
    health = DeviceHealth.Local(),
    counts = counts,
    speed = speed,
    failures = failures,
    speedMode = speedMode,
  )

  /** NAS-Basement at nas.local:8642. */
  fun nas(
    health: DeviceHealth = DeviceHealth.Live,
    connected: Boolean = true,
    counts: PulseCounts = PulseCounts(),
    speed: Long = 0,
    failures: Int = 0,
    disk: DiskSpace? = NasDisk,
    lastSeen: Instant? = null,
  ): DevicePresence = presence(
    entry = RemoteInstance(
      instance = FakeKetchApi(),
      remoteConfig = RemoteConfig(host = "nas.local", name = "NAS-Basement"),
      connectionState = MutableStateFlow(ConnectionState.Connected),
    ),
    name = "NAS-Basement",
    detail = NAS_ID,
    health = health,
    connected = connected,
    counts = counts,
    speed = speed,
    failures = failures,
    disk = disk,
    lastSeen = lastSeen,
  )

  private fun presence(
    entry: InstanceEntry,
    name: String,
    detail: String,
    health: DeviceHealth,
    connected: Boolean = true,
    counts: PulseCounts,
    speed: Long,
    failures: Int,
    speedMode: SpeedMode = SpeedMode.Full,
    disk: DiskSpace? = null,
    lastSeen: Instant? = null,
  ) = DevicePresence(
    entry = entry,
    name = name,
    detail = detail,
    health = health,
    connected = connected,
    watched = true,
    status = null,
    statusAt = null,
    lastSeen = lastSeen,
    speed = speed,
    counts = counts,
    failures = failures,
    unseenFailures = 0,
    cap = SpeedLimit.Unlimited,
    disk = disk,
    speedMode = speedMode,
    history = emptyList(),
  )
}
