package com.linroid.ketch.app.ui.intake

import com.linroid.ketch.api.DownloadProgress
import com.linroid.ketch.api.DownloadState
import com.linroid.ketch.api.DownloadTask
import com.linroid.ketch.api.ResolvedSource
import com.linroid.ketch.app.state.IntakeEntry
import com.linroid.ketch.app.state.IntakeSource
import com.linroid.ketch.app.state.IntakeStatus
import com.linroid.ketch.app.util.LinkKind
import com.linroid.ketch.app.util.formatBytes
import com.linroid.ketch.app.util.shortName
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
  val text: String,
  val tone: LineTone,
  val badge: String? = null,
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
    return StatusLine(duplicateState(duplicate, now), LineTone.Warning, badge = "Already in Ketch")
  }
  val line = when (val status = entry.status) {
    IntakeStatus.Waiting -> StatusLine("Waiting to check…", LineTone.Checking)
    is IntakeStatus.Checking -> when {
      entry.isMagnet -> StatusLine(
        "Fetching file list from peers… ${elapsed(now - status.since)}",
        LineTone.Checking,
      )
      entry.source is IntakeSource.File ->
        StatusLine("Reading the torrent file…", LineTone.Checking)
      else -> StatusLine("Checking…", LineTone.Checking)
    }
    is IntakeStatus.Problem -> if (status.problem.blocksAdd) {
      StatusLine(status.problem.text, LineTone.Danger)
    } else {
      StatusLine(status.problem.detail.orEmpty(), LineTone.Warning, badge = status.problem.title)
    }
    is IntakeStatus.Ready -> StatusLine(
      text = readyLine(entry, status.source),
      tone = LineTone.Neutral,
      badge = if (lacksResume(entry)) "No resume" else null,
    )
  }
  return if (duplicate != null) line.copy(text = "Downloads again · ${line.text}") else line
}

private fun readyLine(entry: IntakeEntry, source: ResolvedSource): String {
  val size = source.totalBytes.takeIf { it >= 0 }?.let(::formatBytes)
  val files = entry.files
  if (files.isNotEmpty()) {
    val selected = entry.selectedFiles?.size ?: files.size
    val bytes = entry.bytes?.let(::formatBytes)
    return listOfNotNull("$selected of ${files.size} files", bytes).joinToString(SEPARATOR)
  }
  val range = entry.source as? IntakeSource.Range
  if (range != null) {
    val count = range.urls.size
    return listOfNotNull(
      size?.let { "$count × $it" },
      "expands to $count links" + if (range.truncated) " (the first $count)" else "",
    ).joinToString(SEPARATOR)
  }
  return buildList {
    add(size ?: "Unknown size")
    add(schemeLabel(entry.url, source))
    if (source.supportsResume) add("resumable")
    if (source.maxSegments > 1 && source.sourceType != TORRENT) {
      add("up to ${source.maxSegments} connections")
    }
  }.joinToString(SEPARATOR)
}

/** Whether [entry] is checked but cannot resume, which its row marks with a "No resume" pill. */
internal fun lacksResume(entry: IntakeEntry): Boolean {
  val source = entry.resolved ?: return false
  return !source.supportsResume && source.sourceType != TORRENT
}

/** "HTTPS", "HTTP", "FTP", "FTPS" or "Torrent" for what downloads [url]. */
internal fun schemeLabel(url: String?, source: ResolvedSource?): String {
  if (source?.sourceType == TORRENT) return "Torrent"
  val scheme = url?.substringBefore("://", "")?.lowercase().orEmpty()
  return when {
    scheme.isNotEmpty() -> scheme.uppercase()
    url != null && LinkKind.of(url) == LinkKind.Magnet -> "Torrent"
    else -> source?.sourceType?.uppercase() ?: ""
  }
}

/** What the existing [task] is doing, after "Already in Ketch · ". */
internal fun duplicateState(task: DownloadTask, now: Instant): String =
  when (val state = task.state.value) {
    is DownloadState.Completed -> {
      val finished = task.createdAt + (state.downloadTime ?: Duration.ZERO)
      "finished ${relativeAge(finished, now)}"
    }
    is DownloadState.Downloading -> "downloading · ${percent(state.progress)}"
    is DownloadState.Paused -> "paused at ${percent(state.progress)}"
    is DownloadState.Queued, is DownloadState.Scheduled -> "waiting to start"
    is DownloadState.Failed -> "failed"
    is DownloadState.Canceled -> "canceled"
  }

/** "just now", "5 min ago", "3 h ago", "yesterday", "2 days ago", or "Sep 28" after a week. */
internal fun relativeAge(
  at: Instant,
  now: Instant,
  zone: TimeZone = TimeZone.currentSystemDefault(),
): String {
  val age = now - at
  return when {
    age < 1.minutes -> "just now"
    age < 1.hours -> "${age.inWholeMinutes} min ago"
    age < 1.days -> "${age.inWholeHours} h ago"
    age < 2.days -> "yesterday"
    age < 7.days -> "${age.inWholeDays} days ago"
    else -> {
      val date = at.toLocalDateTime(zone).date
      "${date.month.shortName} ${date.day}"
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
internal fun splitLabel(totalBytes: Long, connections: Int): String? {
  if (totalBytes <= 0 || connections <= 0) return null
  return "$connections × ${formatBytes(totalBytes / connections)}"
}

private fun percent(progress: DownloadProgress): String {
  val total = progress.totalBytes
  val done = progress.downloadedBytes
  return if (total > 0) "${(done * 100 / total).coerceIn(0, 100)}%" else formatBytes(done)
}

private const val SEPARATOR = " · "
private const val TORRENT = "torrent"
