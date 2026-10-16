package com.linroid.ketch.app.state

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.text.input.TextFieldValue
import com.linroid.ketch.api.Destination
import com.linroid.ketch.api.DownloadRequest
import com.linroid.ketch.api.DownloadTask
import com.linroid.ketch.api.KetchApi
import com.linroid.ketch.api.log.KetchLogger
import com.linroid.ketch.api.log.describeCauses
import com.linroid.ketch.api.log.redactUrl
import com.linroid.ketch.api.log.redactUrlsIn
import com.linroid.ketch.app.util.TaskOrigin
import com.linroid.ketch.config.SiteNames
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.random.Random
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * What adding discovered candidates did.
 *
 * @property added tasks created, in the order of the candidates.
 * @property failed candidates that could not be added, with why.
 */
data class CandidateAddResult(
  val added: List<DownloadTask>,
  val failed: List<Pair<AiCandidate, Throwable>>,
)

/**
 * A Discover turn that ended with results.
 *
 * @property count how many results it shows.
 */
data class DiscoverFound(val sessionId: String, val turnId: String, val count: Int)

/**
 * Runs Discover: sessions of messages the agent answers with downloads, the agent's requests to
 * open websites that wait for the user's answer, and adding the downloads the user picks.
 *
 * A session runs one turn at a time. At most [MAX_RUNNING] turns run across sessions; the others
 * wait as [TurnStatus.Queued] and start in the order they were sent. A run writes to its turn
 * only while it is still that turn's run, so a stopped run that ends late never touches a turn
 * that was run again or deleted. The sessions are saved to the history when a turn starts or
 * ends and when results are discarded or restored or sessions deleted; the history keeps the
 * newest [MAX_SESSIONS]. A session is called by its first message until the agent's answer to it
 * gives a name, which later turns never change.
 *
 * A request to open a website is answered by the page access settings when they cover it, and
 * by what the user allowed or denied earlier in the session; otherwise it waits in [approvals]
 * until [answer]. A search asked for before discovery is set up waits in [pending] and runs with
 * [runPending] once it is. Switching discovery off, or clearing what it needs, stops every turn
 * that runs or waits to start; a new model or provider lets them finish.
 *
 * Meant to be used from the main thread, like the Compose state it holds.
 *
 * @param aiSettings supplies the discovery provider and the page access settings.
 * @param scope runs the searches; it should use the main dispatcher.
 * @param history keeps the sessions between runs of the app.
 * @param clock when turns start and end and approvals were asked.
 * @param newId makes the ids of sessions, turns and approvals.
 * @param devices the devices a search is for, given the id of the device results go to
 *   ([target]); read as each turn starts.
 * @param random picks the [examples] a new session offers.
 */
