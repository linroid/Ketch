package com.linroid.ketch.app.ui.discover

import androidx.compose.ui.unit.dp
import com.linroid.ketch.app.i18n.load
import com.linroid.ketch.app.state.AiCandidate
import com.linroid.ketch.app.state.DiscoverSession
import com.linroid.ketch.app.state.DiscoverTurn
import com.linroid.ketch.app.state.TurnStatus
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.TimeZone
import kotlinx.datetime.UtcOffset
import kotlinx.datetime.asTimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Instant

class DiscoverHistoryGroupsTest {

  // Half past midnight on 2 October in a zone eight hours ahead, still 1 October in UTC.
  private val now = Instant.parse("2026-10-01T16:30:00Z")
  private val east = UtcOffset(hours = 8).asTimeZone()

  private fun candidate(url: String) =
    AiCandidate(url = url, title = url, confidence = 0.9f, description = "")

  private fun turn(
    status: TurnStatus,
    vararg found: AiCandidate,
    message: String = "blender",
  ) = DiscoverTurn(
    id = "turn-$message-$status",
    message = message,
    sites = emptyList(),
    startedAt = now,
    status = status,
    candidates = found.toList(),
  )

  private fun session(
    id: String,
    updatedAt: Instant = now,
    turns: List<DiscoverTurn> = listOf(turn(TurnStatus.Done)),
    discarded: Set<String> = emptySet(),
  ) = DiscoverSession(
    id = id,
    title = turns.first().message,
    createdAt = updatedAt,
    updatedAt = updatedAt,
    turns = turns,
    discarded = discarded,
  )

  @Test
  fun historyPlacement_byPageWidth_docksFloatsOrOpensASheet() {
    assertEquals(HistoryPlacement.Docked, historyPlacement(phone = false, pageWidth = 780.dp))
    assertEquals(HistoryPlacement.Overlay, historyPlacement(phone = false, pageWidth = 779.dp))
    assertEquals(HistoryPlacement.Overlay, historyPlacement(phone = false, pageWidth = 600.dp))
    // A phone opens it as a sheet, however wide it is turned.
    assertEquals(HistoryPlacement.Sheet, historyPlacement(phone = true, pageWidth = 900.dp))
  }

  @Test
  fun historyGroups_acrossLocalMidnight_splitsByTheLocalDay() {
    val afterMidnight = session("a", Instant.parse("2026-10-01T16:10:00Z"))
    val beforeMidnight = session("b", Instant.parse("2026-10-01T15:50:00Z"))
    val twoDaysAgo = session("c", Instant.parse("2026-09-30T10:00:00Z"))
    val sessions = listOf(afterMidnight, beforeMidnight, twoDaysAgo)

    val local = historyGroups(sessions, now, east)
    assertEquals(
      listOf(
        HistoryDay.Today to listOf(afterMidnight),
        HistoryDay.Yesterday to listOf(beforeMidnight),
        HistoryDay.Earlier to listOf(twoDaysAgo),
      ),
      local,
    )
    // The same instants in UTC all fall on 1 October but the last, the day before.
    val utc = historyGroups(sessions, now, TimeZone.UTC)
    assertEquals(
      listOf(
        HistoryDay.Today to listOf(afterMidnight, beforeMidnight),
        HistoryDay.Yesterday to listOf(twoDaysAgo),
      ),
      utc,
    )
  }

  @Test
  fun historyDay_dateAfterToday_countsAsToday() {
    assertEquals(HistoryDay.Today, historyDay(Instant.parse("2026-10-03T08:00:00Z"), now, east))
  }

  @Test
  fun historyTime_todayAndYesterdayShowTheTimeEarlierTheDate() = runTest {
    val evening = Instant.parse("2026-10-01T15:50:00Z")
    assertEquals("23:50", historyTime(evening, HistoryDay.Yesterday, now, east).load())
    val earlier = Instant.parse("2026-09-28T03:00:00Z")
    assertEquals("Sep 28", historyTime(earlier, HistoryDay.Earlier, now, east).load())
  }

  @Test
  fun matchesHistory_titleOrAnyMessage_ignoringCaseAndSpaces() {
    val chat = session(
      id = "a",
      turns = listOf(
        turn(TurnStatus.Done, message = "Blender 4.2"),
        turn(TurnStatus.Done, message = "only the LTS release"),
      ),
    )

    assertTrue(matchesHistory(chat, "  blender "))
    assertTrue(matchesHistory(chat, "LTS"))
    assertTrue(matchesHistory(chat, " "))
    assertFalse(matchesHistory(chat, "ubuntu"))
  }

  @Test
  fun historyStatus_waitingComesFirstThenWhatRunsThenTheNewestTurn() {
    val running = session("a", turns = listOf(turn(TurnStatus.Done), turn(TurnStatus.Running)))
    val queued = session("b", turns = listOf(turn(TurnStatus.Queued)))
    val failed = session("c", turns = listOf(turn(TurnStatus.Done), turn(TurnStatus.Failed)))
    val stopped = session("d", turns = listOf(turn(TurnStatus.Stopped)))

    assertEquals(HistoryStatus.NeedsOk, historyStatus(running, waiting = true))
    assertEquals(HistoryStatus.Searching, historyStatus(running, waiting = false))
    assertEquals(HistoryStatus.Queued, historyStatus(queued, waiting = false))
    assertEquals(HistoryStatus.Failed, historyStatus(failed, waiting = false))
    assertEquals(HistoryStatus.Stopped, historyStatus(stopped, waiting = false))
  }

  @Test
  fun historyStatus_done_countsEachKeptLinkOnceAcrossTurns() {
    val dmg = candidate("https://download.blender.org/a.dmg")
    val zip = candidate("https://download.blender.org/a.zip")
    val iso = candidate("https://download.blender.org/a.iso")
    val chat = session(
      id = "a",
      turns = listOf(turn(TurnStatus.Done, dmg, zip, iso), turn(TurnStatus.Done, dmg)),
      discarded = setOf(iso.url),
    )

    assertEquals(HistoryStatus.Results(2), historyStatus(chat, waiting = false))
    assertEquals(
      HistoryStatus.Results(0),
      historyStatus(session("b", turns = listOf(turn(TurnStatus.Done))), waiting = false),
    )
  }
}
