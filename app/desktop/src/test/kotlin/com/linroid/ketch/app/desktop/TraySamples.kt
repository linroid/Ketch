package com.linroid.ketch.app.desktop

import com.linroid.ketch.api.SpeedLimit
import com.linroid.ketch.api.SpeedLimitMode
import com.linroid.ketch.app.i18n.verbatim
import com.linroid.ketch.app.instance.DevicePresence
import com.linroid.ketch.app.instance.EmbeddedInstance
import com.linroid.ketch.app.instance.RemoteInstance
import com.linroid.ketch.app.instance.detail
import com.linroid.ketch.app.state.DeviceHealth
import com.linroid.ketch.app.state.DevicePulse
import com.linroid.ketch.app.state.PulseCounts
import com.linroid.ketch.app.state.PulseState
import com.linroid.ketch.app.state.SpeedMode
import com.linroid.ketch.config.RemoteConfig
import com.linroid.ketch.remote.ConnectionState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.time.Instant

/** A tray menu's sample state: this PC and a NAS downloading, a desktop offline. */
internal object TraySamples {
  val now: Instant = Instant.parse("2026-10-01T14:30:00Z")

  /** This computer, downloading two files. */
  val pc: DevicePresence = presence(
    name = "Lin's PC",
    counts = PulseCounts(downloading = 2, waiting = 1, paused = 1),
    speed = 6_710_886,
  )

  /** A NAS kept connected, downloading one file, with one that failed. */
  val nas: DevicePresence = presence(
    name = "NAS-Basement",
    remote = true,
    counts = PulseCounts(downloading = 1, failed = 1),
    speed = 2_831_155,
  )

  /** A desktop that cannot be reached. */
  val den: DevicePresence = presence(
    name = "Den-PC",
    remote = true,
    health = DeviceHealth.Offline(),
    watched = false,
  )

  private val speed = TraySpeed(SpeedLimitMode.Full, SpeedLimit.mbps(1), hasRules = true)

  /** What the tray menu shows of the sample. */
  val context: TrayContext = TrayContext(
    pulse = PulseState(
      devices = listOf(pc.toPulse(downloaded = 640_000_000, size = 1_520_000_000), nas.toPulse()),
      allDevices = true,
    ),
    speed = speed,
    recent = listOf(
      RecentDownload("ubuntu-26.04-desktop-amd64.iso", "/Downloads/ubuntu.iso"),
      RecentDownload("Big Buck Bunny 4K.mkv", "/Downloads/bunny.mkv"),
    ),
    now = now,
    devices = listOf(
      TrayDevice(pc, speed = speed),
      TrayDevice(nas, limit = SpeedLimit.Unlimited),
      TrayDevice(den),
    ),
  )

  /** The tray menu of the sample. */
  val menu: List<MenuEntry> = trayMenu(context)

  /**
   * How many times ↓ moves from nothing to [entry] of [entries]: the keyboard passes over lines
   * of text, separators and disabled entries.
   */
  fun downsTo(entry: MenuEntry, entries: List<MenuEntry> = menu): Int =
    entries.filter { it.isSelectable() }.indexOf(entry) + 1

  /** The submenu of [entries] whose items do what [action] matches. */
  fun submenuWith(entries: List<MenuEntry>, action: (MenuAction) -> Boolean): MenuEntry.Submenu =
    entries.filterIsInstance<MenuEntry.Submenu>().first { submenu ->
      submenu.entries.any { it is MenuEntry.Item && action(it.action) }
    }

  private fun MenuEntry.isSelectable(): Boolean = when (this) {
    is MenuEntry.Item -> enabled
    is MenuEntry.Submenu -> enabled
    else -> false
  }

  private fun presence(
    name: String,
    remote: Boolean = false,
    health: DeviceHealth = if (remote) DeviceHealth.Live else DeviceHealth.Local(),
    watched: Boolean = true,
    counts: PulseCounts = PulseCounts(),
    speed: Long = 0,
  ): DevicePresence {
    val entry = if (remote) {
      val config = RemoteConfig(host = name.lowercase(), name = name, watch = watched)
      RemoteInstance(FakeDevice(), config, MutableStateFlow(ConnectionState.Connected))
    } else {
      EmbeddedInstance(FakeDevice(), name)
    }
    return DevicePresence(
      entry = entry,
      name = verbatim(name),
      detail = entry.detail,
      health = health,
      connected = true,
      watched = watched,
      status = null,
      statusAt = null,
      lastSeen = null,
      speed = speed,
      counts = counts,
      failures = counts.failed,
      unseenFailures = 0,
      cap = SpeedLimit.Unlimited,
      disk = null,
      speedMode = SpeedMode.Full,
      history = emptyList(),
    )
  }

  private fun DevicePresence.toPulse(downloaded: Long = 0, size: Long = 0) = DevicePulse(
    deviceId = deviceId,
    name = name,
    health = health,
    counts = counts,
    failures = failures,
    speed = speed,
    cap = cap,
    downloadedBytes = downloaded,
    sizeBytes = size,
    sizesKnown = size > 0,
    pendingBytes = 0,
    disk = null,
    history = emptyList(),
  )
}
