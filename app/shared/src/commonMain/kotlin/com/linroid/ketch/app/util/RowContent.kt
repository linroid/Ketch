package com.linroid.ketch.app.util

import com.linroid.ketch.api.DownloadConfig
import com.linroid.ketch.api.DownloadProgress
import com.linroid.ketch.api.DownloadRequest
import com.linroid.ketch.api.DownloadSchedule
import com.linroid.ketch.api.DownloadState
import com.linroid.ketch.api.Segment
import com.linroid.ketch.app.state.DeviceInfo
import com.linroid.ketch.app.state.RowAction
import com.linroid.ketch.app.state.taskActions
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/** The status dot of a row. */
enum class RowStatus {
  Downloading,

  /** Downloading, but no data arrived for more than 5 seconds. */
  Stalled,
  Paused,
  Queued,
  Scheduled,
  Completed,

  /** Completed, but the file has been moved or deleted since. */
  FileMissing,
  Failed,
  Canceled,
}

/**
 * What the rows of one device share at a moment.
 *
 * @property device the device that runs the tasks.
 * @property now the current time, for countdowns and "Today".
 * @property timeZone zone of local dates and times.
 * @property config the device's download configuration, which gives the queue limits and the
 *   retry count; `null` while unknown.
 * @property running requests of the device's downloading tasks, which occupy its slots.
 * @property slowLane whether the device is capped by Slow lane.
 */
data class RowContext(
  val device: DeviceInfo,
  val now: Instant,
  val timeZone: TimeZone,
  val config: DownloadConfig? = null,
  val running: List<DownloadRequest> = emptyList(),
  val slowLane: Boolean = false,
)

/**
 * The text of a task's row, shared by the table, list rows and the inspector.
 *
 * @property status the status dot.
 * @property statusText the status in a word or two, such as "Paused".
 * @property detail the second line, or the reason a task waits or failed, such as
 *   "8 connections · releases.ubuntu.com".
 * @property size bytes so far of total, the final size, or "–" when unknown.
 * @property speed current speed while downloading, otherwise empty.
 * @property time time left while downloading ("–" when unknown), time taken when completed,
 *   otherwise empty.
 * @property added when the task was added: "Today 11:42", "Yesterday" or "Sep 28".
 * @property progress fraction downloaded while downloading or paused with a known size.
 * @property limited whether a speed limit caps the running task.
 * @property error the failure explained, for failed tasks; its title leads [detail].
 * @property primary the row's trailing action.
 */
data class RowContent(
  val status: RowStatus,
  val statusText: String,
  val detail: String,
  val size: String,
  val speed: String = "",
  val time: String = "",
  val added: String,
  val progress: Float? = null,
  val limited: Boolean = false,
  val error: ErrorCopy? = null,
  val primary: RowAction? = null,
)

/**
 * The row text of a task downloading [request], added at [createdAt], while in [state].
 *
 * @param segments the task's segments, which count its connections.
 * @param stalledFor how long a downloading task has received no data, or `null` while data
 *   arrives.
 * @param fileMissing whether a completed task's file is gone; only checked on local devices.
 */
