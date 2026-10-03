package com.linroid.ketch.app.util

import com.linroid.ketch.api.DownloadConfig
import com.linroid.ketch.api.DownloadProgress
import com.linroid.ketch.api.DownloadRequest
import com.linroid.ketch.api.DownloadSchedule
import com.linroid.ketch.api.DownloadState
import com.linroid.ketch.api.Segment
import com.linroid.ketch.app.i18n.ByteUnit
import com.linroid.ketch.app.i18n.UiText
import com.linroid.ketch.app.i18n.clockTime
import com.linroid.ketch.app.i18n.decimal
import com.linroid.ketch.app.i18n.durationText
import com.linroid.ketch.app.i18n.joinText
import com.linroid.ketch.app.i18n.percentText
import com.linroid.ketch.app.i18n.shortDateText
import com.linroid.ketch.app.i18n.sizeText
import com.linroid.ketch.app.i18n.spanText
import com.linroid.ketch.app.i18n.speedText
import com.linroid.ketch.app.i18n.text
import com.linroid.ketch.app.i18n.verbatim
import com.linroid.ketch.app.state.DeviceInfo
import com.linroid.ketch.app.state.RowAction
import com.linroid.ketch.app.state.taskActions
import ketch.app.shared.generated.resources.Res
import ketch.app.shared.generated.resources.date_today_at
import ketch.app.shared.generated.resources.date_yesterday
import ketch.app.shared.generated.resources.row_canceled
import ketch.app.shared.generated.resources.row_completed
import ketch.app.shared.generated.resources.row_connections
import ketch.app.shared.generated.resources.row_files
import ketch.app.shared.generated.resources.row_limited_by_slow_lane
import ketch.app.shared.generated.resources.row_missing_file
import ketch.app.shared.generated.resources.row_paused
import ketch.app.shared.generated.resources.row_saved_on
import ketch.app.shared.generated.resources.row_stalled_for
import ketch.app.shared.generated.resources.row_starts_after
import ketch.app.shared.generated.resources.row_starts_in
import ketch.app.shared.generated.resources.row_starts_on
import ketch.app.shared.generated.resources.row_starts_today
import ketch.app.shared.generated.resources.row_starts_tomorrow
import ketch.app.shared.generated.resources.row_status_canceled
import ketch.app.shared.generated.resources.row_status_done
import ketch.app.shared.generated.resources.row_status_downloading
import ketch.app.shared.generated.resources.row_status_failed
import ketch.app.shared.generated.resources.row_status_file_missing
import ketch.app.shared.generated.resources.row_status_paused
import ketch.app.shared.generated.resources.row_status_queued
import ketch.app.shared.generated.resources.row_status_scheduled
import ketch.app.shared.generated.resources.row_status_stalled
import ketch.app.shared.generated.resources.row_took
import ketch.app.shared.generated.resources.row_waiting_for_conditions
import ketch.app.shared.generated.resources.size_progress
import ketch.app.shared.generated.resources.size_progress_compact
import kotlinx.datetime.DateTimeUnit
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
  val statusText: UiText,
  val detail: UiText,
  val size: UiText,
  val speed: UiText = UiText.Empty,
  val time: UiText = UiText.Empty,
  val added: UiText,
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
        statusText = if (stalled) {
          Res.string.row_status_stalled.text()
        } else {
          Res.string.row_status_downloading.text()
        },
        detail = if (stalled) {
          Res.string.row_stalled_for.text(durationText(stalledFor))
        } else {
          downloadingDetail(request, segments, host, context.slowLane)
        },
        size = runningSize(state),
        speed = speedText(progress.bytesPerSecond),
        time = if (stalled) UNKNOWN else timeLeft(progress),
        added = added,
        progress = fraction(progress),
        limited = limited,
      )
    }
    is DownloadState.Paused -> {
      val progress = state.progress
      val percent = if (progress.totalBytes > 0) {
        percentText((progress.downloadedBytes * 100 / progress.totalBytes).coerceIn(0, 100).toInt())
      } else {
        null
      }
      RowContent(
        status = RowStatus.Paused,
        statusText = Res.string.row_status_paused.text(),
        detail = listOfNotNull(Res.string.row_paused.text(), percent).joinText(),
        size = runningSize(state),
        added = added,
        progress = fraction(progress),
      )
    }
    is DownloadState.Queued -> RowContent(
      status = RowStatus.Queued,
      statusText = Res.string.row_status_queued.text(),
      detail = (context.config?.let { QueueReason.of(request, it, context.running) }
        ?: QueueReason.Next).text,
      size = knownSize(request),
      added = added,
    )
    is DownloadState.Scheduled -> RowContent(
      status = RowStatus.Scheduled,
      statusText = Res.string.row_status_scheduled.text(),
      detail = scheduleText(state.schedule, context.now, context.timeZone),
      size = knownSize(request),
      added = added,
    )
    is DownloadState.Completed -> completedContent(state, host, context.device, missing, added)
    is DownloadState.Failed -> {
      val copy = state.error.toCopy(request, retryCount, context.device)
      RowContent(
        status = RowStatus.Failed,
        statusText = Res.string.row_status_failed.text(),
        detail = listOfNotNull(copy.title, copy.shortHint).joinText(),
        size = knownSize(request),
        added = added,
        error = copy,
      )
    }
    is DownloadState.Canceled -> RowContent(
      status = RowStatus.Canceled,
      statusText = Res.string.row_status_canceled.text(),
      detail = listOfNotNull(Res.string.row_canceled.text(), host?.let(::verbatim)).joinText(),
      size = UiText.Empty,
      added = added,
    )
  }
  return content.copy(primary = primary)
}

