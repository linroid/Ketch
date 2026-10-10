package com.linroid.ketch.app.ui.inspector

import androidx.compose.runtime.Immutable
import com.linroid.ketch.api.DownloadPriority
import com.linroid.ketch.api.DownloadRequest
import com.linroid.ketch.api.DownloadSchedule
import com.linroid.ketch.api.DownloadState
import com.linroid.ketch.api.PauseReason
import com.linroid.ketch.api.SpeedLimit
import com.linroid.ketch.app.components.startTimeFollowOnText
import com.linroid.ketch.app.i18n.UiText
import com.linroid.ketch.app.i18n.durationText
import com.linroid.ketch.app.i18n.joinText
import com.linroid.ketch.app.i18n.percentText
import com.linroid.ketch.app.i18n.priorityText
import com.linroid.ketch.app.i18n.text
import com.linroid.ketch.app.i18n.verbatim
import com.linroid.ketch.app.state.TaskKey
import com.linroid.ketch.app.state.TaskRow
import com.linroid.ketch.app.state.speedLimitText
import com.linroid.ketch.app.ui.inspector.tabs.bytesOfText
import com.linroid.ketch.app.ui.inspector.tabs.compactSizeText
import com.linroid.ketch.app.ui.inspector.tabs.compactSpeedText
import com.linroid.ketch.app.ui.inspector.tabs.sameByteUnit
import com.linroid.ketch.app.util.RowStatus
import com.linroid.ketch.app.util.TaskOrigin
import com.linroid.ketch.app.util.clockTime
import com.linroid.ketch.app.util.startingReason
import com.linroid.ketch.app.util.urlHost
import ketch.app.shared.generated.resources.Res
import ketch.app.shared.generated.resources.date_at_time
import ketch.app.shared.generated.resources.inspector_captured
import ketch.app.shared.generated.resources.inspector_file_missing
import ketch.app.shared.generated.resources.inspector_metric_done_at
import ketch.app.shared.generated.resources.inspector_metric_left
import ketch.app.shared.generated.resources.inspector_origin_agent
import ketch.app.shared.generated.resources.inspector_origin_app
import ketch.app.shared.generated.resources.inspector_origin_browser
import ketch.app.shared.generated.resources.inspector_origin_cli
import ketch.app.shared.generated.resources.inspector_origin_discover
import ketch.app.shared.generated.resources.inspector_reason_full_speed
import ketch.app.shared.generated.resources.inspector_reason_global_limit
import ketch.app.shared.generated.resources.inspector_reason_own_limit
import ketch.app.shared.generated.resources.inspector_reason_paused_restarts
import ketch.app.shared.generated.resources.inspector_reason_paused_resumes
import ketch.app.shared.generated.resources.inspector_reason_reconnect
import ketch.app.shared.generated.resources.inspector_reason_remove_limit
import ketch.app.shared.generated.resources.inspector_reason_slow_lane
import ketch.app.shared.generated.resources.inspector_reason_speed_settings
import ketch.app.shared.generated.resources.inspector_reschedule_note
import ketch.app.shared.generated.resources.inspector_selected
import ketch.app.shared.generated.resources.inspector_size_of
import ketch.app.shared.generated.resources.inspector_source_link
import ketch.app.shared.generated.resources.inspector_source_magnet
import ketch.app.shared.generated.resources.inspector_source_torrent_file
import ketch.app.shared.generated.resources.inspector_urgent_note
import ketch.app.shared.generated.resources.inspector_urgent_note_count
import ketch.app.shared.generated.resources.inspector_with_cookies
import ketch.app.shared.generated.resources.inspector_with_cookies_alone
import ketch.app.shared.generated.resources.inspector_with_cookies_referrer
import ketch.app.shared.generated.resources.inspector_with_cookies_referrer_alone
import ketch.app.shared.generated.resources.inspector_with_referrer
import ketch.app.shared.generated.resources.inspector_with_referrer_alone
import ketch.app.shared.generated.resources.queue_starting
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.jetbrains.compose.resources.StringResource
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant

/** The chip a [InspectorReason] offers. */
internal enum class ReasonAction(private val resource: StringResource) {
  /** Pauses and resumes a stalled download, so it opens fresh connections. */
  Reconnect(Res.string.inspector_reason_reconnect),

  /** Turns the Slow lane off. */
  FullSpeed(Res.string.inspector_reason_full_speed),

  /** Removes the download's own speed limit. */
  RemoveLimit(Res.string.inspector_reason_remove_limit),

  /** Opens the device's Speed settings, where its global limit is set. */
  SpeedSettings(Res.string.inspector_reason_speed_settings);

