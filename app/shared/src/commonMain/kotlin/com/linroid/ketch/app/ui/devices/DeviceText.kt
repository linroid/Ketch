package com.linroid.ketch.app.ui.devices

import com.linroid.ketch.api.NetworkInterfaces
import com.linroid.ketch.app.icons.KetchIcon
import com.linroid.ketch.app.instance.DevicePresence
import com.linroid.ketch.app.instance.EmbeddedInstance
import com.linroid.ketch.app.instance.ServerState
import com.linroid.ketch.app.state.DeviceHealth
import com.linroid.ketch.app.state.DiskSpace
import com.linroid.ketch.app.state.formatSpace
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

/**
 * A device's system as its card names it, "macOS arm64" for the "Mac OS X" and "aarch64" a JVM
 * reports; names it does not know are kept as they are.
 */
internal fun systemLabel(os: String, arch: String): String {
  val name = when {
    os.startsWith("Mac", ignoreCase = true) || os.startsWith("Darwin", ignoreCase = true) ->
      "macOS"
    os.startsWith("Windows", ignoreCase = true) -> "Windows"
    else -> os.trim()
  }
  val cpu = when (arch.trim().lowercase()) {
    "aarch64", "arm64" -> "arm64"
    "amd64", "x86_64", "x64" -> "x64"
    else -> arch.trim()
  }
  return listOf(name, cpu).filter { it.isNotEmpty() }.joinToString(" ")
}

/** [duration] in its largest whole unit, at least a minute: "5 min", "3 h" or "12 d". */
internal fun shortDuration(duration: Duration): String = when {
  duration < 1.hours -> "${duration.inWholeMinutes.coerceAtLeast(1)} min"
  duration < 1.days -> "${duration.inWholeHours} h"
  else -> "${duration.inWholeDays} d"
}

/**
 * The line under a device card's name, "Ketch 0.0.1 · macOS arm64 · up 3 h", from what the
 * device last reported; its host until then, unless the name already is the host. A device that
 * is not online leaves out its uptime.
 */
internal fun deviceMeta(device: DevicePresence, now: Instant): String {
  val status = device.status ?: return device.detail.takeUnless { it == device.name }.orEmpty()
  val uptime = device.uptimeAt(now)?.takeIf { device.health.isOnline }
  return listOfNotNull(
    "Ketch ${status.version}",
    systemLabel(status.system.os, status.system.arch).ifEmpty { null },
    uptime?.let { "up ${shortDuration(it)}" }
  ).joinToString(" · ")
}

/** Why a device card cannot show what the device is doing. */
internal enum class ProblemKind {
  /** The app is reaching the device for the first time. */
  Connecting,

  /** The connection was lost; the app keeps retrying. */
  Offline,

  /** The device turned down the access token. */
  Unauthorized,

  /** The app does not keep a connection to the device. */
  NotConnected,
}

/**
 * What the card of a device that is not online says instead of its numbers.
 *
 * @property detail a second line, such as why the connection failed.
 */
internal data class DeviceProblem(
  val kind: ProblemKind,
  val title: String,
  val detail: String? = null,
)

/**
 * The problem [device] has at [now], or `null` while it is online: "Offline for 2 h · retrying"
 * with the reason, "Needs a new access token", "Not connected" or "Connecting…". A device that
 * was online earlier and is reconnecting reads as offline, so its card does not flicker between
 * retries.
 */
internal fun deviceProblem(device: DevicePresence, now: Instant): DeviceProblem? {
  val health = device.health
  return when {
    health.isOnline -> null
    health == DeviceHealth.Unauthorized -> DeviceProblem(
      kind = ProblemKind.Unauthorized,
      title = "Needs a new access token",
      detail = "It turned down the access token saved for it.",
    )
    !device.connected -> DeviceProblem(
      kind = ProblemKind.NotConnected,
      title = "Not connected",
      detail = if (device.watched) {
        "Ketch keeps only a few devices connected at once. It connects to this one while it shows."
      } else {
        "Ketch connects to it while it shows, or while it stays connected."
      },
    )
    health == DeviceHealth.Connecting && device.lastSeen == null -> DeviceProblem(
      kind = ProblemKind.Connecting,
      title = "Connecting…",
    )
    else -> DeviceProblem(
      kind = ProblemKind.Offline,
      title = device.lastSeen
        ?.let { "Offline for ${shortDuration(now - it)} · retrying" }
        ?: "Offline · retrying",
      detail = (health as? DeviceHealth.Offline)?.reason,
    )
  }
}