class AiDiscoverController(
  private val aiSettings: AiSettingsController,
  private val scope: CoroutineScope,
  private val history: DiscoverHistoryStore = InMemoryDiscoverHistoryStore(),
  private val clock: Clock = Clock.System,
  private val newId: () -> String = { Uuid.random().toString() },
  private val devices: (targetId: String?) -> AiSearchDevices = { AiSearchDevices() },
  private val random: Random = Random.Default,
) {
  /** A turn's search; [job] stays `null` while it waits for a slot. */
  private class Run(val sessionId: String, val turnId: String) {
    var job: Job? = null
  }

  /** An approval of [run] waiting for the user's [answer]. */
  private class Waiting(
    val approval: PageApproval,
    val run: Run,
    val answer: CompletableDeferred<Boolean>,
  )

  /** What the user allowed and denied in a session, as sites; kept in memory only. */
  private class SessionAccess {
    val allowed = mutableSetOf<String>()
    val denied = mutableSetOf<String>()
    var allowAll = false
  }

  private val log = KetchLogger("AiDiscover")

  // The run of each turn that is running or waits to start, by turn id, in the order sent.
  private val runs = LinkedHashMap<String, Run>()
  private val waiting = LinkedHashMap<String, Waiting>()
  private val access = mutableMapOf<String, SessionAccess>()
  private val drafts = mutableMapOf<String?, DiscoverDraft>()

  // What the history keeps of a failure this run of the app saw, by turn id: the message
  // without the provider's reason, which may echo a token.
  private val savedErrors = mutableMapOf<String, String?>()

  private val sessionState = lazy { mutableStateOf(loadHistory()) }

  /** The sessions, loaded on first access, newest first by [DiscoverSession.updatedAt]. */
  var sessions: List<DiscoverSession>
    get() = sessionState.value.value
    private set(value) { sessionState.value.value = value }

  /** Id of the session shown; `null` shows a new, empty one. */
  var currentId by mutableStateOf<String?>(null)
    private set

  /** Searches the new, empty session offers to try; others each time one is shown. */
  var examples by mutableStateOf(pickExamples(random))
    private set

  /** The session shown; `null` for a new one. */
  val current: DiscoverSession?
    get() = currentId?.let { id -> sessions.firstOrNull { it.id == id } }

  /**
   * What is typed in the shown session's composer. A session opened for the first time starts
   * with the websites its last turn was limited to.
   */
  val draft: DiscoverDraft
    get() = drafts.getOrPut(currentId) { draftFor(current) }

  /** Links of the selected results of the shown session; switching sessions clears it. */
  var selected by mutableStateOf(setOf<String>())

  /** Id of the device to add to; `null` adds to the active one. */
  var target by mutableStateOf<String?>(null)

  /** A search that waits for discovery to be set up, or `null`. */
  var pending by mutableStateOf<DiscoverRequest?>(null)
    private set

  /** Requests to open a website that wait for the user's answer, oldest first. */
  var approvals by mutableStateOf(listOf<PageApproval>())
    private set

  /** Whether the Discover page shows, in a window in front or not, as the page reports it. */
  var shown by mutableStateOf(false)

  /** Whether the window that shows the Discover page is in front, as the page reports it. */
  var inFront by mutableStateOf(false)

  private val foundEvents = MutableSharedFlow<DiscoverFound>(extraBufferCapacity = MAX_RUNNING)

  /** Emits when a turn ends with results, for the cue that plays then; never replays. */
  val found: SharedFlow<DiscoverFound> = foundEvents.asSharedFlow()

  /** Whether Discover is on screen: its page shows in a window in front. */
  val visible: Boolean
    get() = shown && inFront

  init {
    scope.launch {
      // Trusting a site or allowing every site in Settings answers the requests it covers.
      snapshotFlow { aiSettings.settings.access }.collect { recheck() }
    }
    scope.launch {
      // Switching Discover off, or clearing its key, stops the searches it was running, which
      // would otherwise go on asking to open websites with no Stop button left to end them; the
      // old engine is released once they end.
      snapshotFlow { aiSettings.provider == null }.collect { off -> if (off) haltAll() }
    }
  }

  /** Shows a new, empty session, with other [examples] when one shows already. */
  fun newSession() {
    if (currentId == null) examples = pickExamples(random, examples) else switchTo(null)
  }

  /** Shows the session with [sessionId], if there is one. */
  fun open(sessionId: String) {
    if (sessions.any { it.id == sessionId }) switchTo(sessionId)
  }

  /**
   * Sends what the [draft] holds: a new session's first message, or a follow-up of the shown
   * one. Does nothing while the text is blank, the shown session runs or discovery is not set
   * up. The text is cleared; the websites stay for the next message.
   */
  fun send() {
    val composer = draft
    val message = composer.text.text.trim()
    if (message.isEmpty() || current?.running == true || aiSettings.provider == null) return
    composer.text = TextFieldValue()
    val session = current
    if (session == null) {
      val created = begin(message, composer.siteList())
      drafts.remove(null)
      drafts[created.id] = composer
    } else {
      follow(session, message, composer.siteList())
    }
  }

  /**
   * Starts a new session for [request], limited to its websites, and shows it. While discovery
   * is not set up, the request waits in [pending] instead and a new, empty session shows, so the
   * setup page says the search waits rather than a saved one hiding it; where discovery cannot
   * run or is switched off, nothing happens.
   */
  fun discover(request: DiscoverRequest) {
    if (!aiSettings.offered) return
    if (aiSettings.provider == null) {
      pending = request.takeIf { it.query.isNotBlank() }
      newSession()
      return
    }
    pending = null
    val query = request.query.trim()
    if (query.isEmpty()) {
      newSession()
      return
    }
    val session = begin(query, request.sites)
    drafts[session.id] = DiscoverDraft(
      sites = request.sites.joinToString(", "),
      showSites = request.sites.isNotEmpty(),
    )
  }

  /** Runs the [pending] search, once discovery is set up. Returns whether it did. */
  fun runPending(): Boolean {
    val request = pending ?: return false
    if (aiSettings.provider == null) return false
    discover(request)
    return true
  }

  /**
   * Stops the turn of the session with [sessionId] that runs or waits to start, keeping the
   * steps it reported. A request it waits on is declined.
   */
  fun stop(sessionId: String? = currentId) {
    val stopped = runs.values.filter { it.sessionId == sessionId }
    if (stopped.isEmpty()) return
    val now = clock.now()
    stopped.forEach { halt(it, now) }
    startQueued()
    save()
  }

  /**
   * Runs the shown session's newest turn again when it failed or was stopped. Does nothing while
   * discovery is not set up.
   */
  fun retry() {
    val session = current ?: return
    val turn = session.turns.last()
    val ended = turn.status == TurnStatus.Failed || turn.status == TurnStatus.Stopped
    if (!ended || session.running || aiSettings.provider == null) return
    rerun(session, turn, turn.sites)
  }

  /**
   * Runs the shown session's newest turn again without the websites it was limited to, which
   * the composer drops too. Does nothing while discovery is not set up.
   */
  fun searchEverywhere() {
    val session = current ?: return
    val turn = session.turns.last()
    if (turn.sites.isEmpty() || session.running || aiSettings.provider == null) return
    drafts[session.id]?.let {
      it.sites = ""
      it.showSites = false
    }
    rerun(session, turn, sites = emptyList())
  }

  /**
   * Discards [urls] from the results of the session with [sessionId]: they are hidden in every
   * turn, leave the selection and are never suggested again in the session.
   *
   * @return the links discarded now, as [SiteNames.canonicalUrl] leaves them, for [restore].
   */
  fun discard(urls: Collection<String>, sessionId: String? = currentId): Set<String> {
    val session = sessions.firstOrNull { it.id == sessionId } ?: return emptySet()
    val discarded = urls.mapTo(mutableSetOf(), SiteNames::canonicalUrl) - session.discarded
    if (discarded.isEmpty()) return discarded
    editSession(session.id) { it.copy(discarded = it.discarded + discarded) }
    if (session.id == currentId) {
      selected = selected.filterTo(mutableSetOf()) { SiteNames.canonicalUrl(it) !in discarded }
    }
    save()
    return discarded
  }

  /** Shows [urls] again in the results of the session with [sessionId]. */
  fun restore(urls: Collection<String>, sessionId: String? = currentId) {
    val session = sessions.firstOrNull { it.id == sessionId } ?: return
    val restored = urls.mapTo(mutableSetOf(), SiteNames::canonicalUrl) intersect session.discarded
    if (restored.isEmpty()) return
    editSession(session.id) { it.copy(discarded = it.discarded - restored) }
    save()
  }

  /**
   * Deletes the session with [sessionId], stopping its turn. Deleting the shown session shows a
   * new one.
   *
   * @return the session as deleted, for [reinsert]; `null` when there is none.
   */
  fun delete(sessionId: String): DiscoverSession? {
    val now = clock.now()
    runs.values.filter { it.sessionId == sessionId }.forEach { halt(it, now) }
    val removed = sessions.firstOrNull { it.id == sessionId } ?: return null
    sessions = sessions.filterNot { it.id == sessionId }
    forget(sessionId)
    if (currentId == sessionId) switchTo(null)
    startQueued()
    save()
    return removed
  }

  /** Puts a deleted [session] back in the history, without showing it. */
  fun reinsert(session: DiscoverSession) {
    if (sessions.any { it.id == session.id }) return
    arrange(sessions + session)
    save()
  }

  /**
   * Deletes every session that is not running. The shown session, if deleted, gives way to a
   * new one.
   *
   * @return the sessions deleted, for [reinsert].
   */
  fun clearHistory(): List<DiscoverSession> {
    val removed = sessions.filterNot { it.running }
    if (removed.isEmpty()) return removed
    val ids = removed.mapTo(mutableSetOf()) { it.id }
    sessions = sessions.filterNot { it.id in ids }
    ids.forEach(::forget)
    if (currentId in ids) switchTo(null)
    save()
    return removed
  }

  /**
   * Answers the approval with [approvalId] with [choice], noting it on its turn. Requests of any
   * session that the answer now covers are answered too.
   */
  fun answer(approvalId: String, choice: PageAccessChoice) {
    val entry = waiting[approvalId] ?: return
    val host = entry.approval.request.host
    val memory = access.getOrPut(entry.run.sessionId) { SessionAccess() }
    when (choice) {
      PageAccessChoice.AllowOnce -> Unit
      PageAccessChoice.AllowSite -> memory.allowed += SiteNames.normalize(host)
      PageAccessChoice.AllowAll -> {
        memory.allowAll = true
        memory.denied.clear()
      }
      PageAccessChoice.AlwaysAllow -> aiSettings.saveAccess { it.trusting(host) }
      PageAccessChoice.Deny -> memory.denied += SiteNames.normalize(host)
    }
    val allowed = choice != PageAccessChoice.Deny
    editTurn(entry.run) { turn ->
      val note = AccessNote(host, allowed)
      if (note in turn.access) turn else turn.copy(access = turn.access + note)
    }
    resolve(entry, allowed)
    recheck()
  }

  /** The approvals the session with [sessionId] waits on, oldest first. */
  fun waitingIn(sessionId: String): List<PageApproval> =
    approvals.filter { it.sessionId == sessionId }

  /** The selected results of the shown session, each link once, in the order of its turns. */
  fun selectedCandidates(): List<AiCandidate> {
    val session = current ?: return emptyList()
    // Selected first: two turns may list the same link spelled differently.
    return session.turns.flatMap(session::visible)
      .filter { it.url in selected }
      .distinctBy { SiteNames.canonicalUrl(it.url) }
  }

  /** Selects or deselects [candidate]. */
  fun toggle(candidate: AiCandidate) {
    selected = if (candidate.url in selected) selected - candidate.url else selected + candidate.url
  }

  /** Selects every result of the shown session's turn with [turnId]. */
  fun selectAll(turnId: String) {
    selected = selected + visibleIn(turnId).map { it.url }
  }

  /** Deselects every result of the shown session, earlier turns' included. */
  fun clearSelection() {
    selected = emptySet()
  }

  /** Deselects every result of the shown session's turn with [turnId]. */
  fun clearSelection(turnId: String) {
    selected = selected - visibleIn(turnId).mapTo(mutableSetOf()) { it.url }
  }

  /**
   * Adds each of [candidates] to [api] on its own, so one that fails never stops the others.
   *
   * @param query search that found them, recorded in the request properties; by default the
   *   shown session's [DiscoverSession.query].
   */
  suspend fun add(
    api: KetchApi,
    candidates: List<AiCandidate>,
    query: String = current?.query.orEmpty(),
  ): CandidateAddResult {
    val added = mutableListOf<DownloadTask>()
    val failed = mutableListOf<Pair<AiCandidate, Throwable>>()
    for (candidate in candidates) {
      catchingUnlessCancelled { api.download(candidate.toRequest(query)) }
        .onSuccess { added += it }
        .onFailure { e ->
          log.w { "Couldn't add ${redactUrl(candidate.url)}: ${e.describeCauses()}" }
          failed += candidate to e
        }
    }
    return CandidateAddResult(added, failed)
  }

  /**
   * The add sheet's request for reviewing [candidates] before adding them to [targetDeviceId].
   *
   * @param query search that found them, recorded in the request properties; by default the
   *   shown session's [DiscoverSession.query].
   */
  fun reviewRequest(
    candidates: List<AiCandidate>,
    targetDeviceId: String?,
    query: String = current?.query.orEmpty(),
  ): IntakeRequest = IntakeRequest(
    seeds = candidates.map { it.toSeed(query) },
    targetDeviceId = targetDeviceId,
  )

  /**
   * Stops every turn that runs or waits to start, as the app closes, and saves the sessions;
   * the requests they wait on are declined.
   */
  fun close() {
    if (sessionState.isInitialized() && !haltAll()) save()
  }

  /** Adds a session for [message] limited to [sites], shows it and starts its first turn. */
  private fun begin(message: String, sites: List<String>): DiscoverSession {
    val now = clock.now()
    val turn = queuedTurn(newId(), message, sites, now)
    val session = DiscoverSession(
      id = newId(),
      // Shown at once; the agent's answer may name it better.
      title = firstLine(message),
      createdAt = now,
      updatedAt = now,
      turns = listOf(turn),
    )
    // Shown first, so the history's cap never drops it.
    switchTo(session.id)
    arrange(listOf(session) + sessions)
    start(session.id, turn.id)
    return session
  }

  /** Adds a turn for [message] to [session] and starts it. */
  private fun follow(session: DiscoverSession, message: String, sites: List<String>) {
    val now = clock.now()
    val turn = queuedTurn(newId(), message, sites, now)
    editSession(session.id) { it.copy(turns = it.turns + turn, updatedAt = now) }
    start(session.id, turn.id)
  }

  /** Clears [turn] of [session] and runs it again, limited to [sites]. */
  private fun rerun(session: DiscoverSession, turn: DiscoverTurn, sites: List<String>) {
    val now = clock.now()
    savedErrors.remove(turn.id)
    val again = queuedTurn(turn.id, turn.message, sites, now)
    editSession(session.id) { edited ->
      edited.copy(turns = edited.turns.map { if (it.id == turn.id) again else it }, updatedAt = now)
    }
    start(session.id, turn.id)
  }

  private fun queuedTurn(id: String, message: String, sites: List<String>, now: Instant) =
    DiscoverTurn(
      id = id,
      message = message,
      sites = sites,
      startedAt = now,
      status = TurnStatus.Queued,
    )

  /** Queues the turn with [turnId] and starts it when a slot is free. */
  private fun start(sessionId: String, turnId: String) {
    runs[turnId] = Run(sessionId, turnId)
    startQueued()
    save()
  }

  /** Starts the turns that wait, in the order they were sent, while slots are free. */
  private fun startQueued() {
    for (run in runs.values.toList()) {
      if (runs.values.count { it.job != null } >= MAX_RUNNING) return
      if (run.job == null && runs[run.turnId] === run) launch(run)
    }
  }

  private fun launch(run: Run) {
    val provider = aiSettings.provider
    val session = sessions.firstOrNull { it.id == run.sessionId }
    val turn = session?.turns?.firstOrNull { it.id == run.turnId }
    if (provider == null || turn == null) {
      // Discovery was switched off while the turn waited.
      editTurn(run) { it.copy(status = TurnStatus.Stopped) }
      runs.remove(run.turnId)
      return
    }
    val request = requestFor(session, turn)
    // What it searches with: a provider or model chosen later applies to the next turn.
    val model = aiSettings.activeModel
    editTurn(run) { it.copy(status = TurnStatus.Running, model = model) }
    run.job = scope.launch {
      // The agent reports steps from its own threads; they reach the turn in order, here.
      val reported = Channel<DiscoveryStep>(Channel.UNLIMITED)
      val follower = launch {
        for (step in reported) editTurn(run) { it.copy(steps = it.steps + step) }
      }
      val result = try {
        catchingUnlessCancelled {
          try {
            provider.discover(
              request = request,
              onStep = { reported.trySend(it) },
              approve = { ask(run, it) },
            )
          } finally {
            reported.close()
          }
        }
      } catch (e: Error) {
        // Such as a reflection failure in a shrunk build: the turn still ends and frees its
        // slot, which nothing else would. Its message is no use to the user.
        log.e(e) { "Discovery crashed, session=${run.sessionId}: ${e.describeCauses()}" }
        Result.failure(IllegalStateException(null, e))
      }
      follower.join()
      finish(run, result)
    }
  }

  /**
   * The search for [turn]: every earlier turn of [session] as history, the first and the latest
   * ones when there are many, with the results of those that finished, the discarded links and
   * the devices it is for.
   */
  private fun requestFor(session: DiscoverSession, turn: DiscoverTurn): AiDiscoverRequest {
    val earlier = session.turns.takeWhile { it.id != turn.id }
    val kept = if (earlier.size > LATEST_TURNS + 1) {
      listOf(earlier.first()) + earlier.takeLast(LATEST_TURNS)
    } else {
      earlier
    }
    return AiDiscoverRequest(
      query = turn.message,
      sites = turn.sites,
      history = kept.map {
        val done = it.status == TurnStatus.Done
        AiDiscoverTurn(
          request = it.message,
          sites = it.sites,
          completed = done,
          results = if (done) session.visible(it) else emptyList(),
        )
      },
      excludedUrls = session.discarded,
      contentFilter = aiSettings.settings.contentFilter,
      devices = devices(target),
    )
  }

  private fun finish(run: Run, result: Result<AiDiscoverResponse>) {
    if (runs[run.turnId] !== run) return
    val now = clock.now()
    result.onSuccess { response ->
      val session = sessions.firstOrNull { it.id == run.sessionId }
      // Links discarded while the search ran never show.
      val discarded = session?.discarded.orEmpty()
      val earlier = session?.turns.orEmpty().takeWhile { it.id != run.turnId }
      val found = carryOver(
        found = response.candidates.filterNot { SiteNames.canonicalUrl(it.url) in discarded },
        earlier = earlier.flatMap { it.candidates },
      )
      log.i { "Found ${found.size} candidates, session=${run.sessionId}" }
      editTurn(run, touched = now) {
        it.copy(
          status = TurnStatus.Done,
          candidates = found,
          summary = response.summary,
          filtered = response.filtered,
        )
      }
      if (session != null) name(session, run.turnId, response.title)
      if (found.isNotEmpty()) {
        foundEvents.tryEmit(DiscoverFound(run.sessionId, run.turnId, found.size))
      }
    }.onFailure { e ->
      // A provider failure's message and causes quote the provider's reply, which may echo a
      // token; its brief leaves that out.
      val brief = (e as? AiDiscoverFailure)?.brief
      log.w { "Discovery failed, session=${run.sessionId}: ${brief ?: e.describeCauses()}" }
      savedErrors[run.turnId] = brief ?: e.message?.let(::redactUrlsIn)
      // The engine's message, such as the provider rejecting the token, says what to fix.
      editTurn(run, touched = now) { it.copy(status = TurnStatus.Failed, errorText = e.message) }
    }
    runs.remove(run.turnId)
    startQueued()
    save()
  }

  /**
   * Calls [session] by [title], the agent's name for it, when [turnId] is its first turn and it
   * is still called by its first message: a later turn, or a first turn run again after it was
   * named, never renames it. A blank [title] keeps the name.
   */
  private fun name(session: DiscoverSession, turnId: String, title: String) {
    val named = firstLine(title)
    if (named.isEmpty() || session.turns.firstOrNull()?.id != turnId) return
    if (session.title != session.query) return
    log.d { "The agent named session=${session.id}" }
    editSession(session.id) { it.copy(title = named) }
  }

  /**
   * Stops every turn that runs or waits to start, declining the requests they wait on, and saves
   * the sessions. Returns whether there were any.
   */
  private fun haltAll(): Boolean {
    if (runs.isEmpty()) return false
    val now = clock.now()
    runs.values.toList().forEach { halt(it, now) }
    save()
    return true
  }

  /** Marks [run]'s turn stopped, declines the request it waits on, and cancels it. */
  private fun halt(run: Run, now: Instant) {
    editTurn(run, touched = now) { it.copy(status = TurnStatus.Stopped) }
    waiting.values.filter { it.run === run }.forEach { resolve(it, allowed = false) }
    runs.remove(run.turnId)
    run.job?.cancel()
  }

  /**
   * Whether [run] may open [request]'s website: at once when the settings or the session's
   * answers decide it, else once the user answers. Runs on the [scope]'s dispatcher, in the
   * agent's coroutine, so stopping the run stops the wait.
   */
  private suspend fun ask(run: Run, request: AiPageRequest): Boolean =
    withContext(scope.coroutineContext.minusKey(Job)) {
      if (runs[run.turnId] !== run) return@withContext false
      decide(run, request.host)?.let { return@withContext it }
      val approval = PageApproval(newId(), run.sessionId, run.turnId, request, clock.now())
      val entry = Waiting(approval, run, CompletableDeferred())
      waiting[approval.id] = entry
      approvals = approvals + approval
      log.d { "Asking to open ${request.host}, session=${run.sessionId}" }
      try {
        entry.answer.await()
      } finally {
        if (waiting.remove(approval.id) != null) {
          approvals = approvals.filterNot { it.id == approval.id }
        }
      }
    }

  /**
   * Whether [run] may open [host] without asking: `true` when the page access settings allow it,
   * even after the user denied it earlier in the session; else `false` when the user denied it
   * in the session, `true` when the session allows it, and `null` to ask.
   */
  private fun decide(run: Run, host: String): Boolean? {
    val memory = access[run.sessionId]
    val settings = aiSettings.settings.access
    // Allow automatically, or the site always allowed since, outranks a Deny in the chat.
    if (settings.allowsWithoutAsking(host)) return true
    if (memory != null && memory.denied.any { SiteNames.covers(it, host) }) return false
    if (memory?.allowAll == true) return true
    // A turn limited to websites only goes where the user said.
    val turn = sessions.firstOrNull { it.id == run.sessionId }?.turns
      ?.firstOrNull { it.id == run.turnId }
    if (!turn?.sites.isNullOrEmpty()) return true
    return if (settings.allowsWithoutAsking(host, memory?.allowed.orEmpty())) true else null
  }

  /** Answers every waiting request the settings or its session's answers now decide. */
  private fun recheck() {
    for (entry in waiting.values.toList()) {
      decide(entry.run, entry.approval.request.host)?.let { resolve(entry, it) }
    }
  }

  private fun resolve(entry: Waiting, allowed: Boolean) {
    waiting.remove(entry.approval.id)
    approvals = approvals.filterNot { it.id == entry.approval.id }
    entry.answer.complete(allowed)
  }

  private fun switchTo(sessionId: String?) {
    if (sessionId == currentId) return
    currentId = sessionId
    selected = emptySet()
    if (sessionId == null) examples = pickExamples(random, examples)
  }

  private fun visibleIn(turnId: String): List<AiCandidate> {
    val session = current ?: return emptyList()
    return session.turns.firstOrNull { it.id == turnId }?.let(session::visible).orEmpty()
  }

  private fun editTurn(
    run: Run,
    touched: Instant? = null,
    transform: (DiscoverTurn) -> DiscoverTurn,
  ) {
    if (runs[run.turnId] !== run) return
    editSession(run.sessionId) { session ->
      session.copy(
        turns = session.turns.map { if (it.id == run.turnId) transform(it) else it },
        updatedAt = touched ?: session.updatedAt,
      )
    }
  }

  private fun editSession(sessionId: String, transform: (DiscoverSession) -> DiscoverSession) {
    arrange(sessions.map { if (it.id == sessionId) transform(it) else it })
  }

  /**
   * Sets [sessions] to [list], newest first, without the oldest beyond [MAX_SESSIONS]; a
   * running or shown session is never dropped.
   */
  private fun arrange(list: List<DiscoverSession>) {
    val sorted = list.sortedByDescending { it.updatedAt }
    var extra = sorted.size - MAX_SESSIONS
    val dropped = mutableSetOf<String>()
    for (session in sorted.asReversed()) {
      if (extra <= 0) break
      if (session.running || session.id == currentId) continue
      dropped += session.id
      extra--
    }
    dropped.forEach(::forget)
    sessions = if (dropped.isEmpty()) sorted else sorted.filterNot { it.id in dropped }
  }

  private fun forget(sessionId: String) {
    drafts.remove(sessionId)
    access.remove(sessionId)
  }

  private fun draftFor(session: DiscoverSession?): DiscoverDraft =
    DiscoverDraft(sites = session?.turns?.last()?.sites.orEmpty().joinToString(", "))

  private fun save() {
    history.save(
      sessions.map { session ->
        if (session.turns.none { it.id in savedErrors }) return@map session
        session.copy(
          turns = session.turns.map { turn ->
            if (turn.id in savedErrors) turn.copy(errorText = savedErrors[turn.id]) else turn
          },
        )
      },
    )
  }

  /**
   * The saved sessions, newest first and at most [MAX_SESSIONS]. Turns that ran when the app
   * last closed read as stopped: nothing runs them now.
   */
  private fun loadHistory(): List<DiscoverSession> = history.load()
    .filter { it.turns.isNotEmpty() }
    .map { session ->
      if (!session.running) return@map session
      session.copy(
        turns = session.turns.map {
          if (it.status == TurnStatus.Queued || it.status == TurnStatus.Running) {
            it.copy(status = TurnStatus.Stopped)
          } else {
            it
          }
        },
      )
    }
    .sortedByDescending { it.updatedAt }
    .take(MAX_SESSIONS)

  companion object {
    /** Most turns that run at once, across sessions. */
    internal const val MAX_RUNNING: Int = 3

    /** Most sessions the history keeps. */
    internal const val MAX_SESSIONS: Int = 50

    /** Latest earlier turns a follow-up sends, besides the first. */
    internal const val LATEST_TURNS: Int = 5
  }
}

