package com.linroid.ketch.app.feedback

import com.linroid.ketch.api.DownloadProgress
import com.linroid.ketch.api.DownloadState
import com.linroid.ketch.api.DownloadTask
import com.linroid.ketch.api.KetchFeatures
import com.linroid.ketch.api.Segment
import com.linroid.ketch.app.i18n.UiText
import com.linroid.ketch.app.i18n.etaText
import com.linroid.ketch.app.i18n.joinText
import com.linroid.ketch.app.i18n.percentText
import com.linroid.ketch.app.i18n.sizeText
import com.linroid.ketch.app.i18n.speedText
import com.linroid.ketch.app.i18n.text
import com.linroid.ketch.app.i18n.verbatim
import com.linroid.ketch.app.state.StatusFilter
import com.linroid.ketch.app.state.isStarting
import com.linroid.ketch.app.state.waitsInQueue
import com.linroid.ketch.app.util.displayName
import com.linroid.ketch.app.util.startingReason
import ketch.app.shared.generated.resources.Res
import ketch.app.shared.generated.resources.ongoing_downloading
import ketch.app.shared.generated.resources.ongoing_left
import ketch.app.shared.generated.resources.ongoing_left_minutes
import ketch.app.shared.generated.resources.ongoing_left_under_minute
import ketch.app.shared.generated.resources.ongoing_line_waiting
import ketch.app.shared.generated.resources.ongoing_size_of
import ketch.app.shared.generated.resources.ongoing_waiting
import ketch.app.shared.generated.resources.row_status_starting

/**
 * What an ongoing notification says about a device's downloads, such as the one the Android
 * download service shows: the tasks downloading or waiting for a slot, in list order. Notifiers
 * read its text with `load()` as they post. A task that [isStarting] holds a slot, so it counts
 * as downloading, though it has no progress yet.
 *
 * @property title "Downloading 3 files · 4.2 MB/s", or "Waiting to download 2 files" while every
 *   task waits.
 * @property text "1.2 GB of 3.4 GB · about 6 min left", only the bytes so far while a size is
 *   unknown, or `null` while no task has progress.
 * @property permille overall progress of the tasks that have some, out of [PROGRESS_MAX]; `null`
 *   while a size is unknown or no task has progress.
 * @property lines a line per file, such as "ubuntu-24.04.iso  45% · 2.1 MB/s",
 *   "ubuntu.torrent  Finding peers" or "debian.iso  Waiting", at most [MAX_LINES].
 * @property more number of files left out of [lines].
 * @property lanes for a single download with nothing starting or waiting, the lengths of its
 *   connection ranges out of about [PROGRESS_MAX], merged into at most [MAX_LANES]; `null`
 *   otherwise.
 * @property filter status tab that lists these tasks.
 */
internal data class OngoingDownloads(
  val title: UiText,
  val text: UiText?,
  val permille: Int?,
  val lines: List<UiText>,
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

    /**
     * What an ongoing notification says about [tasks], those of a device that reports
     * [features]; `null` when none downloads, starts or waits.
     */
    fun of(
      tasks: List<DownloadTask>,
      features: Set<String> = KetchFeatures.ALL,
    ): OngoingDownloads? {
      val downloading = ArrayList<Pair<DownloadTask, DownloadProgress>>()
      val starting = ArrayList<DownloadTask>()
      val queued = ArrayList<DownloadTask>()
      for (task in tasks) {
        val state = task.state.value
        when {
          state is DownloadState.Downloading -> downloading += task to state.progress
          state.isStarting(task.queuePosition.value, features) -> starting += task
          state.waitsInQueue -> queued += task
        }
      }
      val active = downloading.size + starting.size
      if (active == 0 && queued.isEmpty()) return null
      // Names are only worked out for the lines that show.
      val lines = buildList {
        for ((task, progress) in downloading.take(MAX_LINES)) {
          add(line(nameOf(task), progressLine(progress)))
        }
        for (task in starting.take(MAX_LINES - size)) {
          val reason = startingReason(task.requestState.value)
          add(line(nameOf(task), reason ?: Res.string.row_status_starting.text()))
        }
        for (task in queued.take(MAX_LINES - size)) {
          add(line(nameOf(task), Res.string.ongoing_line_waiting.text()))
        }
      }
      val more = active + queued.size - lines.size
      if (active == 0) {
        return OngoingDownloads(
          title = Res.plurals.ongoing_waiting.text(queued.size),
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
      val text = when {
        downloading.isEmpty() -> null
        total > 0 -> {
          val left = timeLeft((total - received).coerceAtLeast(0), speed)
          listOfNotNull(Res.string.ongoing_size_of.text(sizeText(received), sizeText(total)), left)
            .joinText()
        }
        else -> sizeText(received)
      }
      val single = downloading.singleOrNull()?.takeIf { starting.isEmpty() && queued.isEmpty() }
      return OngoingDownloads(
        title = listOfNotNull(
          Res.plurals.ongoing_downloading.text(active),
          speedText(speed).takeIf { speed > 0 },
        ).joinText(),
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

    // "ubuntu.iso  45% · 2.1 MB/s": the name, then what goes on.
    private fun line(name: String, state: UiText): UiText =
      listOf(verbatim(name), state).joinText(LINE_SEPARATOR)

    // "45% · 2.1 MB/s"; the bytes so far while the size is unknown.
    private fun progressLine(progress: DownloadProgress): UiText = listOfNotNull(
      if (progress.totalBytes > 0) {
        percentText(permilleOf(progress.downloadedBytes, progress.totalBytes) / 10)
      } else {
        sizeText(progress.downloadedBytes)
      },
      progress.bytesPerSecond.takeIf { it > 0 }?.let(::speedText)
    ).joinText()

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

    private fun timeLeft(bytes: Long, speed: Long): UiText? {
      if (speed <= 0) return null
      val seconds = bytes / speed
      return when {
        seconds < 60 -> Res.string.ongoing_left_under_minute.text()
        seconds < 3600 -> Res.plurals.ongoing_left_minutes.text(((seconds + 59) / 60).toInt())
        else -> Res.string.ongoing_left.text(etaText(seconds))
      }
    }

    // Between a file's name and what it does, as notifications line them up.
    private const val LINE_SEPARATOR = "  "
  }
}
