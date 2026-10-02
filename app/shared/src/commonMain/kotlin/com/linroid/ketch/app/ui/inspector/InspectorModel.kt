package com.linroid.ketch.app.ui.inspector

import androidx.compose.runtime.Immutable
import com.linroid.ketch.api.DownloadPriority
import com.linroid.ketch.api.DownloadRequest
import com.linroid.ketch.api.DownloadSchedule
import com.linroid.ketch.api.DownloadState
import com.linroid.ketch.api.SpeedLimit
import com.linroid.ketch.app.components.startTimeLabel
import com.linroid.ketch.app.state.TaskKey
import com.linroid.ketch.app.state.TaskRow
import com.linroid.ketch.app.state.formatSpeedLimit
import com.linroid.ketch.app.ui.inspector.tabs.clockTime
import com.linroid.ketch.app.ui.inspector.tabs.formatBytesOf
import com.linroid.ketch.app.ui.inspector.tabs.formatSize
import com.linroid.ketch.app.ui.inspector.tabs.formatSpeed
import com.linroid.ketch.app.util.RowStatus
import com.linroid.ketch.app.util.TaskOrigin
import com.linroid.ketch.app.util.formatDuration
import com.linroid.ketch.app.util.priorityLabel
import com.linroid.ketch.app.util.urlHost
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant

/** The chip a [InspectorReason] offers. */
internal enum class ReasonAction(val label: String) {
  /** Pauses and resumes a stalled download, so it opens fresh connections. */
  Reconnect("Reconnect"),

  /** Turns the Slow lane off. */
  FullSpeed("Full speed"),

  /** Removes the download's own speed limit. */
  RemoveLimit("Remove limit"),

  /** Opens the device's Speed settings, where its global limit is set. */
  SpeedSettings("Speed settings"),
}

/**
 * Why a task is where it is, under the inspector's metric line, such as "Limited by Slow lane
 * (1 MB/s)", with the one [action] that changes it.
 *
 * @property warning whether it reports a problem, such as a stall, drawn in the throttled color.
 */
@Immutable
internal data class InspectorReason(
  val text: String,
  val action: ReasonAction? = null,
  val warning: Boolean = false,
)

/**
 * The reason line of [row], or `null` when nothing needs explaining. A stall comes first; then
 * the limit that caps a running download: the Slow lane when [slowLane] is on and [globalCap]
 * is the lower one, the download's own limit, or the device's [globalCap]. Waiting tasks say why
 * they wait, and paused ones whether resuming keeps their progress; a completed download whose
 * file is gone ([fileMissing]) says so. The action bar already offers Resume, Start now and
 * Download again, so these reasons carry no chip of their own.
 */
internal fun inspectorReason(
  row: TaskRow,
  slowLane: Boolean,
  globalCap: SpeedLimit,
  fileMissing: Boolean = false,
): InspectorReason? {
  val content = row.content
  return when (row.state) {
    is DownloadState.Downloading -> when {
      content.status == RowStatus.Stalled ->
        InspectorReason(content.detail, ReasonAction.Reconnect, warning = true)
      else -> limitReason(row.request.speedLimit, slowLane, globalCap)
    }
    is DownloadState.Queued, is DownloadState.Scheduled -> InspectorReason(content.detail)
    is DownloadState.Paused -> {
      val resumable = row.request.resolvedSource?.supportsResume != false
      InspectorReason(
        if (resumable) "Paused · resumes where it stopped" else "Paused · resuming starts over",
      )
    }
    is DownloadState.Completed -> {
      val missing = fileMissing || content.status == RowStatus.FileMissing
      if (missing && !row.device.capabilities.isRemote) {
        InspectorReason("File moved or deleted", warning = true)
      } else {
        null
      }
    }
    is DownloadState.Failed, is DownloadState.Canceled -> null
  }
}