  /** What the chip says. */
  val label: UiText get() = resource.text()
}

/**
 * Why a task is where it is, under the inspector's metric line, such as "Limited by Slow lane
 * (1 MB/s)", with the one [action] that changes it.
 *
 * @property warning whether it reports a problem, such as a stall, drawn in the throttled color.
 */
@Immutable
internal data class InspectorReason(
  val text: UiText,
  val action: ReasonAction? = null,
  val warning: Boolean = false,
)

/**
 * The reason line of [row], or `null` when nothing needs explaining. A stall comes first; then
 * the limit that caps a running download: the Slow lane when [slowLane] is on and [globalCap]
 * is the lower one, the download's own limit, or the device's [globalCap]. Waiting tasks say why
 * they wait, starting ones that they start or find peers, tasks the engine paused say why and
 * when they go on, and paused ones whether resuming keeps their progress; a completed download
 * whose file is gone ([fileMissing]) says so. The action bar already offers Resume, Start now and
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
    // The subline already names the site that a starting row's detail may be.
    is DownloadState.Queued if row.isStarting ->
      InspectorReason(startingReason(row.request) ?: Res.string.queue_starting.text())
    is DownloadState.Queued, is DownloadState.Scheduled -> InspectorReason(content.detail)
    // The row explains a pause the engine made, and when the task goes on by itself.
    is DownloadState.Paused -> if (row.state.reason != PauseReason.User) {
      InspectorReason(content.detail)
    } else {
      val resumable = row.request.resolvedSource?.supportsResume != false
      InspectorReason(
        if (resumable) {
          Res.string.inspector_reason_paused_resumes.text()
        } else {
          Res.string.inspector_reason_paused_restarts.text()
        },
      )
    }
    is DownloadState.Completed -> {
      val missing = fileMissing || content.status == RowStatus.FileMissing
      if (missing && !row.device.capabilities.isRemote) {
        InspectorReason(Res.string.inspector_file_missing.text(), warning = true)
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
    globalWins && slowLane -> InspectorReason(
      Res.string.inspector_reason_slow_lane.text(speedLimitText(global)),
      ReasonAction.FullSpeed,
    )
    globalWins -> InspectorReason(
      Res.string.inspector_reason_global_limit.text(speedLimitText(global)),
      ReasonAction.SpeedSettings,
    )
    !own.isUnlimited -> InspectorReason(
      Res.string.inspector_reason_own_limit.text(speedLimitText(own)),
      ReasonAction.RemoveLimit,
    )
    else -> null
  }
}

/**
 * A part of the inspector's metric line.
 *
 * @property strong whether it is drawn in the primary color, as the share and speed are.
 */
@Immutable
internal data class MetricPart(val text: UiText, val strong: Boolean = false)

/**
 * The parts of the inspector's metric line for [row] at [now], joined with " · ":
 * "42%", "2.4 of 5.7 GB", "6.4 MB/s", "2m 10s left" and "done ≈ 14:32" while downloading; the
 * share and bytes while paused or failed; the known size while waiting; nothing once it ended.
 */
