package com.linroid.ketch.app.ui.devices

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import com.linroid.ketch.api.NetworkInterfaces
import com.linroid.ketch.app.i18n.UiText
import com.linroid.ketch.app.i18n.joinText
import com.linroid.ketch.app.i18n.text
import com.linroid.ketch.app.i18n.verbatim
import com.linroid.ketch.app.icons.KetchIcon
import com.linroid.ketch.app.instance.DevicePresence
import com.linroid.ketch.app.instance.EmbeddedInstance
import com.linroid.ketch.app.instance.ServerState
import com.linroid.ketch.app.platform.LocalDeviceKind
import com.linroid.ketch.app.state.DeviceHealth
import com.linroid.ketch.app.state.DiskSpace
import com.linroid.ketch.app.state.folderNameText
import com.linroid.ketch.app.state.formatSpace
import com.linroid.ketch.app.state.isDocumentTree
import ketch.app.shared.generated.resources.Res
import ketch.app.shared.generated.resources.action_connect
import ketch.app.shared.generated.resources.device_duration_days
import ketch.app.shared.generated.resources.device_duration_hours
import ketch.app.shared.generated.resources.device_duration_minutes
import ketch.app.shared.generated.resources.device_fix_enter_token
import ketch.app.shared.generated.resources.device_fix_retry_now
import ketch.app.shared.generated.resources.device_free_of_total
import ketch.app.shared.generated.resources.device_meta_uptime
import ketch.app.shared.generated.resources.device_not_connected
import ketch.app.shared.generated.resources.device_problem_connecting
import ketch.app.shared.generated.resources.device_problem_not_kept
import ketch.app.shared.generated.resources.device_problem_offline
import ketch.app.shared.generated.resources.device_problem_offline_for
import ketch.app.shared.generated.resources.device_problem_over_limit
import ketch.app.shared.generated.resources.device_problem_token_rejected
import ketch.app.shared.generated.resources.device_problem_unauthorized
import ketch.app.shared.generated.resources.device_sharing_discoverable
import ketch.app.shared.generated.resources.device_sharing_on
import ketch.app.shared.generated.resources.device_show_downloads
import org.jetbrains.compose.resources.StringResource
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
    os.startsWith("mac", ignoreCase = true) || os.startsWith("darwin", ignoreCase = true) ->
      "macOS"
    os.startsWith("windows", ignoreCase = true) -> "Windows"
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
internal fun shortDurationText(duration: Duration): UiText = when {
  duration < 1.hours ->
    Res.string.device_duration_minutes.text(duration.inWholeMinutes.coerceAtLeast(1))
  duration < 1.days -> Res.string.device_duration_hours.text(duration.inWholeHours)
  else -> Res.string.device_duration_days.text(duration.inWholeDays)
}

/**
 * The line under a device card's name, "Ketch 0.0.1 · macOS arm64 · up 3 h", from what the
 * device last reported; its host until then, unless the name already is the host. A device that
 * is not online leaves out its uptime.
 */
internal fun deviceMeta(device: DevicePresence, now: Instant): UiText {
  val status = device.status
    ?: return device.detail.takeUnless { device.name == verbatim(it) }.orEmpty().let(::verbatim)
  val uptime = device.uptimeAt(now)?.takeIf { device.health.isOnline }
  return listOfNotNull(
    verbatim("Ketch ${status.version}"),
    systemLabel(status.system.os, status.system.arch).ifEmpty { null }?.let(::verbatim),
    uptime?.let { Res.string.device_meta_uptime.text(shortDurationText(it)) },
  ).joinText()
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
  val title: UiText,
  val detail: UiText? = null,
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
      title = Res.string.device_problem_unauthorized.text(),
      detail = Res.string.device_problem_token_rejected.text(),
    )
    !device.connected -> DeviceProblem(
      kind = ProblemKind.NotConnected,
      title = Res.string.device_not_connected.text(),
      detail = if (device.watched) {
        Res.string.device_problem_over_limit.text()
      } else {
        Res.string.device_problem_not_kept.text()
      },
    )
    health == DeviceHealth.Connecting && device.lastSeen == null -> DeviceProblem(
      kind = ProblemKind.Connecting,
      title = Res.string.device_problem_connecting.text(),
    )
    else -> DeviceProblem(
      kind = ProblemKind.Offline,
      title = device.lastSeen
        ?.let { Res.string.device_problem_offline_for.text(shortDurationText(now - it)) }
        ?: Res.string.device_problem_offline.text(),
      detail = (health as? DeviceHealth.Offline)?.reason?.let(::verbatim),
    )
  }
}

