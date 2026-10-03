package com.linroid.ketch.app.ui.inspector.tabs

import com.linroid.ketch.api.SpeedLimit
import com.linroid.ketch.app.i18n.load
import com.linroid.ketch.app.i18n.verbatim
import com.linroid.ketch.app.state.ListFixtures.START
import com.linroid.ketch.app.state.SpeedHistory
import com.linroid.ketch.app.state.TimelineKind
import com.linroid.ketch.app.theme.lightKetchColors
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds

class ActivityTabTest {
  private val colors = lightKetchColors()

  @Test
  fun activityBands_twoConnections_oneBandEachInLaneColors() {
    val history = SpeedHistory.of(300, START, mapOf(0L to 200L, 500L to 100L))
      .plus(200, START + 1.seconds, mapOf(0L to 150L, 500L to 50L))

    val bands = activityBands(history, colors)

    assertEquals(listOf(listOf(200L, 150L), listOf(100L, 50L)), bands.map { it.samples })
    assertEquals(listOf(colors.lanes[0], colors.lanes[1]), bands.map { it.color })
  }

  @Test
  fun activityBands_secondsWithoutConnections_showTheTaskSpeed() {
    val history = SpeedHistory.of(300, START)
      .plus(200, START + 1.seconds, mapOf(0L to 150L, 500L to 50L))

    val band = activityBands(history, colors).single()

    assertEquals(listOf(300L, 200L), band.samples)
    assertEquals(colors.accent, band.color)
  }

  @Test
  fun activityBands_singleConnection_isTheTaskSpeedInAccent() {
    val history = SpeedHistory.of(300, START, mapOf(0L to 300L))

    val band = activityBands(history, colors).single()

    assertEquals(listOf(300L), band.samples)
    assertEquals(colors.accent, band.color)
  }

  @Test
  fun activityLimits_taskAndGlobal_drawsEachOnce() = runTest {
    suspend fun lines(task: SpeedLimit, global: SpeedLimit, name: String) =
      activityLimits(task, global, verbatim(name)).map { it.bytesPerSecond to it.label.load() }

    assertEquals(
      listOf(
        SpeedLimit.mbps(1).bytesPerSecond to "Slow lane 1 MB/s",
        SpeedLimit.mbps(5).bytesPerSecond to "Task 5 MB/s"
      ),
      lines(SpeedLimit.mbps(5), SpeedLimit.mbps(1), "Slow lane")
    )
    assertEquals(
      listOf(SpeedLimit.mbps(2).bytesPerSecond to "Task 2 MB/s"),
      lines(SpeedLimit.mbps(2), SpeedLimit.mbps(2), "Global")
    )
    assertEquals(
      listOf(
        SpeedLimit.mbps(4).bytesPerSecond to "Global 4 MB/s",
        SpeedLimit.mbps(5).bytesPerSecond to null
      ),
      lines(SpeedLimit.mbps(5), SpeedLimit.mbps(4), "Global")
    )
    assertEquals(emptyList(), lines(SpeedLimit.Unlimited, SpeedLimit.Unlimited, "Global"))
  }

  @Test
  fun activityStats_peakAverageAndConnections() = runTest {
    val history = SpeedHistory.of(1_048_576, START).plus(3_145_728, START + 1.seconds)

    assertEquals(
      listOf("Peak" to "3.0 MB/s", "Average" to "2.0 MB/s", "Connections" to "8"),
      activityStats(history, 8).map { (name, value) -> name.load() to value.load() }
    )
    assertEquals(
      listOf("Peak", "Average"),
      activityStats(history, null).map { it.first }.load(),
    )
  }

  @Test
  fun timelineColor_eachKind_matchesItsStatus() {
    assertEquals(colors.status.paused.color, colors.timelineColor(TimelineKind.Paused))
    assertEquals(colors.status.failed.color, colors.timelineColor(TimelineKind.Failed))
    assertEquals(colors.status.completed.color, colors.timelineColor(TimelineKind.Completed))
    assertEquals(colors.accent, colors.timelineColor(TimelineKind.Resumed))
    assertEquals(colors.textTertiary, colors.timelineColor(TimelineKind.Changed))
  }
}
