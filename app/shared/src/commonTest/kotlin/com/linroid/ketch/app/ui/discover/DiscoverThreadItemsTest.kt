package com.linroid.ketch.app.ui.discover

import com.linroid.ketch.app.i18n.load
import com.linroid.ketch.app.state.AccessNote
import com.linroid.ketch.app.state.AiCandidate
import com.linroid.ketch.app.state.AiPageKind
import com.linroid.ketch.app.state.AiPageRequest
import com.linroid.ketch.app.state.DiscoverSession
import com.linroid.ketch.app.state.DiscoverTurn
import com.linroid.ketch.app.state.DiscoveryStep
import com.linroid.ketch.app.state.PageApproval
import com.linroid.ketch.app.state.TurnStatus
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Instant

class DiscoverThreadItemsTest {

  private val at = Instant.fromEpochMilliseconds(1_790_000_000_000)
  private val dmg = candidate("https://download.blender.org/a.dmg", size = 400)
  private val zip = candidate("https://download.blender.org/a.zip", size = 100)

  private fun candidate(url: String, size: Long? = null) =
    AiCandidate(url = url, title = url, fileSize = size, confidence = 0.9f, description = "")

  private fun turn(
    id: String,
    status: TurnStatus,
    vararg found: AiCandidate,
    sites: List<String> = emptyList(),
    summary: String = "",
    steps: List<DiscoveryStep> = emptyList(),
    access: List<AccessNote> = emptyList(),
    filtered: Int = 0,
  ) = DiscoverTurn(
    id = id,
    message = "blender",
    sites = sites,
    startedAt = at,
    status = status,
    steps = steps,
    candidates = found.toList(),
    summary = summary,
    access = access,
    filtered = filtered,
  )

  private fun session(vararg turns: DiscoverTurn, discarded: Set<String> = emptySet()) =
    DiscoverSession(
      id = "session",
      title = "blender",
      createdAt = at,
      updatedAt = at,
      turns = turns.toList(),
      discarded = discarded,
    )

  private fun approval(id: String, turnId: String) = PageApproval(
    id = id,
    sessionId = "session",
    turnId = turnId,
    request = AiPageRequest("https://blender.org/", "blender.org", AiPageKind.Page),
    askedAt = at,
  )

  private fun List<ThreadItem>.kinds(): List<String> = map { it.kind() }

  @Test
  fun threadItems_doneTurnWithADiscardedResult_listsTheRestAndTheDiscardedLine() {
    val done = turn("t1", TurnStatus.Done, dmg, zip, summary = "Found two", steps = Steps)
    val items = threadItems(session(done, discarded = setOf(zip.url)), emptyList())

    assertEquals(
      listOf("Message", "Steps", "Summary", "Results", "Result", "Discarded"),
      items.kinds(),
    )
    assertEquals(listOf(dmg), (items[3] as ThreadItem.Results).candidates)
    assertEquals(listOf(zip), (items[5] as ThreadItem.Discarded).candidates)
  }

  @Test
  fun threadItems_sameLinkInTwoTurns_keysEachRowByItsTurn() {
    val items = threadItems(
      session(turn("t1", TurnStatus.Done, dmg), turn("t2", TurnStatus.Done, dmg)),
      emptyList(),
    )

    val keys = items.map { it.key }
    assertEquals(keys.size, keys.toSet().size)
    assertTrue("t1/result/https://download.blender.org/a.dmg" in keys)
    assertTrue("t2/result/https://download.blender.org/a.dmg" in keys)
  }

  @Test
  fun threadItems_runningTurnWaitingOnAnApproval_showsTheCardInsteadOfPlaceholders() {
    val running = turn("t1", TurnStatus.Running)
    val waiting = approval("a1", "t1")

    assertEquals(
      listOf("Message", "Steps", "Skeleton", "Skeleton", "Skeleton"),
      threadItems(session(running), emptyList()).kinds(),
    )
    val items = threadItems(session(running), listOf(waiting))
    assertEquals(listOf("Message", "Steps", "Approval"), items.kinds())
    assertEquals(approvalKey("t1", "a1"), items.last().key)
  }

  @Test
  fun threadItems_queuedTurn_saysItWaitsWithoutSteps() {
    val items = threadItems(session(turn("t1", TurnStatus.Queued)), emptyList())

    assertEquals(listOf("Message", "Queued"), items.kinds())
  }

  @Test
  fun threadItems_answeredRequests_showTheAccessLine() {
    val notes = listOf(AccessNote("blender.org", allowed = true))
    val done = turn("t1", TurnStatus.Done, dmg, access = notes)
    val items = threadItems(session(done), emptyList())

    assertEquals("Access", items[1].kind())
  }

  @Test
  fun threadItems_retryAndTheWholeWeb_onlyOnTheNewestTurnWhileIdle() {
    val failed = turn("t1", TurnStatus.Failed)
    val stopped = turn("t2", TurnStatus.Stopped)
    val none = turn("t3", TurnStatus.Done, sites = listOf("blender.org"))

    val items = threadItems(session(failed, stopped, none), emptyList())
    assertFalse(items.filterIsInstance<ThreadItem.Failed>().single().canRetry)
    assertFalse(items.filterIsInstance<ThreadItem.Stopped>().single().canRetry)
    assertTrue(items.filterIsInstance<ThreadItem.NoResults>().single().canSearchEverywhere)

    val newest = threadItems(session(none, stopped), emptyList())
    assertFalse(newest.filterIsInstance<ThreadItem.NoResults>().single().canSearchEverywhere)
    assertTrue(newest.filterIsInstance<ThreadItem.Stopped>().single().canRetry)

    val busy = threadItems(session(failed, turn("t4", TurnStatus.Running)), emptyList())
    assertFalse(busy.filterIsInstance<ThreadItem.Failed>().single().canRetry)
  }

