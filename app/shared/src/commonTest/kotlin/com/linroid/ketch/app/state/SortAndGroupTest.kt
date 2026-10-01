package com.linroid.ketch.app.state

import com.linroid.ketch.api.DownloadPriority
import com.linroid.ketch.api.DownloadProgress
import com.linroid.ketch.api.DownloadRequest
import com.linroid.ketch.api.DownloadSchedule
import com.linroid.ketch.api.DownloadState
import com.linroid.ketch.api.KetchError
import com.linroid.ketch.app.state.ListFixtures.START
import com.linroid.ketch.app.state.ListFixtures.downloading
import com.linroid.ketch.app.state.ListFixtures.row
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

class SortAndGroupTest {
  private val utc = TimeZone.UTC

  @Test
  fun arrangeRows_smart_ordersGroupsByWhatNeedsTheUser() {
    val rows = listOf(
      row("old", completed(), createdAt = START - 30.days),
      row("week", completed(), createdAt = START - 3.days),
      row("yesterday", completed(), createdAt = START - 1.days),
      row("today", completed()),
      row("failed", DownloadState.Failed(KetchError.Network())),
      row("canceled", DownloadState.Canceled),
      row("paused", DownloadState.Paused(DownloadProgress(5, 10))),
      row("queued", DownloadState.Queued),
      row("running", downloading(10)),
    )

    val groups = arrangeRows(rows, ListArrangement(), START, utc)

    assertEquals(
      listOf(
        "Downloading", "Waiting", "Paused", "Needs attention", "Added today", "Added yesterday",
        "Added this week", "Added earlier",
      ),
      groups.map { it.title }
    )
    assertEquals(setOf("failed", "canceled"), groups[3].ids().toSet())
  }

  @Test
  fun arrangeRows_smartDownloading_ordersByPriorityThenProgress() {
    val rows = listOf(
      row("slow", downloading(100, total = 1000, speed = 100)),
      row("far", downloading(900, total = 1000, speed = 300)),
      row("urgent", downloading(10, total = 1000, speed = 50), request = urgent("urgent")),
    )

    val group = arrangeRows(rows, ListArrangement(), START, utc).single()

    assertEquals(listOf("urgent", "far", "slow"), group.rows.map { it.key.taskId })
    // 450 B/s in total; the urgent task takes the longest: 990 B at 50 B/s, 19 s.
    assertEquals(listOf("3", "450 B/s", "all done ≈ 12:00"), group.details)
  }

  @Test
  fun arrangeRows_downloadingWithUnknownTimeLeft_omitsAllDone() {
    val rows = listOf(row("a", downloading(10, speed = 0)), row("b", downloading(10)))

    val details = arrangeRows(rows, ListArrangement(), START, utc).single().details

    assertEquals(listOf("2", "100 B/s"), details)
  }

  @Test
  fun arrangeRows_smartWaiting_queuedByPriorityAndAgeThenScheduledByStart() {
    val rows = listOf(
      row("later", scheduled(START + 3.hours)),
      row("queued-new", DownloadState.Queued, createdAt = START),
      row("soon", scheduled(START + 1.hours)),
      row("queued-old", DownloadState.Queued, createdAt = START - 1.hours),
      row("high", DownloadState.Queued, request = urgent("high", DownloadPriority.HIGH)),
      row("delayed", DownloadState.Scheduled(DownloadSchedule.AfterDelay(30.minutes))),
    )

    val group = arrangeRows(rows, ListArrangement(), START, utc).single()

    assertEquals(
      listOf("high", "queued-old", "queued-new", "delayed", "soon", "later"),
      group.rows.map { it.key.taskId }
    )
    assertEquals(listOf("6", "in start order"), group.details)
  }

  @Test
  fun arrangeRows_dayGroups_showCountAndSize() {
    val rows = listOf(row("a", completed(1024)), row("b", completed(2048)))

    val details = arrangeRows(rows, ListArrangement(), START, utc).single().details

    assertEquals(listOf("2", "3.0 KB"), details)
  }

  @Test
  fun arrangeRows_manyEarlierRows_collapsedByDefault() {
    val old = (1..51).map { row("old$it", completed(), createdAt = START - 30.days) }
    val few = (1..50).map { row("few$it", completed(), createdAt = START - 30.days) }

    assertTrue(arrangeRows(old, ListArrangement(), START, utc).single().collapsedByDefault)
    assertFalse(arrangeRows(few, ListArrangement(), START, utc).single().collapsedByDefault)
  }