/**
 * When a task was added, in [timeZone]: "Today 11:42", "Yesterday", "Sep 28", or "Sep 28, 2025"
 * in an earlier year.
 */
fun formatAdded(createdAt: Instant, now: Instant, timeZone: TimeZone): UiText {
  val added = createdAt.toLocalDateTime(timeZone)
  val today = now.toLocalDateTime(timeZone).date
  return when (added.date) {
    today -> Res.string.date_today_at.text(clockTime(added))
    today.minus(1, DateTimeUnit.DAY) -> Res.string.date_yesterday.text()
    else -> shortDateText(added.date, today)
  }
}

/** A downloading task counts as stalled once it has received no data for longer than this. */
internal val STALL_THRESHOLD: Duration = 5.seconds

private fun completedContent(
  state: DownloadState.Completed,
  host: String?,
  device: DeviceInfo,
  missing: Boolean,
  added: UiText,
): RowContent {
  val size = state.totalBytes?.let(::sizeText)
  val detail = when {
    device.capabilities.isRemote -> Res.string.row_saved_on.text(device.name)
    missing -> Res.string.row_missing_file.text()
    else -> {
      val parts = transferSummary(state).filter { it != size } +
        listOfNotNull(host?.let(::verbatim))
      if (parts.isEmpty()) Res.string.row_completed.text() else parts.joinText()
    }
  }
  return RowContent(
    status = if (missing) RowStatus.FileMissing else RowStatus.Completed,
    statusText = if (missing) {
      Res.string.row_status_file_missing.text()
    } else {
      Res.string.row_status_done.text()
    },
    detail = detail,
    size = size ?: UNKNOWN,
    time = state.downloadTime?.let { Res.string.row_took.text(durationText(it)) } ?: UiText.Empty,
    added = added,
  )
}