private fun limitReason(own: SpeedLimit, slowLane: Boolean, global: SpeedLimit): InspectorReason? {
  val globalWins = !global.isUnlimited &&
    (own.isUnlimited || global.bytesPerSecond <= own.bytesPerSecond)
  return when {
    globalWins && slowLane ->
      InspectorReason("Limited by Slow lane (${formatSpeedLimit(global)})", ReasonAction.FullSpeed)
    globalWins -> InspectorReason(
      "Limited by the global limit (${formatSpeedLimit(global)})",
      ReasonAction.SpeedSettings,
    )
    !own.isUnlimited -> {
      InspectorReason("Limited to ${formatSpeedLimit(own)}", ReasonAction.RemoveLimit)
    }
    else -> null
  }
}

/**
 * The parts of the inspector's metric line for [row] at [now], joined with " · ":
 * "42%", "2.4 of 5.7 GB", "6.4 MB/s", "2m 10s left" and "done ≈ 14:32" while downloading; the
 * share and bytes while paused or failed; the known size while waiting; nothing once it ended.
 */
internal fun metricParts(row: TaskRow, now: Instant, zone: TimeZone): List<String> {
  val state = row.state
  val progress = when (state) {
    is DownloadState.Downloading -> state.progress
    is DownloadState.Paused -> state.progress
    else -> null
  }
  return when (state) {
    is DownloadState.Downloading -> buildList {
      addAll(progressParts(progress?.downloadedBytes, progress?.totalBytes))
      row.speed?.let { add(formatSpeed(it)) }
      val left = row.timeLeft
      if (left != null) {
        add("${formatDuration(left)} left")
        if (left < 1.days) add("done ≈ ${clockTime(now + left, zone)}")
      }
    }
    is DownloadState.Paused -> progressParts(progress?.downloadedBytes, progress?.totalBytes)
    is DownloadState.Failed -> {
      val total = row.sizeBytes
      val done = row.segments.sumOf { it.downloadedBytes.coerceIn(0, it.totalBytes) }
      if (done > 0 && total != null) progressParts(done, total) else knownSize(row)
    }
    is DownloadState.Queued, is DownloadState.Scheduled -> knownSize(row)
    // The subline shows the size, and the Details right below the time taken and speed.
    is DownloadState.Completed, is DownloadState.Canceled -> emptyList()
  }
}

private fun progressParts(downloaded: Long?, total: Long?): List<String> {
  if (downloaded == null) return emptyList()
  if (total == null || total <= 0) return listOf(formatSize(downloaded))
  val percent = (downloaded.coerceIn(0, total) * 100 / total).toInt()
  val done = formatSize(downloaded)
  val size = formatSize(total)
  // "2.4 of 5.7 GB" in one unit, but "474 MB of 1.3 GB" rather than "0.5 of 1.3 GB".
  val bytes = if (done.substringAfter(' ') == size.substringAfter(' ')) {
    formatBytesOf(downloaded, total)
  } else {
    "$done of $size"
  }
  return listOf("$percent%", bytes)
}

private fun knownSize(row: TaskRow): List<String> = listOfNotNull(row.sizeBytes?.let(::formatSize))

/**
 * When [row] was added, for the Added detail: the row's own "Today 11:42", or an earlier day
 * with its time, such as "Yesterday 18:05" or "Sep 28 09:12".
 */
internal fun addedDetail(row: TaskRow, now: Instant, zone: TimeZone): String {
  val today = now.toLocalDateTime(zone).date
  if (row.createdAt.toLocalDateTime(zone).date == today) return row.content.added
  return "${row.content.added} ${clockTime(row.createdAt, zone)}"
}

/**
 * Where [request] downloads from, for the Source detail: "HTTPS · releases.ubuntu.com",
 * "FTP · ftp.example.org", "BitTorrent · magnet" or "BitTorrent · torrent file".
 */
internal fun sourceLabel(request: DownloadRequest, isTorrent: Boolean): String {
  val url = request.url.trim()
  val scheme = url.substringBefore(':', "").lowercase()
  if (isTorrent) {
    return when {
      scheme == "magnet" -> "BitTorrent · magnet"
      url.substringBefore('?').endsWith(".torrent", ignoreCase = true) ||
        scheme == "torrent" -> "BitTorrent · torrent file"
      else -> "BitTorrent"
    }
  }
  val protocol = scheme.uppercase().ifEmpty { "Link" }
  return listOfNotNull(protocol, urlHost(url)).joinToString(" · ")
}

