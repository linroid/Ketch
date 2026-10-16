package com.linroid.ketch.app.state

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.input.TextFieldValue
import com.linroid.ketch.config.SiteNames
import kotlin.time.Instant

/** Where a Discover turn is. */
enum class TurnStatus {
  /** Waiting for another search to finish before it starts. */
  Queued,

  /** The agent is working on it. */
  Running,

  /** The agent answered; it may have found nothing. */
  Done,

  /** The search failed, such as when the model provider rejected the key. */
  Failed,

  /** The user stopped it, or the app closed while it ran. */
  Stopped,
}

/**
 * How the user answered the agent's request to open a website during a turn.
 *
 * @property host the website, such as `download.blender.org`.
 * @property allowed whether the user allowed it.
 */
data class AccessNote(
  val host: String,
  val allowed: Boolean,
)

/**
 * One exchange of a Discover session: what the user asked and what the agent did about it.
 *
 * @property id unique id of the turn.
 * @property message what the user asked, as typed.
 * @property sites websites the turn was limited to; empty searched the whole web.
 * @property startedAt when it was sent, or last run again.
 * @property status where it is.
 * @property steps what the agent reported doing, oldest first.
 * @property candidates the downloads it found, best first, without links the session discarded
 *   by the time it finished.
 * @property summary the agent's short reply in plain text; blank when it gave none.
 * @property errorText why it failed, in the engine's words; `null` when it did not fail or
 *   gave no reason.
 * @property access how the user answered the agent's requests to open websites, each website
 *   and answer once.
 * @property filtered how many of the agent's results the content filter hid.
 * @property model the provider and model it searched with, once it started; `null` while it
 *   waits, and for turns saved before they were recorded.
 */
data class DiscoverTurn(
  val id: String,
  val message: String,
  val sites: List<String>,
  val startedAt: Instant,
  val status: TurnStatus,
  val steps: List<DiscoveryStep> = emptyList(),
  val candidates: List<AiCandidate> = emptyList(),
  val summary: String = "",
  val errorText: String? = null,
  val access: List<AccessNote> = emptyList(),
  val filtered: Int = 0,
  val model: TurnModel? = null,
)

/**
 * A Discover conversation: the user's first message and the follow-ups that refine it.
 *
 * @property id unique id of the session.
 * @property title what the history calls the session: its [query] until the agent's answer to the
 *   first message names it, then that name.
 * @property createdAt when the first message was sent.
 * @property updatedAt when a turn last started or ended; the history lists sessions by it.
 * @property turns the exchanges, oldest first; never empty.
 * @property discarded links the user discarded from the results, as [SiteNames.canonicalUrl]
 *   leaves them; hidden in every turn and never suggested again.
 */
data class DiscoverSession(
  val id: String,
  val title: String,
  val createdAt: Instant,
  val updatedAt: Instant,
  val turns: List<DiscoverTurn>,
  val discarded: Set<String> = emptySet(),
) {
  /**
   * What the session was started for: the first line of its first message, its spaces collapsed.
   * The downloads it adds record it as their query, whatever the session is called.
   */
  val query: String
    get() = turns.firstOrNull()?.message?.let(::firstLine).orEmpty()

  /** Whether a turn of this session is running or waits to start. */
  val running: Boolean
    get() = turns.any { it.status == TurnStatus.Queued || it.status == TurnStatus.Running }

  /** The results of [turn] the user has not discarded, each link once. */
  fun visible(turn: DiscoverTurn): List<AiCandidate> =
    turn.candidates.distinctBy { SiteNames.canonicalUrl(it.url) }
      .filterNot { SiteNames.canonicalUrl(it.url) in discarded }

  /** The results of [turn] the user discarded, each link once. */
  fun discardedIn(turn: DiscoverTurn): List<AiCandidate> =
    turn.candidates.distinctBy { SiteNames.canonicalUrl(it.url) }
      .filter { SiteNames.canonicalUrl(it.url) in discarded }
}

/**
 * A request of the agent that waits for the user's answer.
 *
 * @property id unique id of the approval.
 * @property sessionId the session whose turn asks.
 * @property turnId the turn that asks.
 * @property request what the agent wants to open.
 * @property askedAt when it started waiting.
 */
data class PageApproval(
  val id: String,
  val sessionId: String,
  val turnId: String,
  val request: AiPageRequest,
  val askedAt: Instant,
)

/** How the user answers a [PageApproval]. */
enum class PageAccessChoice {
  /** Allows this request only. */
  AllowOnce,

  /** Allows the website and its subdomains for the rest of the session. */
  AllowSite,

  /** Allows every website for the rest of the session. */
  AllowAll,

  /** Allows the website and its subdomains from now on, in the page access settings. */
  AlwaysAllow,

  /** Declines the request, and the website for the rest of the session. */
  Deny,
}

/**
 * What the user is writing in a session's composer, kept while they visit other sessions and
 * pages.
 *
 * @property text the message being typed.
 * @property sites websites to limit the next message to, separated by commas or spaces.
 * @property showSites whether the website field shows.
 */
class DiscoverDraft(sites: String = "", showSites: Boolean = false) {
  var text by mutableStateOf(TextFieldValue())
  var sites by mutableStateOf(sites)
  var showSites by mutableStateOf(showSites)

  /** The websites in [sites], without blanks. */
  fun siteList(): List<String> = parseSites(sites)
}

/** The first line of [text] that is not blank, trimmed and its spaces collapsed. */
internal fun firstLine(text: String): String =
  text.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty().trim().replace(Whitespace, " ")

private val Whitespace = Regex("\\s+")

/** Websites typed as "ubuntu.com, blender.org" or "ubuntu.com blender.org". */
internal fun parseSites(text: String): List<String> =
  text.split(",", " ").map { it.trim() }.filter { it.isNotEmpty() }
