package com.linroid.ketch.app.ui.discover

import androidx.compose.runtime.Immutable
import com.linroid.ketch.app.state.AiCandidate
import com.linroid.ketch.app.state.DiscoverSession
import com.linroid.ketch.app.state.DiscoverTurn
import com.linroid.ketch.app.state.PageApproval
import com.linroid.ketch.app.state.TurnStatus
import com.linroid.ketch.config.SiteNames

/**
 * One item of a Discover chat's thread, the unit its list scrolls by. Every key starts with the
 * id of its [turn], so a link the agent finds again in a follow-up never clashes with itself.
 * Items compare by value, so an unchanged thread is the same list, and an item whose turn did
 * not change skips recomposing while the newest turn reports its steps.
 */
@Immutable
internal sealed interface ThreadItem {
  /** The turn the item belongs to. */
  val turn: DiscoverTurn

  /** Key of the item in the thread's list, unique across the session. */
  val key: String

  /** The user's message, right-aligned, with the websites it was limited to. */
  data class Message(override val turn: DiscoverTurn, val first: Boolean) : ThreadItem {
    override val key: String = "${turn.id}/message"
  }

  /** The steps the agent reported, the newest spinning while the turn runs. */
  data class Steps(override val turn: DiscoverTurn) : ThreadItem {
    override val key: String = "${turn.id}/steps"
  }

  /** The turn waits for other searches to finish before it starts. */
  data class Queued(override val turn: DiscoverTurn) : ThreadItem {
    override val key: String = "${turn.id}/queued"
  }

  /** How the user answered the turn's requests to open websites. */
  data class Access(override val turn: DiscoverTurn) : ThreadItem {
    override val key: String = "${turn.id}/access"
  }

  /** A request to open a website that waits for the user's answer. */
  data class Approval(override val turn: DiscoverTurn, val approval: PageApproval) : ThreadItem {
    override val key: String = approvalKey(turn.id, approval.id)
  }

  /** The shape of a result row while the turn searches. */
  data class Skeleton(override val turn: DiscoverTurn, val index: Int) : ThreadItem {
    override val key: String = "${turn.id}/skeleton/$index"
  }

  /** The agent's short reply. */
  data class Summary(override val turn: DiscoverTurn) : ThreadItem {
    override val key: String = "${turn.id}/summary"
  }

  /** "4 downloads" above the results, which selects or discards them all. */
  data class Results(
    override val turn: DiscoverTurn,
    val candidates: List<AiCandidate>,
  ) : ThreadItem {
    override val key: String = "${turn.id}/results"
  }

  /** One download the turn found. */
  data class Result(override val turn: DiscoverTurn, val candidate: AiCandidate) : ThreadItem {
    override val key: String = "${turn.id}/result/${SiteNames.canonicalUrl(candidate.url)}"
  }

  /** "2 discarded · Restore" under the results the user kept. */
  data class Discarded(
    override val turn: DiscoverTurn,
    val candidates: List<AiCandidate>,
  ) : ThreadItem {
    override val key: String = "${turn.id}/discarded"
  }

  /** "2 hidden by the content filter · Settings" under the results the turn shows. */
  data class Filtered(override val turn: DiscoverTurn) : ThreadItem {
    override val key: String = "${turn.id}/filtered"
  }

  /**
   * The turn found nothing; [canSearchEverywhere] offers the whole web. When the content filter
   * hid what it found ([DiscoverTurn.filtered]), it says so and offers its setting.
   */
  data class NoResults(
    override val turn: DiscoverTurn,
    val canSearchEverywhere: Boolean,
  ) : ThreadItem {
    override val key: String = "${turn.id}/none"
  }

  /** The turn failed; [canRetry] offers to run it again. */
  data class Failed(override val turn: DiscoverTurn, val canRetry: Boolean) : ThreadItem {
    override val key: String = "${turn.id}/failed"
  }

  /** The user stopped the turn; [canRetry] offers to run it again. */
  data class Stopped(override val turn: DiscoverTurn, val canRetry: Boolean) : ThreadItem {
    override val key: String = "${turn.id}/stopped"
  }
}

/** Key of the thread item of the approval with [approvalId], asked by the turn with [turnId]. */
internal fun approvalKey(turnId: String, approvalId: String): String =
  "$turnId/approval/$approvalId"

/**
 * The items of [session]'s thread, oldest turn first: each message, then what the agent did
 * about it. [waiting] are the session's requests to open a website that wait for an answer; a
 * turn that waits on one shows it instead of placeholder rows.
 *
 * Retry and Search the whole web are offered only on the newest turn, and only while the
 * session does not run, since they run that turn again in place; without [canRun], as while
 * Discover is not set up, never.
 */
internal fun threadItems(
  session: DiscoverSession,
  waiting: List<PageApproval>,
  canRun: Boolean = true,
): List<ThreadItem> =
  buildList {
    val newest = session.turns.lastOrNull()
    for (turn in session.turns) {
      val offers = canRun && turn === newest && !session.running
      add(ThreadItem.Message(turn, first = turn === session.turns.first()))
      if (turn.status == TurnStatus.Queued) {
        add(ThreadItem.Queued(turn))
      } else if (turn.steps.isNotEmpty() || turn.status == TurnStatus.Running) {
        add(ThreadItem.Steps(turn))
      }
      if (turn.access.isNotEmpty()) add(ThreadItem.Access(turn))
      val asks = waiting.filter { it.turnId == turn.id }
      asks.forEach { add(ThreadItem.Approval(turn, it)) }
      when (turn.status) {
        TurnStatus.Queued -> Unit
        TurnStatus.Running -> if (asks.isEmpty()) {
          repeat(SKELETON_ROWS) { add(ThreadItem.Skeleton(turn, it)) }
        }
        TurnStatus.Done -> addResults(session, turn, offers)
        TurnStatus.Failed -> add(ThreadItem.Failed(turn, canRetry = offers))
        TurnStatus.Stopped -> add(ThreadItem.Stopped(turn, canRetry = offers))
      }
    }
  }

private fun MutableList<ThreadItem>.addResults(
  session: DiscoverSession,
  turn: DiscoverTurn,
  offers: Boolean,
) {
  if (turn.summary.isNotBlank()) add(ThreadItem.Summary(turn))
  val shown = session.visible(turn)
  if (shown.isNotEmpty()) {
    add(ThreadItem.Results(turn, shown))
    shown.forEach { add(ThreadItem.Result(turn, it)) }
  }
  val discarded = session.discardedIn(turn)
  if (discarded.isNotEmpty()) add(ThreadItem.Discarded(turn, discarded))
  if (turn.filtered > 0 && turn.candidates.isNotEmpty()) add(ThreadItem.Filtered(turn))
  if (turn.candidates.isEmpty()) {
    add(ThreadItem.NoResults(turn, canSearchEverywhere = offers && turn.sites.isNotEmpty()))
  }
}

/** Placeholder rows a running turn shows until it answers. */
internal const val SKELETON_ROWS: Int = 3