/**
 * A link split for the Link detail, credentials removed.
 *
 * @property host the host and port, written in a heavier weight; `null` for links without one,
 *   such as magnets.
 * @property path the rest up to the query: the part shortened in the middle when it does not
 *   fit.
 * @property query the query and fragment, often signed tokens, kept behind "Show full link";
 *   `null` when there is none.
 * @property full the whole link without credentials.
 */
@Immutable
internal data class LinkParts(
  val host: String?,
  val path: String,
  val query: String?,
  val full: String,
)

/** [url] split into [LinkParts]; see there. */
internal fun linkParts(url: String): LinkParts {
  val full = withoutPassword(url.trim())
  val schemeEnd = full.indexOf("://")
  if (schemeEnd < 0) {
    // Magnets and other opaque links: everything after the first parameter is the "query".
    val cut = full.indexOf('&').takeIf { it > 0 }
    return LinkParts(
      host = null,
      path = if (cut != null) full.substring(0, cut) else full,
      query = cut?.let { full.substring(it) },
      full = full,
    )
  }
  val afterScheme = full.substring(schemeEnd + 3)
  val authorityEnd = afterScheme.indexOfFirst { it == '/' || it == '?' || it == '#' }
    .let { if (it < 0) afterScheme.length else it }
  val host = afterScheme.substring(0, authorityEnd).substringAfterLast('@')
  val rest = afterScheme.substring(authorityEnd)
  val queryStart = rest.indexOfFirst { it == '?' || it == '#' }
  return LinkParts(
    host = host.ifEmpty { null },
    path = if (queryStart < 0) rest else rest.substring(0, queryStart),
    query = if (queryStart < 0) null else rest.substring(queryStart),
    full = full,
  )
}

/** [url] with the password of its user info, if any, written as "***". */
private fun withoutPassword(url: String): String {
  val authorityStart = url.indexOf("://").takeIf { it >= 0 }?.plus(3) ?: return url
  val authorityEnd = url.indexOf('/', authorityStart).let { if (it < 0) url.length else it }
  val at = url.lastIndexOf('@', authorityEnd - 1)
  if (at < authorityStart) return url
  val colon = url.indexOf(':', authorityStart)
  if (colon < 0 || colon > at) return url
  return url.substring(0, colon + 1) + "***" + url.substring(at)
}

/**
 * Where [request] came from and what it brought along, for the Captured detail: "From the
 * browser, with cookies and referrer". `null` when neither its origin nor such headers are
 * known.
 */
internal fun capturedText(request: DownloadRequest): String? {
  val origin = when (TaskOrigin.of(request)) {
    TaskOrigin.Browser -> "From the browser"
    TaskOrigin.Discover -> "From Discover"
    TaskOrigin.Agent -> "From an AI agent"
    TaskOrigin.App -> "Added in Ketch"
    TaskOrigin.Cli -> "From the command line"
    null -> null
  }
  val names = request.headers.keys.map { it.lowercase() }.toSet()
  val brought = listOfNotNull(
    "cookies".takeIf { "cookie" in names },
    "referrer".takeIf { "referer" in names },
  )
  if (origin == null && brought.isEmpty()) return null
  val extras = if (brought.isEmpty()) null else "with ${brought.joinToString(" and ")}"
  return when {
    extras == null -> origin
    origin == null -> extras.replaceFirstChar { it.uppercaseChar() }
    else -> "$origin, $extras"
  }
}

/**
 * The three speed limits the Speed control offers, fastest first: the round values just below
 * [reference], the download's speed (at 8.4 MB/s: 5, 2 and 1 MB/s). Below the slowest steps, or
 * without a speed, the three slowest or 5, 2 and 1 MB/s.
 */
internal fun speedPresets(reference: Long?): List<SpeedLimit> {
  if (reference == null || reference <= 0) return DefaultPresets
  val below = PresetLadder.filter { it.bytesPerSecond < reference }.takeLast(PRESET_COUNT)
  val presets = if (below.size == PRESET_COUNT) below else PresetLadder.take(PRESET_COUNT)
  return presets.reversed()
}