/**
 * [found] with what the [earlier] results of the session, oldest first, knew of the same links.
 * A candidate whose link an earlier turn returned, compared by [SiteNames.canonicalUrl], gets the
 * file name, size, content type, source page and description it lacks from the latest such
 * result: a follow-up answered from the earlier results may leave them out. What the agent
 * returned now wins.
 */
internal fun carryOver(found: List<AiCandidate>, earlier: List<AiCandidate>): List<AiCandidate> {
  if (earlier.isEmpty()) return found
  val latest = HashMap<String, AiCandidate>()
  for (candidate in earlier) latest[SiteNames.canonicalUrl(candidate.url)] = candidate
  return found.map { candidate ->
    val known = latest[SiteNames.canonicalUrl(candidate.url)] ?: return@map candidate
    candidate.copy(
      fileName = candidate.fileName?.takeIf { it.isNotBlank() } ?: known.fileName,
      fileSize = candidate.fileSize ?: known.fileSize,
      mimeType = candidate.mimeType?.takeIf { it.isNotBlank() } ?: known.mimeType,
      sourceUrl = candidate.sourceUrl.ifBlank { known.sourceUrl },
      description = candidate.description.ifBlank { known.description },
    )
  }
}

/** Builds the request that adds this candidate, remembering where it came from. */
internal fun AiCandidate.toRequest(query: String): DownloadRequest = DownloadRequest(
  url = url,
  destination = fileName?.takeIf { it.isNotBlank() }?.let(::Destination),
  headers = discoverHeaders(),
  properties = discoverProperties(query),
)

/** The add sheet's row for this candidate, with the same headers and properties. */
internal fun AiCandidate.toSeed(query: String): IntakeSeed = IntakeSeed(
  url = url,
  fileName = fileName?.takeIf { it.isNotBlank() },
  headers = discoverHeaders(),
  properties = discoverProperties(query),
)

// Servers that check the referrer see the page the link was found on, as a browser would send.
private fun AiCandidate.discoverHeaders(): Map<String, String> =
  if (sourceUrl.isNotBlank()) mapOf("Referer" to sourceUrl) else emptyMap()

private fun discoverProperties(query: String): Map<String, String> = buildMap {
  put(TaskOrigin.PROPERTY, TaskOrigin.Discover.id)
  if (query.isNotBlank()) put(QUERY_PROPERTY, query)
}

/** Request property holding the Discover query that found a download. */
internal const val QUERY_PROPERTY = "ketch.query"