/** The button that fixes what keeps a remote device's card from showing what it is doing. */
internal enum class ProblemFix(private val resource: StringResource) {
  /** Reconnects now rather than at the next scheduled attempt. */
  RetryNow(Res.string.device_fix_retry_now),

  /** Asks for a new access token. */
  EnterToken(Res.string.device_fix_enter_token),

  /** Keeps the device connected from now on. */
  Connect(Res.string.action_connect),

  /** Switches to the device, which connects it. */
  Show(Res.string.device_show_downloads);

  /** What the button says. */
  val label: UiText get() = resource.text()
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
internal fun storageLabel(disk: DiskSpace): UiText =
  Res.string.device_free_of_total.text(formatSpace(disk.usableBytes), formatSpace(disk.totalBytes))

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
internal fun sharingLabel(server: ServerState): UiText? {
  if (server !is ServerState.Running || server.config.isLoopbackOnly) return null
  val resource = if (server.config.mdnsEnabled) {
    Res.string.device_sharing_discoverable
  } else {
    Res.string.device_sharing_on
  }
  return resource.text(server.port)
}

/**
 * Glyph of the kind of device: the form factor of the embedded one, which is a [localKind], and
 * for a remote one what its system suggests; most remote devices are servers.
 */
internal fun deviceIcon(device: DevicePresence, localKind: LocalDeviceKind): KetchIcon {
  if (device.entry is EmbeddedInstance) {
    return when (localKind) {
      LocalDeviceKind.Phone -> KetchIcon.Phone
      LocalDeviceKind.IPad, LocalDeviceKind.Tablet -> KetchIcon.Tablet
      LocalDeviceKind.Browser -> KetchIcon.Browser
      LocalDeviceKind.Mac -> KetchIcon.Laptop
      LocalDeviceKind.Pc, LocalDeviceKind.Computer -> KetchIcon.Desktop
    }
  }
  val os = device.status?.system?.os.orEmpty()
  return when {
    os.startsWith("mac", ignoreCase = true) -> KetchIcon.Laptop
    os.startsWith("windows", ignoreCase = true) -> KetchIcon.Desktop
    else -> KetchIcon.Server
  }
}

/**
 * [path] with the user's home folder as "~", as in "~/Downloads", for "/Users/alex/Downloads"
 * and "/home/alex/Downloads", or the name of an Android document tree; other paths are kept as
 * they are.
 */
internal fun shortPathText(path: String): UiText =
  if (isDocumentTree(path)) folderNameText(path) else verbatim(homeShortened(path))

// The path with the user's home folder as "~"; other paths as they are.
private fun homeShortened(path: String): String {
  val match = HomeFolder.find(path) ?: return path
  return "~" + path.substring(match.range.last + 1)
}

/** [name] cut to [max] characters with "…" at the end, for a button label. */
internal fun clipName(name: String, max: Int = MAX_NAME_LENGTH): String =
  if (name.length <= max) name else name.take(max - 1).trimEnd() + "…"

/** [text] with [part], such as a command it names, in [style]. */
internal fun styledPart(text: String, part: String, style: SpanStyle): AnnotatedString =
  buildAnnotatedString {
    append(text)
    val start = text.indexOf(part)
    if (start >= 0) addStyle(style, start, start + part.length)
  }

/** The command that runs Ketch as a server, kept on one line. */
internal const val SERVER_COMMAND = "ketch\u00A0server"

private val HomeFolder =
  Regex("""^(/Users/[^/]+|/home/[^/]+|[A-Za-z]:\\Users\\[^\\]+)(?=$|[/\\])""")
private const val MAX_NAME_LENGTH = 20
