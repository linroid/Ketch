package com.linroid.ketch.app.ui.discover

import com.linroid.ketch.app.i18n.UiText
import com.linroid.ketch.app.i18n.clockTime
import com.linroid.ketch.app.i18n.shortDateText
import com.linroid.ketch.app.i18n.text
import com.linroid.ketch.app.i18n.verbatim
import com.linroid.ketch.app.state.DiscoverSession
import com.linroid.ketch.app.state.TurnStatus
import com.linroid.ketch.config.SiteNames
import ketch.app.shared.generated.resources.Res
import ketch.app.shared.generated.resources.date_today
import ketch.app.shared.generated.resources.date_yesterday
import ketch.app.shared.generated.resources.discover_history_earlier
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.TimeZone
import kotlinx.datetime.minus
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

/** A group of Discover's history, by the local day its searches last ran. */
internal enum class HistoryDay(val title: UiText) {
  Today(Res.string.date_today.text()),
  Yesterday(Res.string.date_yesterday.text()),
  Earlier(Res.string.discover_history_earlier.text()),
}

/**
 * [sessions], kept in their order, split into Today, Yesterday and Earlier by the local day of
 * [timeZone] each one last ran ([DiscoverSession.updatedAt]) compared with [now]'s. A session
 * dated after today, from a clock set back, counts as today. Empty groups are left out.
 */
internal fun historyGroups(
  sessions: List<DiscoverSession>,
  now: Instant,
  timeZone: TimeZone,
): List<Pair<HistoryDay, List<DiscoverSession>>> {
  val grouped = sessions.groupBy { historyDay(it.updatedAt, now, timeZone) }
  return HistoryDay.entries.mapNotNull { day -> grouped[day]?.let { day to it } }
}

/** The group of a session that last ran [at]; see [historyGroups]. */
internal fun historyDay(at: Instant, now: Instant, timeZone: TimeZone): HistoryDay {
  val today = now.toLocalDateTime(timeZone).date
  val date = at.toLocalDateTime(timeZone).date
  return when {
    date >= today -> HistoryDay.Today
    date == today.minus(1, DateTimeUnit.DAY) -> HistoryDay.Yesterday
    else -> HistoryDay.Earlier
  }
}

/**
 * When a session of the [day] group last ran: the time today and yesterday, whose group names
 * the day, else the date, such as "Sep 28".
 */
internal fun historyTime(at: Instant, day: HistoryDay, now: Instant, timeZone: TimeZone): UiText {
  val time = at.toLocalDateTime(timeZone)
  return when (day) {
    HistoryDay.Today, HistoryDay.Yesterday -> verbatim(clockTime(time))
    HistoryDay.Earlier -> shortDateText(time.date, now.toLocalDateTime(timeZone).date)
  }
}

/**
 * Whether [session] matches what is typed in the history's filter: its title or any of its
 * messages contains [query], ignoring case and the spaces around it. A blank query matches all.
 */
internal fun matchesHistory(session: DiscoverSession, query: String): Boolean {
  val wanted = query.trim()
  if (wanted.isEmpty()) return true
  return session.title.contains(wanted, ignoreCase = true) ||
    session.turns.any { it.message.contains(wanted, ignoreCase = true) }
}

/** What a history row says about its session under the title. */
internal sealed interface HistoryStatus {
  /** A turn waits for the user to allow a website. */
  data object NeedsOk : HistoryStatus

  /** A turn is searching. */
  data object Searching : HistoryStatus

  /** A turn waits for other searches to finish before it starts. */
  data object Queued : HistoryStatus

  /** The newest turn failed. */
  data object Failed : HistoryStatus

  /** The newest turn was stopped. */
  data object Stopped : HistoryStatus

  /** The session is done; it shows [count] results, each link once. */
  data class Results(val count: Int) : HistoryStatus
}

/**
 * What [session] is doing, most pressing first: waiting for the user's OK when [waiting], then
 * searching or waiting to start, then how its newest turn ended, else how many results it
 * shows across its turns, without the discarded ones and each link once.
 */
internal fun historyStatus(session: DiscoverSession, waiting: Boolean): HistoryStatus {
  if (waiting) return HistoryStatus.NeedsOk
  val statuses = session.turns.map { it.status }
  return when {
    TurnStatus.Running in statuses -> HistoryStatus.Searching
    TurnStatus.Queued in statuses -> HistoryStatus.Queued
    statuses.lastOrNull() == TurnStatus.Failed -> HistoryStatus.Failed
    statuses.lastOrNull() == TurnStatus.Stopped -> HistoryStatus.Stopped
    else -> HistoryStatus.Results(
      session.turns.flatMap(session::visible).distinctBy { SiteNames.canonicalUrl(it.url) }.size,
    )
  }
}