/**
 * The download that starting the [starting] ones now would pause on a device that runs [slots]
 * at once: like the engine's queue, the first of [running], all the downloads running there,
 * with the lowest priority below Urgent, when every slot is taken; `null` when a slot is free.
 * A running download among [starting] keeps its slot and is never the one paused.
 */
internal fun preemptionVictim(
  running: List<TaskRow>,
  slots: Int?,
  starting: Set<TaskKey> = emptySet(),
): TaskRow? {
  if (slots == null || running.size < slots) return null
  return running.filter { it.key !in starting && it.request.priority < DownloadPriority.URGENT }
    .minByOrNull { it.request.priority.ordinal }
}

/**
 * The note shown before Urgent starts [count] waiting downloads at the cost of [victim]:
 * "Starts now; may pause "debian-12.iso" (Low)".
 */
internal fun urgentNote(victim: TaskRow, count: Int = 1): String {
  val starts = if (count == 1) "Starts now" else "Starts $count now"
  return "$starts; may pause \"${victim.name}\" (${priorityLabel(victim.request.priority)})"
}

/**
 * The note shown before a running download is rescheduled: "Pauses now and starts 01:00
 * tonight".
 */
internal fun rescheduleNote(schedule: DownloadSchedule, now: Instant, zone: TimeZone): String {
  val label = startTimeLabel(schedule, now, zone)
  return "Pauses now and ${label.replaceFirstChar { it.lowercaseChar() }}"
}

/**
 * When [this] row starts: the time a scheduled one waits for, else now. The request keeps the
 * schedule it was added with, which says nothing once that time has passed.
 */
internal val TaskRow.startSchedule: DownloadSchedule
  get() = (state as? DownloadState.Scheduled)?.schedule ?: DownloadSchedule.Immediate

/**
 * What the Speed, Connections, Priority and Start controls show for [rows]: each value when every
 * row shares it, else `null`, drawn as "—".
 */
@Immutable
internal data class SharedSettings(
  val speedLimit: SpeedLimit?,
  val connections: Int?,
  val priority: DownloadPriority?,
  val schedule: DownloadSchedule?,
) {
  companion object {
    fun of(rows: List<TaskRow>): SharedSettings = SharedSettings(
      speedLimit = rows.map { it.request.speedLimit }.distinct().singleOrNull(),
      connections = rows.map { it.request.connections }.distinct().singleOrNull(),
      priority = rows.map { it.request.priority }.distinct().singleOrNull(),
      schedule = rows.map { it.startSchedule }.distinct().singleOrNull(),
    )
  }
}

/** Whether the inspector offers live controls for a task in this state. */
internal val DownloadState.hasControls: Boolean
  get() = when (this) {
    is DownloadState.Downloading,
    is DownloadState.Paused,
    is DownloadState.Queued,
    is DownloadState.Scheduled -> true
    is DownloadState.Completed,
    is DownloadState.Failed,
    is DownloadState.Canceled -> false
  }

/**
 * The summary line of several selected [rows]: "3 selected · 2.4 GB · 9.1 MB/s", the size of
 * those whose size is known and the speed of those downloading.
 */
internal fun selectionLine(rows: List<TaskRow>): String {
  val size = rows.mapNotNull { it.sizeBytes }.sum()
  val speed = rows.mapNotNull { it.speed }.sum()
  return listOfNotNull(
    "${rows.size} selected",
    size.takeIf { it > 0 }?.let(::formatSize),
    speed.takeIf { it > 0 }?.let(::formatSpeed),
  ).joinToString(" · ")
}

private const val PRESET_COUNT = 3

/** Round speeds the presets are taken from, slowest first. */
private val PresetLadder: List<SpeedLimit> = listOf(
  SpeedLimit.kbps(64),
  SpeedLimit.kbps(128),
  SpeedLimit.kbps(256),
  SpeedLimit.kbps(512),
  SpeedLimit.mbps(1),
  SpeedLimit.mbps(2),
  SpeedLimit.mbps(5),
  SpeedLimit.mbps(10),
  SpeedLimit.mbps(20),
  SpeedLimit.mbps(50),
  SpeedLimit.mbps(100),
)

private val DefaultPresets = listOf(SpeedLimit.mbps(5), SpeedLimit.mbps(2), SpeedLimit.mbps(1))
