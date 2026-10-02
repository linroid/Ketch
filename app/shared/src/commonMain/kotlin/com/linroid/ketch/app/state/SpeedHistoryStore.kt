package com.linroid.ketch.app.state

import com.linroid.ketch.api.DownloadState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * Speed of one task once a second, oldest first.
 *
 * Samples are [SpeedHistoryStore.INTERVAL] apart: seconds the task spent paused or waiting read
 * as `0`, so the newest sample was taken at [lastAt] and every earlier one a second before the
 * next.
 *
 * @property lastAt when the newest sample was taken.
 */
class SpeedHistory internal constructor(
  private val samples: LongArray,
  val lastAt: Instant,
) {
  /** Number of samples, at most [SpeedHistoryStore.CAPACITY]. */
  val size: Int
    get() = samples.size

  /** Highest speed in bytes per second, `0` when empty. */
  val peak: Long
    get() = samples.maxOrNull() ?: 0

  /** Mean speed in bytes per second over every sample, `0` when empty. */
  val average: Long
    get() = if (samples.isEmpty()) 0 else samples.sum() / samples.size

  /** Speed in bytes per second at [index], `0` being the oldest sample. */
  operator fun get(index: Int): Long = samples[index]

  /** When the sample at [index] was taken. */
  fun timeAt(index: Int): Instant = lastAt - SpeedHistoryStore.INTERVAL * (size - 1 - index)

  /** The samples as a list, oldest first. */
  fun toList(): List<Long> = samples.toList()

  /**
   * This history with [speed] sampled at [now]. Whole seconds missed since [lastAt] are filled
   * with `0`; a sample less than half a second after the last one replaces it.
   */
  internal fun plus(speed: Long, now: Instant): SpeedHistory {
    val elapsed = ((now - lastAt) / SpeedHistoryStore.INTERVAL).roundToInt()
    if (elapsed <= 0) {
      val replaced = samples.copyOf()
      replaced[replaced.lastIndex] = speed
      return SpeedHistory(replaced, lastAt)
    }
    val gap = (elapsed - 1).coerceAtMost(SpeedHistoryStore.CAPACITY)
    val joined = samples + LongArray(gap) + speed
    val start = maxOf(0, joined.size - SpeedHistoryStore.CAPACITY)
    return SpeedHistory(joined.copyOfRange(start, joined.size), now)
  }

  internal companion object {
    fun of(speed: Long, now: Instant): SpeedHistory = SpeedHistory(longArrayOf(speed), now)
  }
}

/**
 * Speed history of every downloading task, sampled once a second from task rows and kept for
 * the last [CAPACITY] seconds, so the inspector's Activity chart is full the moment it opens.
 *
 * A history starts when its task first downloads, stays while it is paused or finished, and is
 * dropped with the task.
 *
 * @param rows rows of every task, such as [TaskListModel.rows].
 * @param scope samples until it is cancelled.
 * @param clock time of each sample.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SpeedHistoryStore(
  rows: StateFlow<List<TaskRow>>,
  scope: CoroutineScope,
  private val clock: Clock = Clock.System,
) {
  private val state = MutableStateFlow<Map<TaskKey, SpeedHistory>>(emptyMap())

  /** History of each task that has downloaded. */
  val histories: StateFlow<Map<TaskKey, SpeedHistory>> = state.asStateFlow()

  init {
    scope.launch {
      rows.map { list -> list.any { it.state is DownloadState.Downloading } }
        .distinctUntilChanged()
        .collectLatest { active ->
          if (!active) return@collectLatest
          while (true) {
            record(clock.now(), speedsOf(rows.value))
            delay(INTERVAL)
          }
        }
    }
    scope.launch {
      rows.map { list -> list.mapTo(HashSet()) { it.key } }
        .distinctUntilChanged()
        .collect { keys -> state.update { current -> current.filterKeys(keys::contains) } }
    }
  }

  /** History of the task [key], or `null` if it has not downloaded. */
  fun history(key: TaskKey): SpeedHistory? = state.value[key]

  /**
   * Takes one sample at [now]. [speeds] has every listed task: the speed of a downloading one,
   * `null` for the others. Tasks missing from it are forgotten.
   */
  internal fun record(now: Instant, speeds: Map<TaskKey, Long?>) {
    state.update { current ->
      buildMap {
        for ((key, speed) in speeds) {
          val history = current[key]
          when {
            speed != null -> put(key, history?.plus(speed, now) ?: SpeedHistory.of(speed, now))
            history != null -> put(key, history)
          }
        }
      }
    }
  }

  private fun speedsOf(rows: List<TaskRow>): Map<TaskKey, Long?> = rows.associate { row ->
    row.key to (row.state as? DownloadState.Downloading)?.progress?.bytesPerSecond
  }

  companion object {
    /** Time between two samples. */
    val INTERVAL: Duration = 1.seconds

    /** Samples kept per task: five minutes. */
    const val CAPACITY: Int = 300
  }
}