private fun downloadingDetail(
  request: DownloadRequest,
  segments: List<Segment>,
  host: String?,
  slowLane: Boolean,
): UiText {
  // A torrent's segments are its selected files, not connections.
  val parts = if (isTorrent(request)) {
    listOfNotNull(segments.size.takeIf { it > 1 }?.let { Res.plurals.row_files.text(it) })
  } else {
    val connections = segments.count { !it.isComplete }
    listOfNotNull(connections.takeIf { it > 0 }?.let { Res.plurals.row_connections.text(it) })
  }
  val detail = parts + listOfNotNull(
    host?.let(::verbatim),
    Res.string.row_limited_by_slow_lane.text().takeIf { slowLane },
  )
  return if (detail.isEmpty()) Res.string.row_status_downloading.text() else detail.joinText()
}

private fun runningSize(state: DownloadState): UiText {
  val progress = when (state) {
    is DownloadState.Downloading -> state.progress
    is DownloadState.Paused -> state.progress
    else -> return UNKNOWN
  }
  return when {
    progress.totalBytes > 0 -> formatSizeOf(progress.downloadedBytes, progress.totalBytes)
    progress.downloadedBytes > 0 -> sizeText(progress.downloadedBytes)
    else -> UNKNOWN
  }
}

/**
 * [downloaded] of [total] bytes in the unit of [total], to three significant digits, as the
 * table's Size column shows it: "2.41/5.69 GB", "0.49/1.20 GB", "138/512 MB". When not
 * [compact] it reads as list rows show it: "2.41 of 5.69 GB".
 */
fun formatSizeOf(downloaded: Long, total: Long, compact: Boolean = true): UiText {
  val unit = ByteUnit.of(total)
  fun number(bytes: Long): String {
    if (unit == ByteUnit.B) return bytes.coerceAtLeast(0).toString()
    val value = bytes.coerceAtLeast(0).toDouble() / unit.bytes
    val decimals = when {
      value < 10 -> 2
      value < 100 -> 1
      else -> 0
    }
    return decimal(value, decimals)
  }
  val totalText = unit.text(number(total))
  val part = number(downloaded.coerceAtMost(total))
  return if (compact) {
    Res.string.size_progress_compact.text(part, totalText)
  } else {
    Res.string.size_progress.text(part, totalText)
  }
}

private fun knownSize(request: DownloadRequest): UiText =
  request.resolvedSource?.totalBytes?.takeIf { it > 0 }?.let(::sizeText) ?: UNKNOWN

/** Fraction downloaded, kept within `0..1`, or `null` while the size is unknown. */
private fun fraction(progress: DownloadProgress): Float? =
  progress.percent.coerceIn(0f, 1f).takeIf { progress.totalBytes > 0 }

private fun timeLeft(progress: DownloadProgress): UiText {
  if (progress.totalBytes <= 0 || progress.bytesPerSecond <= 0) return UNKNOWN
  val remaining = (progress.totalBytes - progress.downloadedBytes).coerceAtLeast(0)
  return durationText((remaining / progress.bytesPerSecond).seconds)
}

private fun scheduleText(schedule: DownloadSchedule, now: Instant, timeZone: TimeZone): UiText =
  when (schedule) {
    is DownloadSchedule.AtTime -> {
      val start = schedule.startAt.toLocalDateTime(timeZone)
      val today = now.toLocalDateTime(timeZone).date
      val time = clockTime(start)
      val startsAt = when (start.date) {
        today -> Res.string.row_starts_today.text(time)
        today.plus(1, DateTimeUnit.DAY) -> Res.string.row_starts_tomorrow.text(time)
        else -> Res.string.row_starts_on.text(shortDateText(start.date, today), time)
      }
      val remaining = schedule.startAt - now
      if (remaining > Duration.ZERO) {
        listOf(startsAt, Res.string.row_starts_in.text(spanText(remaining))).joinText()
      } else {
        startsAt
      }
    }
    // The delay counts from when the task was added, which the state does not tell.
    is DownloadSchedule.AfterDelay -> Res.string.row_starts_after.text(spanText(schedule.delay))
    is DownloadSchedule.Immediate -> Res.string.row_waiting_for_conditions.text()
  }

/** What a column shows while its value is unknown. */
private val UNKNOWN: UiText = verbatim("–")