  @Test
  fun arrangeRows_columnSort_keepsRowsWithoutValueLastBothWays() {
    val rows = listOf(
      row("done", completed()),
      row("fast", downloading(10, speed = 900)),
      row("slow", downloading(10, speed = 100)),
    )
    val bySpeed = ListArrangement(SortKey.Speed, descending = true, group = GroupBy.None)

    val descending = arrangeRows(rows, bySpeed, START, utc).single().rows
    val ascending = arrangeRows(rows, bySpeed.copy(descending = false), START, utc).single().rows

    assertEquals(listOf("fast", "slow", "done"), descending.map { it.key.taskId })
    assertEquals(listOf("slow", "fast", "done"), ascending.map { it.key.taskId })
  }

  @Test
  fun arrangeRows_speedSort_usesThreeSampleAverage() {
    val rows = listOf(
      row("spike", downloading(10, speed = 900), speedSamples = listOf(900, 0, 0, 900)),
      row("steady", downloading(10, speed = 400), speedSamples = listOf(400, 400, 400)),
    )
    val bySpeed = ListArrangement(SortKey.Speed, descending = true, group = GroupBy.None)

    val sorted = arrangeRows(rows, bySpeed, START, utc).single().rows

    assertEquals(listOf("steady", "spike"), sorted.map { it.key.taskId })
  }

  @Test
  fun arrangeRows_sortByName_insideEachGroup() {
    val rows = listOf(
      row("b", downloading(10), request = named("beta.iso")),
      row("a", downloading(10), request = named("Alpha.iso")),
      row("c", DownloadState.Queued, request = named("aardvark.iso")),
    )
    val byName = ListArrangement(SortKey.Name, descending = false, group = GroupBy.Smart)

    val groups = arrangeRows(rows, byName, START, utc)

    assertEquals(listOf("a", "b"), groups[0].ids())
    assertEquals(listOf("c"), groups[1].ids())
    assertEquals(listOf("1"), groups[1].details)
  }

  @Test
  fun arrangeRows_groupBySite_putsHostlessLinksLast() {
    val rows = listOf(
      row("magnet", DownloadState.Queued, request = DownloadRequest("magnet:?xt=urn:btih:abc")),
      row("z", DownloadState.Queued, request = DownloadRequest("https://zeta.org/z.iso")),
      row("a", DownloadState.Queued, request = DownloadRequest("https://alpha.org/a.iso")),
    )

    val groups = arrangeRows(rows, ListArrangement(group = GroupBy.Site), START, utc)

    assertEquals(listOf("alpha.org", "zeta.org", "Other"), groups.map { it.title })
  }

  @Test
  fun arrangeRows_noGrouping_returnsOneUntitledGroup() {
    val rows = listOf(row("a", DownloadState.Queued), row("b", completed()))

    val groups = arrangeRows(rows, ListArrangement(group = GroupBy.None), START, utc)

    assertEquals(listOf(""), groups.map { it.title })
    assertEquals(listOf("a", "b"), groups.single().rows.map { it.key.taskId })
  }

  @Test
  fun sortedBy_sameColumn_reversesAndNewColumnUsesItsDirection() {
    val bySize = ListArrangement().sortedBy(SortKey.Size)

    assertEquals(ListArrangement(SortKey.Size, descending = true), bySize)
    assertEquals(ListArrangement(SortKey.Size, descending = false), bySize.sortedBy(SortKey.Size))
    assertEquals(ListArrangement(SortKey.Name, descending = false), bySize.sortedBy(SortKey.Name))
  }

  @Test
  fun decode_encodedArrangement_roundTripsAndFallsBackPerPart() {
    val arrangement = ListArrangement(SortKey.TimeLeft, descending = true, group = GroupBy.Site)

    assertEquals("left:desc:site", arrangement.encode())
    assertEquals(arrangement, ListArrangement.decode(arrangement.encode(), StatusFilter.All))
    assertEquals(
      ListArrangement(SortKey.Speed, descending = true, group = GroupBy.None),
      ListArrangement.decode("speed:sideways:mystery", StatusFilter.Done)
    )
    assertEquals(ListArrangement(), ListArrangement.decode(null, StatusFilter.All))
  }

