package com.linroid.ketch.app.ui.intake

import com.linroid.ketch.api.DownloadProgress
import com.linroid.ketch.api.DownloadState
import com.linroid.ketch.api.DownloadTask
import com.linroid.ketch.api.ResolvedSource
import com.linroid.ketch.app.i18n.UiText
import com.linroid.ketch.app.i18n.joinText
import com.linroid.ketch.app.i18n.monthShortText
import com.linroid.ketch.app.i18n.percentText
import com.linroid.ketch.app.i18n.sizeText
import com.linroid.ketch.app.i18n.text
import com.linroid.ketch.app.i18n.verbatim
import com.linroid.ketch.app.state.IntakeEntry
import com.linroid.ketch.app.state.IntakeSource
import com.linroid.ketch.app.state.IntakeStatus
import com.linroid.ketch.app.util.LinkKind
import ketch.app.shared.generated.resources.Res
import ketch.app.shared.generated.resources.date_month_day
import ketch.app.shared.generated.resources.intake_age_days
import ketch.app.shared.generated.resources.intake_age_hours
import ketch.app.shared.generated.resources.intake_age_just_now
import ketch.app.shared.generated.resources.intake_age_minutes
import ketch.app.shared.generated.resources.intake_age_yesterday
import ketch.app.shared.generated.resources.intake_badge_duplicate
import ketch.app.shared.generated.resources.intake_badge_no_resume
import ketch.app.shared.generated.resources.intake_duplicate_canceled
import ketch.app.shared.generated.resources.intake_duplicate_downloading
import ketch.app.shared.generated.resources.intake_duplicate_failed
import ketch.app.shared.generated.resources.intake_duplicate_finished
import ketch.app.shared.generated.resources.intake_duplicate_paused
import ketch.app.shared.generated.resources.intake_duplicate_waiting
import ketch.app.shared.generated.resources.intake_scheme_torrent
import ketch.app.shared.generated.resources.intake_split
import ketch.app.shared.generated.resources.intake_status_checking
import ketch.app.shared.generated.resources.intake_status_downloads_again
import ketch.app.shared.generated.resources.intake_status_expands
import ketch.app.shared.generated.resources.intake_status_expands_first
import ketch.app.shared.generated.resources.intake_status_fetching
import ketch.app.shared.generated.resources.intake_status_files_chosen
import ketch.app.shared.generated.resources.intake_status_reading_torrent
import ketch.app.shared.generated.resources.intake_status_resumable
import ketch.app.shared.generated.resources.intake_status_unknown_size
import ketch.app.shared.generated.resources.intake_status_up_to_connections
import ketch.app.shared.generated.resources.intake_status_waiting
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/** How a row's status line reads. */
internal enum class LineTone { Neutral, Checking, Warning, Danger }

/**
 * A row's status line.
 *
 * @property badge a short label shown as a pill before [text], such as "No resume".
 */
internal data class StatusLine(
  val text: UiText,
  val tone: LineTone,
  val badge: UiText? = null,
)

/**
 * The status line of [entry] at [now]: what its check found, or how far it got.
 *
 * - "Checking…", or "Fetching file list from peers… 0:14" for a magnet;
 * - "5.7 GB · HTTPS · resumable · up to 16 connections", or a "No resume" pill;
 * - "4 × 1.1 GB · expands to 4 links" for a range;
 * - "3 of 128 files · 4.2 GB" for a torrent;
 * - the problem, or an "Already in Ketch" pill with "finished 2 days ago".
 */
internal fun statusLine(entry: IntakeEntry, now: Instant): StatusLine {
  val duplicate = entry.duplicate
  if (duplicate != null && !entry.downloadAgain) {
    val badge = Res.string.intake_badge_duplicate.text()
    return StatusLine(duplicateState(duplicate, now), LineTone.Warning, badge = badge)
  }
  val line = when (val status = entry.status) {
    IntakeStatus.Waiting -> StatusLine(Res.string.intake_status_waiting.text(), LineTone.Checking)
    is IntakeStatus.Checking -> when {
      entry.isMagnet -> StatusLine(
        Res.string.intake_status_fetching.text(elapsed(now - status.since)),
        LineTone.Checking,
      )
      entry.source is IntakeSource.File ->
        StatusLine(Res.string.intake_status_reading_torrent.text(), LineTone.Checking)
      else -> StatusLine(Res.string.intake_status_checking.text(), LineTone.Checking)
    }
    is IntakeStatus.Problem -> if (status.problem.blocksAdd) {
      StatusLine(status.problem.text, LineTone.Danger)
    } else {
      StatusLine(
        text = status.problem.detail ?: UiText.Empty,
        tone = LineTone.Warning,
        badge = status.problem.title,
      )
    }
    is IntakeStatus.Ready -> StatusLine(
      text = readyLine(entry, status.source),
      tone = LineTone.Neutral,
      badge = if (lacksResume(entry)) Res.string.intake_badge_no_resume.text() else null,
    )
  }
  return if (duplicate != null) {
    line.copy(text = Res.string.intake_status_downloads_again.text(line.text))
  } else {
    line
  }
}

