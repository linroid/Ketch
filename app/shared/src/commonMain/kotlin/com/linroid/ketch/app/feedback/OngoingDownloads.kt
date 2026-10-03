package com.linroid.ketch.app.feedback

import com.linroid.ketch.api.DownloadProgress
import com.linroid.ketch.api.DownloadState
import com.linroid.ketch.api.DownloadTask
import com.linroid.ketch.api.Segment
import com.linroid.ketch.app.state.StatusFilter
import com.linroid.ketch.app.util.displayName
import com.linroid.ketch.app.util.formatBytes
import com.linroid.ketch.app.util.formatEta
import com.linroid.ketch.app.util.plural

/**
 * What an ongoing notification says about a device's downloads, such as the one the Android
 * download service shows: the tasks downloading or waiting for a slot, in list order.
 *
 * @property title "Downloading 3 files · 4.2 MB/s", or "Waiting to download 2 files" while every
 *   task waits.
 * @property text "1.2 GB of 3.4 GB · about 6 min left", only the bytes so far while a size is
 *   unknown, or `null` while every task waits.
 * @property permille overall progress out of [PROGRESS_MAX]; `null` while a size is unknown or
 *   every task waits.
 * @property lines a line per file, such as "ubuntu-24.04.iso  45% · 2.1 MB/s" or
 *   "debian.iso  Waiting", at most [MAX_LINES].
 * @property more number of files left out of [lines].
 * @property lanes for a single download with nothing waiting, the lengths of its connection
 *   ranges out of about [PROGRESS_MAX], merged into at most [MAX_LANES]; `null` otherwise.
 * @property filter status tab that lists these tasks.
 */
internal data class OngoingDownloads(
  val title: String,
  val text: String?,
  val permille: Int?,
  val lines: List<String>,
  val more: Int,
  val lanes: List<Int>?,
  val filter: StatusFilter,
) {
  /** Overall progress in percent, such as 45; `null` while [permille] is. */
  val percent: Int?
    get() = permille?.let { it / 10 }

  /** How far the progress reaches along [lanes], in the units of their lengths. */
  val lanePosition: Int
    get() = (permille ?: 0) * (lanes?.sum() ?: 0) / PROGRESS_MAX

  companion object {
    /** Units of [permille]. */
    const val PROGRESS_MAX: Int = 1000

    /** Most files [lines] lists. */
    const val MAX_LINES: Int = 5

    /** Most segments Android 16 draws; beyond that it falls back to a single bar. */
    const val MAX_LANES: Int = 10

    /** What an ongoing notification says about [tasks]; `null` when none downloads or waits. */
    fun of(tasks: List<DownloadTask>): OngoingDownloads? {
      val downloading = ArrayList<Pair<DownloadTask, DownloadProgress>>()
      val queued = ArrayList<DownloadTask>()
      for (task in tasks) {
        when (val state = task.state.value) {
          is DownloadState.Downloading -> downloading += task to state.progress
          is DownloadState.Queued -> queued += task
          else -> Unit
        }
      }
      if (downloading.isEmpty() && queued.isEmpty()) return null
      // Names are only worked out for the lines that show.
      val lines = buildList {
        for ((task, progress) in downloading.take(MAX_LINES)) {
          add("${nameOf(task)}  ${progressLine(progress)}")
        }
        for (task in queued.take(MAX_LINES - size)) add("${nameOf(task)}  Waiting")
      }
      val more = downloading.size + queued.size - lines.size
      if (downloading.isEmpty()) {
        return OngoingDownloads(
          title = "Waiting to download ${plural(queued.size, "file")}",
          text = null,
          permille = null,
          lines = lines,
          more = more,
          lanes = null,
          filter = StatusFilter.Waiting,
        )
      }
      val speed = downloading.sumOf { it.second.bytesPerSecond }
      val received = downloading.sumOf { it.second.downloadedBytes }
      val total = if (downloading.all { it.second.totalBytes > 0 }) {
        downloading.sumOf { it.second.totalBytes }
      } else {
        0L
      }
      val text = if (total > 0) {
        val left = timeLeft((total - received).coerceAtLeast(0), speed)
        listOfNotNull("${formatBytes(received)} of ${formatBytes(total)}", left)
          .joinToString(" · ")
      } else {
        formatBytes(received)
      }
      val single = downloading.singleOrNull()?.takeIf { queued.isEmpty() }
      return OngoingDownloads(
        title = "Downloading ${plural(downloading.size, "file")}" +
          if (speed > 0) " · ${formatBytes(speed)}/s" else "",
        text = text,
        permille = if (total > 0) permilleOf(received, total) else null,
        lines = lines,
        more = more,
        lanes = single?.let { (task, _) ->
          if (total > 0) laneLengths(task.segments.value) else listOf(PROGRESS_MAX)
        },
        filter = StatusFilter.Downloading,
      )
    }

    private fun nameOf(task: DownloadTask): String =
      displayName(task.requestState.value, task.state.value)

    // "45% · 2.1 MB/s"; the bytes so far while the size is unknown.
    private fun progressLine(progress: DownloadProgress): String = listOfNotNull(
      if (progress.totalBytes > 0) {
        "${permilleOf(progress.downloadedBytes, progress.totalBytes) / 10}%"
      } else {
        formatBytes(progress.downloadedBytes)
      },
      progress.bytesPerSecond.takeIf { it > 0 }?.let { "${formatBytes(it)}/s" }
    ).joinToString(" · ")

    private fun permilleOf(received: Long, total: Long): Int =
      (received.coerceIn(0, total) * PROGRESS_MAX / total).toInt()

    // One lane per connection range, sized by its share of the file. More ranges than Android
    // draws are merged with their neighbours.
    private fun laneLengths(segments: List<Segment>): List<Int> {
      val ranges = segments.sortedBy { it.start }.map { it.totalBytes }.filter { it > 0 }
      val bytes = ranges.sum()
      if (bytes <= 0) return listOf(PROGRESS_MAX)
      val perLane = (ranges.size + MAX_LANES - 1) / MAX_LANES
      return ranges.chunked(perLane).map { maxOf(1, (it.sum() * PROGRESS_MAX / bytes).toInt()) }
    }

    private fun timeLeft(bytes: Long, speed: Long): String? {
      if (speed <= 0) return null
      val seconds = bytes / speed
      return when {
        seconds < 60 -> "less than a minute left"
        seconds < 3600 -> "about ${(seconds + 59) / 60} min left"
        else -> "about ${formatEta(seconds)} left"
      }
    }
  }
}