  @Test
  fun arrange_withinInterval_keepsOrderThenResorts() {
    val stable = StableArrangement()
    val bySpeed = ListArrangement(SortKey.Speed, descending = true, group = GroupBy.None)
    stable.arrange(listOf(speedRow("a", 900), speedRow("b", 100)), bySpeed, START, utc)
    val swapped = listOf(speedRow("a", 100), speedRow("b", 900))

    val held = stable.arrange(swapped, bySpeed, START + 1.seconds, utc)
    val resorted = stable.arrange(swapped, bySpeed, START + 2.seconds, utc)

    assertEquals(listOf("a", "b"), held.ids())
    assertEquals(listOf("b", "a"), resorted.ids())
    assertEquals(900, held.single().rows[1].speed)
  }

  @Test
  fun arrange_frozen_keepsOrderUntilReleased() {
    val stable = StableArrangement()
    val bySpeed = ListArrangement(SortKey.Speed, descending = true, group = GroupBy.None)
    stable.arrange(listOf(speedRow("a", 900), speedRow("b", 100)), bySpeed, START, utc)
    val swapped = listOf(speedRow("a", 100), speedRow("b", 900))

    val frozen = stable.arrange(swapped, bySpeed, START + 10.seconds, utc, frozen = true)
    val released = stable.arrange(swapped, bySpeed, START + 11.seconds, utc, frozen = false)

    assertEquals(listOf("a", "b"), frozen.ids())
    assertEquals(listOf("b", "a"), released.ids())
  }

  @Test
  fun arrange_viewChanges_resortsAtOnceEvenWhenFrozen() {
    val stable = StableArrangement()
    val rows = listOf(speedRow("a", 900), speedRow("b", 100))
    val bySpeed = ListArrangement(SortKey.Speed, descending = true, group = GroupBy.None)
    stable.arrange(rows, bySpeed, START, utc, frozen = true)

    val reversed = bySpeed.sortedBy(SortKey.Speed)
    val arranged = stable.arrange(rows, reversed, START + 1.seconds, utc, frozen = true)

    assertEquals(listOf("b", "a"), arranged.ids())
  }

  @Test
  fun arrange_whileHeld_dropsGoneRowsAndPlacesNewOnes() {
    val stable = StableArrangement()
    val bySpeed = ListArrangement(SortKey.Speed, descending = true, group = GroupBy.None)
    stable.arrange(
      listOf(speedRow("a", 900), speedRow("b", 500), speedRow("c", 100)),
      bySpeed,
      START,
      utc
    )
    // c overtakes a, b is gone, and d arrives after both of them.
    val rows = listOf(speedRow("a", 900), speedRow("c", 950), speedRow("d", 300))

    val held = stable.arrange(rows, bySpeed, START + 1.seconds, utc, frozen = true)

    assertEquals(listOf("a", "c", "d"), held.ids())
  }

  @Test
  fun arrange_whileHeld_finishedRowStaysInItsGroup() {
    val stable = StableArrangement()
    val running = listOf(row("a", downloading(10)), row("b", completed()))
    stable.arrange(running, ListArrangement(), START, utc)
    val finished = listOf(row("a", completed()), row("b", completed()))

    val held = stable.arrange(finished, ListArrangement(), START + 1.seconds, utc)
    val resorted = stable.arrange(finished, ListArrangement(), START + 2.seconds, utc)

    assertEquals(listOf("Downloading", "Added today"), held.map { it.title })
    assertEquals(listOf("1", "0 B/s"), held[0].details)
    assertEquals(listOf("Added today"), resorted.map { it.title })
  }

  private fun List<RowGroup>.ids(): List<String> = flatMap { it.ids() }

  private fun RowGroup.ids(): List<String> = rows.map { it.key.taskId }

  private fun speedRow(id: String, speed: Long) = row(id, downloading(10, speed = speed))

  private fun completed(size: Long = 100) = DownloadState.Completed("/downloads/file", size)

  private fun scheduled(at: Instant) = DownloadState.Scheduled(DownloadSchedule.AtTime(at))

  private fun urgent(id: String, priority: DownloadPriority = DownloadPriority.URGENT) =
    DownloadRequest("https://example.com/$id.bin", priority = priority)

  private fun named(name: String) = DownloadRequest("https://example.com/$name")
}