internal fun metricParts(row: TaskRow, now: Instant, zone: TimeZone): List<MetricPart> {
  val state = row.state
  val progress = when (state) {
    is DownloadState.Downloading -> state.progress
    is DownloadState.Paused -> state.progress
    else -> null
  }
  return when (state) {
    is DownloadState.Downloading -> buildList {
      addAll(progressParts(progress?.downloadedBytes, progress?.totalBytes))
      row.speed?.let { add(MetricPart(compactSpeedText(it), strong = true)) }
      val left = row.timeLeft
      if (left != null) {
        add(MetricPart(Res.string.inspector_metric_left.text(durationText(left))))
        if (left < 1.days) {
          add(MetricPart(Res.string.inspector_metric_done_at.text(clockTime(now + left, zone))))
        }
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

private fun progressParts(downloaded: Long?, total: Long?): List<MetricPart> {
  if (downloaded == null) return emptyList()
  if (total == null || total <= 0) return listOf(MetricPart(compactSizeText(downloaded)))
  val percent = (downloaded.coerceIn(0, total) * 100 / total).toInt()
  // "2.4 of 5.7 GB" in one unit, but "474 MB of 1.3 GB" rather than "0.5 of 1.3 GB".
  val bytes = if (sameByteUnit(downloaded, total)) {
    bytesOfText(downloaded, total)
  } else {
    Res.string.inspector_size_of.text(compactSizeText(downloaded), compactSizeText(total))
  }
  return listOf(MetricPart(percentText(percent), strong = true), MetricPart(bytes))
}

private fun knownSize(row: TaskRow): List<MetricPart> =
  listOfNotNull(row.sizeBytes?.let { MetricPart(compactSizeText(it)) })

/**
 * When [row] was added, for the Added detail: the row's own "Today 11:42", or an earlier day
 * with its time, such as "Yesterday 18:05" or "Sep 28 09:12".
 */
internal fun addedDetail(row: TaskRow, now: Instant, zone: TimeZone): UiText {
  val today = now.toLocalDateTime(zone).date
  if (row.createdAt.toLocalDateTime(zone).date == today) return row.content.added
  return Res.string.date_at_time.text(row.content.added, clockTime(row.createdAt, zone))
}

/**
 * When [row] finished, for the Finished detail, in the form of [addedDetail]; `null` while it
 * has not completed or its finish time is unknown.
 */
internal fun finishedDetail(row: TaskRow, now: Instant, zone: TimeZone): UiText? {
  val finishedAt = row.finishedAt ?: return null
  val today = now.toLocalDateTime(zone).date
  if (finishedAt.toLocalDateTime(zone).date == today) return row.content.finished
  return Res.string.date_at_time.text(row.content.finished, clockTime(finishedAt, zone))
}

/**
 * Where [request] downloads from, for the Source detail: "HTTPS · releases.ubuntu.com",
 * "FTP · ftp.example.org", "BitTorrent · magnet" or "BitTorrent · torrent file".
 */
internal fun sourceLabel(request: DownloadRequest, isTorrent: Boolean): UiText {
  val url = request.url.trim()
  val scheme = url.substringBefore(':', "").lowercase()
  if (isTorrent) {
    return when {
      scheme == "magnet" -> Res.string.inspector_source_magnet.text()
      url.substringBefore('?').endsWith(".torrent", ignoreCase = true) ||
        scheme == "torrent" -> Res.string.inspector_source_torrent_file.text()
      else -> verbatim("BitTorrent")
    }
  }
  val protocol = if (scheme.isEmpty()) {
    Res.string.inspector_source_link.text()
  } else {
    verbatim(scheme.uppercase())
  }
  return listOfNotNull(protocol, urlHost(url)?.let(::verbatim)).joinText()
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
internal fun capturedText(request: DownloadRequest): UiText? {
  val origin = when (TaskOrigin.of(request)) {
    TaskOrigin.Browser -> Res.string.inspector_origin_browser
    TaskOrigin.Discover -> Res.string.inspector_origin_discover
    TaskOrigin.Agent -> Res.string.inspector_origin_agent
    TaskOrigin.App -> Res.string.inspector_origin_app
    TaskOrigin.Cli -> Res.string.inspector_origin_cli
    null -> null
  }
  val names = request.headers.keys.map { it.lowercase() }.toSet()
  val cookies = "cookie" in names
  val referrer = "referer" in names
  // What came along, after the origin or on its own.
  val extras = when {
    cookies && referrer ->
      Res.string.inspector_with_cookies_referrer to Res.string.inspector_with_cookies_referrer_alone
    cookies -> Res.string.inspector_with_cookies to Res.string.inspector_with_cookies_alone
    referrer -> Res.string.inspector_with_referrer to Res.string.inspector_with_referrer_alone
    else -> null
  }
  return when {
    extras == null -> origin?.text()
    origin == null -> extras.second.text()
    else -> Res.string.inspector_captured.text(origin.text(), extras.first.text())
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
internal fun urgentNote(victim: TaskRow, count: Int = 1): UiText {
  val priority = priorityText(victim.request.priority)
  return if (count == 1) {
    Res.string.inspector_urgent_note.text(victim.name, priority)
  } else {
    Res.plurals.inspector_urgent_note_count.text(count, count, victim.name, priority)
  }
}

/**
 * The note shown before a running download is rescheduled: "Pauses now and starts 01:00
 * tonight".
 */
internal fun rescheduleNote(schedule: DownloadSchedule, now: Instant, zone: TimeZone): UiText =
  Res.string.inspector_reschedule_note.text(startTimeFollowOnText(schedule, now, zone))

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

/**
 * Whether the inspector offers live controls for a task in this state, which can still download,
 * so its speed cap and connections still apply.
 */
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
internal fun selectionLine(rows: List<TaskRow>): UiText {
  val size = rows.mapNotNull { it.sizeBytes }.sum()
  val speed = rows.mapNotNull { it.speed }.sum()
  return listOfNotNull(
    Res.plurals.inspector_selected.text(rows.size),
    size.takeIf { it > 0 }?.let(::compactSizeText),
    speed.takeIf { it > 0 }?.let(::compactSpeedText),
  ).joinText()
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