fun rowContent(
  request: DownloadRequest,
  state: DownloadState,
  createdAt: Instant,
  context: RowContext,
  segments: List<Segment> = emptyList(),
  stalledFor: Duration? = null,
  fileMissing: Boolean = false,
): RowContent {
  val stalled = state is DownloadState.Downloading && stalledFor != null &&
    stalledFor > STALL_THRESHOLD
  val missing = fileMissing && !context.device.capabilities.isRemote
  val retryCount = context.config?.retryCount ?: 0
  val primary = taskActions(
    request = request,
    state = state,
    device = context.device,
    retryCount = retryCount,
    stalled = stalled,
    fileMissing = missing,
  ).primary
  val added = formatAdded(createdAt, context.now, context.timeZone)
  val host = urlHost(request.url)
  val content = when (state) {
    is DownloadState.Downloading -> {
      val progress = state.progress
      val limited = !request.speedLimit.isUnlimited || context.slowLane ||
        context.config?.speedLimit?.isUnlimited == false
      RowContent(
        status = if (stalled) RowStatus.Stalled else RowStatus.Downloading,
        statusText = if (stalled) "Stalled" else "Downloading",
        detail = if (stalled) {
          "Stalled · no data for ${formatDuration(stalledFor)}"
        } else {
          downloadingDetail(request, segments, host, context.slowLane)
        },
        size = runningSize(state),
        speed = "${formatBytes(progress.bytesPerSecond.coerceAtLeast(0))}/s",
        time = if (stalled) UNKNOWN else timeLeft(progress),
        added = added,
        progress = fraction(progress),
        limited = limited,
      )
    }
    is DownloadState.Paused -> {
      val progress = state.progress
      val percent = if (progress.totalBytes > 0) {
        "${(progress.downloadedBytes * 100 / progress.totalBytes).coerceIn(0, 100)}%"
      } else {
        null
      }
      RowContent(
        status = RowStatus.Paused,
        statusText = "Paused",
        detail = listOfNotNull("Paused", percent).joinToString(SEPARATOR),
        size = runningSize(state),
        added = added,
        progress = fraction(progress),
      )
    }
    is DownloadState.Queued -> RowContent(
      status = RowStatus.Queued,
      statusText = "Queued",
      detail = (context.config?.let { QueueReason.of(request, it, context.running) }
        ?: QueueReason.Next).text,
      size = knownSize(request),
      added = added,
    )
    is DownloadState.Scheduled -> RowContent(
      status = RowStatus.Scheduled,
      statusText = "Scheduled",
      detail = scheduleText(state.schedule, context.now, context.timeZone),
      size = knownSize(request),
      added = added,
    )
    is DownloadState.Completed -> completedContent(state, host, context.device, missing, added)
    is DownloadState.Failed -> {
      val copy = state.error.toCopy(request, retryCount, context.device)
      RowContent(
        status = RowStatus.Failed,
        statusText = "Failed",
        detail = listOfNotNull(copy.title, copy.shortHint).joinToString(SEPARATOR),
        size = knownSize(request),
        added = added,
        error = copy,
      )
    }
    is DownloadState.Canceled -> RowContent(
      status = RowStatus.Canceled,
      statusText = "Canceled",
      detail = listOfNotNull("Canceled", host).joinToString(SEPARATOR),
      size = "",
      added = added,
    )
  }
  return content.copy(primary = primary)
}

/**
 * When a task was added, in [timeZone]: "Today 11:42", "Yesterday", "Sep 28", or "Sep 28, 2025"
 * in an earlier year.
 */
fun formatAdded(createdAt: Instant, now: Instant, timeZone: TimeZone): String {
  val added = createdAt.toLocalDateTime(timeZone)
  val today = now.toLocalDateTime(timeZone).date
  return when (added.date) {
    today -> "Today ${added.clockText()}"
    today.minus(1, DateTimeUnit.DAY) -> "Yesterday"
    else -> shortDate(added.date, today)
  }
}

/** A downloading task counts as stalled once it has received no data for longer than this. */
internal val STALL_THRESHOLD: Duration = 5.seconds

private fun completedContent(
  state: DownloadState.Completed,
  host: String?,
  device: DeviceInfo,
  missing: Boolean,
  added: String,
): RowContent {
  val size = state.totalBytes?.let(::formatBytes)
  val detail = when {
    device.capabilities.isRemote -> "Saved on ${device.name}"
    missing -> "File moved or deleted"
    else -> (transferSummary(state).filter { it != size } + listOfNotNull(host))
      .joinToString(SEPARATOR).ifEmpty { "Completed" }
  }
  return RowContent(
    status = if (missing) RowStatus.FileMissing else RowStatus.Completed,
    statusText = if (missing) "File missing" else "Done",
    detail = detail,
    size = size ?: UNKNOWN,
    time = state.downloadTime?.let { "took ${formatDuration(it)}" }.orEmpty(),
    added = added,
  )
}

private fun downloadingDetail(
  request: DownloadRequest,
  segments: List<Segment>,
  host: String?,
  slowLane: Boolean,
): String {
  // A torrent's segments are its selected files, not connections.
  val parts = if (isTorrent(request)) {
    listOfNotNull(segments.size.takeIf { it > 1 }?.let { "$it files" })
  } else {
    val connections = segments.count { !it.isComplete }
    listOfNotNull(connections.takeIf { it > 0 }?.let(::connectionsText))
  }
  val detail = parts + listOfNotNull(host, "limited by Slow lane".takeIf { slowLane })
  return detail.joinToString(SEPARATOR).ifEmpty { "Downloading" }
}

private fun connectionsText(count: Int): String =
  if (count == 1) "1 connection" else "$count connections"

private fun runningSize(state: DownloadState): String {
  val progress = when (state) {
    is DownloadState.Downloading -> state.progress
    is DownloadState.Paused -> state.progress
    else -> return UNKNOWN
  }
  return when {
    progress.totalBytes > 0 -> formatSizeOf(progress.downloadedBytes, progress.totalBytes)
    progress.downloadedBytes > 0 -> formatBytes(progress.downloadedBytes)
    else -> UNKNOWN
  }
}