  @Test
  fun threadItems_searchesCannotRun_offerNothingToRunAgain() {
    val failed = threadItems(session(turn("t1", TurnStatus.Failed)), emptyList(), canRun = false)
    val none = turn("t1", TurnStatus.Done, sites = listOf("blender.org"))
    val empty = threadItems(session(none), emptyList(), canRun = false)

    assertFalse(failed.filterIsInstance<ThreadItem.Failed>().single().canRetry)
    assertFalse(empty.filterIsInstance<ThreadItem.NoResults>().single().canSearchEverywhere)
  }

  @Test
  fun threadItems_everyResultDiscarded_showsOnlyTheDiscardedLine() {
    val done = turn("t1", TurnStatus.Done, dmg)
    val items = threadItems(session(done, discarded = setOf(dmg.url)), emptyList())

    assertEquals(listOf("Message", "Discarded"), items.kinds())
  }

  @Test
  fun threadItems_doneTurnWithAReplyAndNothingFound_showsTheReplyOverNoResults() {
    val done = turn("t1", TurnStatus.Done, summary = "I can't help find that.", steps = Steps)

    assertEquals(
      listOf("Message", "Steps", "Summary", "NoResults"),
      threadItems(session(done), emptyList()).kinds(),
    )
  }

  @Test
  fun threadItems_contentFilterHidSome_showsTheFilteredLineUnderTheResults() {
    val done = turn("t1", TurnStatus.Done, dmg, filtered = 2)

    assertEquals(
      listOf("Message", "Results", "Result", "Filtered"),
      threadItems(session(done), emptyList()).kinds(),
    )
  }

  @Test
  fun threadItems_contentFilterHidEverything_showsNoResultsWithoutTheLine() {
    val done = turn("t1", TurnStatus.Done, filtered = 3)

    assertEquals(listOf("Message", "NoResults"), threadItems(session(done), emptyList()).kinds())
  }

  @Test
  fun threadItems_busySession_keysEveryItemOnce() {
    val notes = listOf(AccessNote("blender.org", allowed = true))
    val chat = session(
      turn("t1", TurnStatus.Done, dmg, zip, summary = "Two builds", steps = Steps, access = notes),
      turn("t2", TurnStatus.Failed, steps = Steps),
      turn("t3", TurnStatus.Done, dmg, zip, sites = listOf("blender.org")),
      turn("t4", TurnStatus.Running, steps = Steps, access = notes),
      discarded = setOf(zip.url),
    )

    val keys = threadItems(chat, listOf(approval("a1", "t4"), approval("a2", "t4"))).map { it.key }

    assertEquals(keys.distinct(), keys)
  }

  @Test
  fun threadItems_newestTurnGrows_leavesTheItemsOfEarlierTurnsEqual() {
    val first = turn("t1", TurnStatus.Done, dmg, summary = "One build", steps = Steps)
    val before = threadItems(session(first, turn("t2", TurnStatus.Running)), emptyList())
    val grown = turn("t2", TurnStatus.Running, steps = Steps)
    val after = threadItems(session(first, grown), emptyList())

    // Equal items are what lets the thread skip recomposing the earlier turns.
    assertEquals(before.filter { it.turn.id == "t1" }, after.filter { it.turn.id == "t1" })
    assertFalse(before.filter { it.turn.id == "t2" } == after.filter { it.turn.id == "t2" })
  }

  @Test
  fun candidateMeta_sizeAndHost_joinedAndWithoutAnUnknownSize() = runTest {
    assertEquals("400 B · download.blender.org", candidateMeta(dmg).load())
    assertEquals("example.com", candidateMeta(candidate("https://example.com/b.iso")).load())
  }

  @Test
  fun selectionSummary_someFromEarlierTurns_saysHowMany() = runTest {
    val newest = turn("t2", TurnStatus.Done, dmg)
    val chat = session(turn("t1", TurnStatus.Done, dmg, zip), newest)

    assertEquals(0, earlierThan(chat, listOf(dmg)))
    assertEquals(1, earlierThan(chat, listOf(dmg, zip)))
    val summary = selectionSummary(listOf(dmg, zip), earlier = 1).load()
    assertEquals("2 selected · 500 B · 1 from earlier", summary)
    assertEquals("Select downloads to add", selectionSummary(emptyList()).load())
  }

  @Test
  fun selectionSummary_unknownSize_leavesTheTotalOut() = runTest {
    val unsized = candidate("https://example.com/b.iso")

    assertEquals("2 selected", selectionSummary(listOf(dmg, unsized)).load())
  }

  private fun ThreadItem.kind(): String = this::class.simpleName.orEmpty()

  private companion object {
    val Steps = listOf(DiscoveryStep("Searched the web"))
  }
}