private fun readyLine(entry: IntakeEntry, source: ResolvedSource): UiText {
  val size = source.totalBytes.takeIf { it >= 0 }?.let(::sizeText)
  val files = entry.files
  if (files.isNotEmpty()) {
    val selected = entry.selectedFiles?.size ?: files.size
    val bytes = entry.bytes?.let(::sizeText)
    val chosen = Res.plurals.intake_status_files_chosen.text(files.size, selected, files.size)
    return listOfNotNull(chosen, bytes).joinText()
  }
  val range = entry.source as? IntakeSource.Range
  if (range != null) {
    val count = range.urls.size
    val expands = if (range.truncated) {
      Res.plurals.intake_status_expands_first
    } else {
      Res.plurals.intake_status_expands
    }
    return listOfNotNull(
      size?.let { Res.string.intake_split.text(count, it) },
      expands.text(count),
    ).joinText()
  }
  return buildList {
    add(size ?: Res.string.intake_status_unknown_size.text())
    add(schemeLabel(entry.url, source))
    if (source.supportsResume) add(Res.string.intake_status_resumable.text())
    if (source.maxSegments > 1 && source.sourceType != TORRENT) {
      add(Res.plurals.intake_status_up_to_connections.text(source.maxSegments))
    }
  }.joinText()
}

/** Whether [entry] is checked but cannot resume, which its row marks with a "No resume" pill. */
internal fun lacksResume(entry: IntakeEntry): Boolean {
  val source = entry.resolved ?: return false
  return !source.supportsResume && source.sourceType != TORRENT
}

/** "HTTPS", "HTTP", "FTP", "FTPS" or "Torrent" for what downloads [url]. */
internal fun schemeLabel(url: String?, source: ResolvedSource?): UiText {
  if (source?.sourceType == TORRENT) return Res.string.intake_scheme_torrent.text()
  val scheme = url?.substringBefore("://", "")?.lowercase().orEmpty()
  return when {
    scheme.isNotEmpty() -> verbatim(scheme.uppercase())
    url != null && LinkKind.of(url) == LinkKind.Magnet -> Res.string.intake_scheme_torrent.text()
    else -> verbatim(source?.sourceType?.uppercase().orEmpty())
  }
}

/** What the existing [task] is doing, after "Already in Ketch · ". */
internal fun duplicateState(task: DownloadTask, now: Instant): UiText =
  when (val state = task.state.value) {
    is DownloadState.Completed -> {
      val finished = task.createdAt + (state.downloadTime ?: Duration.ZERO)
      Res.string.intake_duplicate_finished.text(relativeAge(finished, now))
    }
    is DownloadState.Downloading ->
      Res.string.intake_duplicate_downloading.text(percent(state.progress))
    is DownloadState.Paused -> Res.string.intake_duplicate_paused.text(percent(state.progress))
    is DownloadState.Queued, is DownloadState.Scheduled ->
      Res.string.intake_duplicate_waiting.text()
    is DownloadState.Failed -> Res.string.intake_duplicate_failed.text()
    is DownloadState.Canceled -> Res.string.intake_duplicate_canceled.text()
  }

/** "just now", "5 min ago", "3 h ago", "yesterday", "2 days ago", or "Sep 28" after a week. */
internal fun relativeAge(
  at: Instant,
  now: Instant,
  zone: TimeZone = TimeZone.currentSystemDefault(),
): UiText {
  val age = now - at
  return when {
    age < 1.minutes -> Res.string.intake_age_just_now.text()
    age < 1.hours -> Res.plurals.intake_age_minutes.text(age.inWholeMinutes.toInt())
    age < 1.days -> Res.plurals.intake_age_hours.text(age.inWholeHours.toInt())
    age < 2.days -> Res.string.intake_age_yesterday.text()
    age < 7.days -> Res.plurals.intake_age_days.text(age.inWholeDays.toInt())
    else -> {
      val date = at.toLocalDateTime(zone).date
      Res.string.date_month_day.text(monthShortText(date.month), date.month.ordinal + 1, date.day)
    }
  }
}

/** "0:14" or "2:05" for an [elapsed] wait. */
internal fun elapsed(elapsed: Duration): String {
  val seconds = elapsed.inWholeSeconds.coerceAtLeast(0)
  return "${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}"
}

/**
 * "16 × 360 MB": how a file of [totalBytes] splits over [connections], or `null` when its size
 * is unknown.
 */
internal fun splitLabel(totalBytes: Long, connections: Int): UiText? {
  if (totalBytes <= 0 || connections <= 0) return null
  return Res.string.intake_split.text(connections, sizeText(totalBytes / connections))
}

private fun percent(progress: DownloadProgress): UiText {
  val total = progress.totalBytes
  val done = progress.downloadedBytes
  if (total <= 0) return sizeText(done)
  return percentText((done * 100 / total).coerceIn(0, 100).toInt())
}

private const val TORRENT = "torrent"