/**
 * [downloaded] of [total] bytes in the unit of [total], to three significant digits, as the
 * table's Size column shows it: "2.41/5.69 GB", "0.49/1.20 GB", "138/512 MB". With [separator]
 * " of " it reads as list rows show it: "2.41 of 5.69 GB".
 */
fun formatSizeOf(downloaded: Long, total: Long, separator: String = "/"): String {
  val unit = SIZE_UNITS.lastOrNull { total >= it.second } ?: SIZE_UNITS.first()
  fun number(bytes: Long): String {
    val value = bytes.coerceAtLeast(0).toDouble() / unit.second
    if (unit.second == 1L) return bytes.coerceAtLeast(0).toString()
    val decimals = when {
      value < 10 -> 2
      value < 100 -> 1
      else -> 0
    }
    return formatDecimal(value, decimals)
  }
  return "${number(downloaded.coerceAtMost(total))}$separator${number(total)} ${unit.first}"
}

private val SIZE_UNITS = listOf(
  "B" to 1L,
  "KB" to (1L shl 10),
  "MB" to (1L shl 20),
  "GB" to (1L shl 30),
  "TB" to (1L shl 40)
)

/** [value] rounded half up to [decimals] places, with a dot. */
private fun formatDecimal(value: Double, decimals: Int): String {
  var scale = 1L
  repeat(decimals) { scale *= 10 }
  val scaled = (value * scale + 0.5).toLong()
  if (decimals == 0) return scaled.toString()
  val fraction = (scaled % scale).toString().padStart(decimals, '0')
  return "${scaled / scale}.$fraction"
}

private fun knownSize(request: DownloadRequest): String =
  request.resolvedSource?.totalBytes?.takeIf { it > 0 }?.let(::formatBytes) ?: UNKNOWN

/** Fraction downloaded, kept within `0..1`, or `null` while the size is unknown. */
private fun fraction(progress: DownloadProgress): Float? =
  progress.percent.coerceIn(0f, 1f).takeIf { progress.totalBytes > 0 }

private fun timeLeft(progress: DownloadProgress): String {
  if (progress.totalBytes <= 0 || progress.bytesPerSecond <= 0) return UNKNOWN
  val remaining = (progress.totalBytes - progress.downloadedBytes).coerceAtLeast(0)
  return formatDuration((remaining / progress.bytesPerSecond).seconds)
}

private fun scheduleText(schedule: DownloadSchedule, now: Instant, timeZone: TimeZone): String =
  when (schedule) {
    is DownloadSchedule.AtTime -> {
      val start = schedule.startAt.toLocalDateTime(timeZone)
      val today = now.toLocalDateTime(timeZone).date
      val day = when (start.date) {
        today -> "today"
        today.plus(1, DateTimeUnit.DAY) -> "tomorrow"
        else -> shortDate(start.date, today)
      }
      val startsAt = "Starts $day at ${start.clockText()}"
      val remaining = schedule.startAt - now
      if (remaining > Duration.ZERO) "$startsAt · in ${formatSpan(remaining)}" else startsAt
    }
    // The delay counts from when the task was added, which the state does not tell.
    is DownloadSchedule.AfterDelay -> "Starts after ${formatSpan(schedule.delay)}"
    is DownloadSchedule.Immediate -> "Waiting for conditions"
  }

/**
 * A span to the minute, rounded up so a countdown never reads zero early: "45s", "30 min",
 * "3h 12m", "2d 4h".
 */
private fun formatSpan(duration: Duration): String {
  val seconds = duration.inWholeSeconds.coerceAtLeast(0)
  if (seconds < 60) return "${seconds}s"
  val minutes = (seconds + 59) / 60
  val days = minutes / (24 * 60)
  val hours = minutes / 60 % 24
  val mins = minutes % 60
  return when {
    days > 0 -> if (hours > 0) "${days}d ${hours}h" else "${days}d"
    hours > 0 -> if (mins > 0) "${hours}h ${mins}m" else "${hours}h"
    else -> "$mins min"
  }
}

private fun shortDate(date: LocalDate, today: LocalDate): String {
  val day = "${date.month.shortName} ${date.day}"
  return if (date.year == today.year) day else "$day, ${date.year}"
}

private const val SEPARATOR = " · "
private const val UNKNOWN = "–"