/** The button that fixes what keeps a remote device's card from showing what it is doing. */
internal enum class ProblemFix(val label: String) {
  /** Reconnects now rather than at the next scheduled attempt. */
  RetryNow("Retry now"),

  /** Asks for a new access token. */
  EnterToken("Enter token"),

  /** Keeps the device connected from now on. */
  Connect("Connect"),

  /** Switches to the device, which connects it. */
  Show("Show downloads"),
}

/**
 * The fix for [problem] of [device]; `null` while it is connecting. A device the app means to
 * keep connected but does not, because more devices are kept connected than it allows, connects
 * once it shows.
 */
internal fun problemFix(device: DevicePresence, problem: DeviceProblem): ProblemFix? =
  when (problem.kind) {
    ProblemKind.Offline -> ProblemFix.RetryNow
    ProblemKind.Unauthorized -> ProblemFix.EnterToken
    ProblemKind.NotConnected -> if (device.watched) ProblemFix.Show else ProblemFix.Connect
    ProblemKind.Connecting -> null
  }

/** Free space as the card's storage line shows it: "412 GB free of 926 GB". */
internal fun storageLabel(disk: DiskSpace): String =
  "${formatSpace(disk.usableBytes)} free of ${formatSpace(disk.totalBytes)}"

/**
 * Names of the network interfaces a device downloads over, as the system reports them: the
 * selected ones, or every available one while none is selected. Empty when the device cannot
 * choose networks.
 */
internal fun networkNames(networks: NetworkInterfaces): List<String> {
  if (!networks.supported) return emptyList()
  val names = networks.available.associate { it.id to it.name }
  val selected = networks.config.interfaceIds.map { names[it] ?: it }
  return selected.ifEmpty { networks.available.map { it.name } }
}

/**
 * Whether the embedded device is shared on the network, for its card: "Sharing on :8642 ·
 * discoverable", or `null` while other devices cannot reach it.
 */
internal fun sharingLabel(server: ServerState): String? {
  if (server !is ServerState.Running || server.config.isLoopbackOnly) return null
  val discoverable = if (server.config.mdnsEnabled) " · discoverable" else ""
  return "Sharing on :${server.port}$discoverable"
}

/**
 * Glyph of the kind of device: the form factor of the embedded one, named by [localNoun] such
 * as "This phone", and for a remote one what its system suggests; most remote devices are
 * servers.
 */
internal fun deviceIcon(device: DevicePresence, localNoun: String): KetchIcon {
  if (device.entry is EmbeddedInstance) {
    val noun = localNoun.lowercase()
    return when {
      "phone" in noun -> KetchIcon.Phone
      "ipad" in noun || "tablet" in noun -> KetchIcon.Tablet
      "browser" in noun -> KetchIcon.Browser
      "mac" in noun -> KetchIcon.Laptop
      else -> KetchIcon.Desktop
    }
  }
  val os = device.status?.system?.os.orEmpty()
  return when {
    os.startsWith("Mac", ignoreCase = true) -> KetchIcon.Laptop
    os.startsWith("Windows", ignoreCase = true) -> KetchIcon.Desktop
    else -> KetchIcon.Server
  }
}

/**
 * [path] with the user's home folder as "~", as in "~/Downloads", for "/Users/alex/Downloads"
 * and "/home/alex/Downloads"; other paths are kept as they are.
 */
internal fun shortPath(path: String): String {
  val match = HomeFolder.find(path) ?: return path
  return "~" + path.substring(match.range.last + 1)
}

/** [name] cut to [max] characters with "…" at the end, for a button label. */
internal fun clipName(name: String, max: Int = MAX_NAME_LENGTH): String =
  if (name.length <= max) name else name.take(max - 1).trimEnd() + "…"

private val HomeFolder =
  Regex("""^(/Users/[^/]+|/home/[^/]+|[A-Za-z]:\\Users\\[^\\]+)(?=$|[/\\])""")
private const val MAX_NAME_LENGTH = 20
