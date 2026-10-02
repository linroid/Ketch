package com.linroid.ketch.app.ui.feedback

import com.linroid.ketch.app.feedback.AppMessage
import com.linroid.ketch.app.feedback.MessageAction
import com.linroid.ketch.app.feedback.MessageLevel
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

class ActivityPopoverTest {

  private val now = Instant.parse("2026-10-01T14:30:00Z")
  private val utc = TimeZone.UTC

  private fun message(
    id: Long,
    at: Instant = now,
    level: MessageLevel = MessageLevel.Success,
    actions: List<MessageAction> = emptyList(),
  ) = AppMessage(id = id, level = level, title = "Message $id", actions = actions, at = at)

  @Test
  fun activityGroups_history_splitsTodayFromEarlierInOrder() {
    val history = listOf(
      message(3, now - 1.hours),
      message(2, now - 15.hours),
      message(1, now - 2.days)
    )

    val groups = activityGroups(history, now, utc)

    assertEquals(listOf("Today", "Earlier"), groups.map { it.first })
    assertEquals(listOf(3L), groups[0].second.map { it.id })
    assertEquals(listOf(2L, 1L), groups[1].second.map { it.id })
  }

  @Test
  fun activityGroups_onlyOlderEntries_hasNoTodayGroup() {
    val groups = activityGroups(listOf(message(1, now - 3.days)), now, utc)

    assertEquals(listOf("Earlier"), groups.map { it.first })
  }

  @Test
  fun activityTime_byDay_readsClockYesterdayOrDate() {
    assertEquals("14:02", activityTime(Instant.parse("2026-10-01T14:02:00Z"), now, utc))
    assertEquals(
      "Yesterday 18:20",
      activityTime(Instant.parse("2026-09-30T18:20:00Z"), now, utc)
    )
    assertEquals("Sep 28", activityTime(Instant.parse("2026-09-28T09:00:00Z"), now, utc))
  }

  @Test
  fun showsActions_undoAfterItsToast_isHidden() {
    val removed = message(1, actions = listOf(MessageAction("Undo") {}))

    assertTrue(showsActions(removed, onScreen = true))
    assertFalse(showsActions(removed, onScreen = false))
  }

  @Test
  fun showsActions_failure_keepsRetry() {
    val failed = message(1, level = MessageLevel.Error, actions = listOf(MessageAction("Retry") {}))

    assertTrue(showsActions(failed, onScreen = false))
    assertFalse(showsActions(message(2), onScreen = true))
  }
}
