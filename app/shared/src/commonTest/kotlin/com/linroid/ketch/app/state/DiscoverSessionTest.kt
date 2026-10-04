package com.linroid.ketch.app.state

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Instant

class DiscoverSessionTest {

  private val at = Instant.fromEpochMilliseconds(1_790_000_000_000)

  private fun candidate(url: String) =
    AiCandidate(url = url, title = url, confidence = 0.9f, description = "")

  private fun turn(status: TurnStatus, vararg found: AiCandidate) = DiscoverTurn(
    id = "turn-$status",
    message = "blender",
    sites = emptyList(),
    startedAt = at,
    status = status,
    candidates = found.toList(),
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

  @Test
  fun visible_sameLinkTwiceAndADiscardedOne_showsEachKeptLinkOnce() {
    val dmg = candidate("https://download.blender.org/a.dmg")
    val again = candidate("HTTPS://DOWNLOAD.blender.org:443/a.dmg#mirror")
    val zip = candidate("https://download.blender.org/a.zip")
    val turn = turn(TurnStatus.Done, dmg, again, zip)
    val session = session(turn, discarded = setOf("https://download.blender.org/a.zip"))

    assertEquals(listOf(dmg), session.visible(turn))
    assertEquals(listOf(zip), session.discardedIn(turn))
  }

  @Test
  fun running_queuedOrRunningTurn_countsAndFinishedOnesDoNot() {
    assertTrue(session(turn(TurnStatus.Done), turn(TurnStatus.Queued)).running)
    assertTrue(session(turn(TurnStatus.Running)).running)
    assertFalse(
      session(turn(TurnStatus.Done), turn(TurnStatus.Failed), turn(TurnStatus.Stopped)).running,
    )
  }

  @Test
  fun siteList_commasAndSpaces_splitsIntoWebsites() {
    val draft = DiscoverDraft(sites = " ubuntu.com,  blender.org kernel.org ,")
    assertEquals(listOf("ubuntu.com", "blender.org", "kernel.org"), draft.siteList())
  }
}
