package com.linroid.ketch.torrent.v1

import com.linroid.ketch.torrent.engine.TorrentBufferBudget
import com.linroid.ketch.torrent.peer.PeerMessage
import com.linroid.ketch.torrent.storage.CommitOutcome
import com.linroid.ketch.torrent.storage.SelectionView
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TorrentPieceSchedulerTest {
  private val block = 16_384
  /** Pieces of four blocks. */
  private val piece = 4 * block

  private fun scheduler(
    pieces: Int,
    budget: TorrentBufferBudget,
    clock: () -> Long = { 0 },
    verified: BooleanArray = BooleanArray(pieces),
  ) = TorrentPieceScheduler(BooleanArray(pieces) { true }, verified, { piece }, budget, clock)

  private fun all(pieces: Int) = BooleanArray(pieces) { true }

  private fun data(index: Int, begin: Int) =
    ByteArray(block) { (index * 31 + begin / block).toByte() }

  /** Delivers every block of [requests] from [peer], returning the last arrival. */
  private suspend fun TorrentPieceScheduler.deliver(
    peer: Int,
    requests: List<PeerMessage.Request>,
  ): TorrentPieceScheduler.Arrival? = requests.map {
    received(peer, it.index, it.begin, data(it.index, it.begin))
  }.lastOrNull()

  @Test
  fun peersShareOnePieceBlockByBlock_andItsBufferIsChargedOnce() = runTest {
    val budget = TorrentBufferBudget(piece)
    val scheduler = scheduler(pieces = 2, budget)
    scheduler.availability(1, all(2))
    scheduler.availability(2, all(2))
    val first = scheduler.request(1, max = 2)
    assertEquals(listOf(0 to 0, 0 to block), first.requests.map { it.index to it.begin })
    // The budget holds one piece: the second peer takes the rest of the same one.
    val second = scheduler.request(2, max = 4)
    assertEquals(listOf(0 to 2 * block, 0 to 3 * block),
      second.requests.map { it.index to it.begin })
    assertTrue(second.starved, "No buffer was left to open piece 1")
    assertEquals(piece, budget.allocated)
    assertIs<TorrentPieceScheduler.Arrival.Kept>(scheduler.deliver(1, first.requests))
    val complete = assertIs<TorrentPieceScheduler.Arrival.Complete>(
      scheduler.deliver(2, second.requests))
    assertContentEquals((0 until 4).flatMap { data(0, it * block).asList() }.toByteArray(),
      complete.bytes)
    assertFalse(scheduler.finish(2, 0, CommitOutcome.VERIFIED))
    assertEquals(0, budget.allocated)
    assertTrue(scheduler.isVerified(0))
    // The buffer it freed opens the next piece.
    assertEquals(1, scheduler.request(1, max = 1).requests.single().index)
    for (peer in 1..2) scheduler.remove(peer)
    assertEquals(0, budget.allocated)
  }

  @Test
  fun requestsRunAcrossPieceEnds() = runTest {
    val budget = TorrentBufferBudget(4 * piece)
    val scheduler = scheduler(pieces = 4, budget)
    scheduler.availability(1, all(4))
    val grant = scheduler.request(1, max = 10)
    assertEquals(10, grant.requests.size)
    assertEquals(listOf(0, 1, 2), grant.requests.map { it.index }.distinct())
    assertEquals(3 * piece, budget.allocated)
    scheduler.remove(1)
    // Nobody connected has them any more.
    assertEquals(0, budget.allocated)
  }

  @Test
  fun rarestPieceOpensFirst_andHavesBuildAvailabilityOnce() = runTest {
    val budget = TorrentBufferBudget(piece)
    val scheduler = scheduler(pieces = 2, budget)
    // A peer may announce before any bitfield, and repeat itself.
    repeat(3) { scheduler.announce(1, 0) }
    scheduler.announce(2, 1)
    scheduler.announce(3, 1)
    scheduler.availability(4, all(2))
    // Piece 0 is held by peers 1 and 4; piece 1 by peers 2, 3 and 4. Counting the repeated
    // announcements would make piece 0 look the most common one.
    assertEquals(0, scheduler.request(4, max = 1).requests.single().index)
    for (peer in 1..4) scheduler.remove(peer)
    assertEquals(0, budget.allocated)
  }

  @Test
  fun chokeFreesOutstandingBlocks_whileReceivedBlocksStay() = runTest {
    val budget = TorrentBufferBudget(piece)
    val scheduler = scheduler(pieces = 1, budget)
    scheduler.availability(1, all(1))
    scheduler.availability(2, all(1))
    val first = scheduler.request(1, max = 4).requests
    scheduler.deliver(1, first.take(2))
    // Peer 1 chokes us: its other two requests are void, its two blocks kept.
    scheduler.releasePeer(1)
    val rest = scheduler.request(2, max = 4).requests
    assertEquals(first.drop(2), rest)
    assertIs<TorrentPieceScheduler.Arrival.Complete>(scheduler.deliver(2, rest))
    assertFalse(scheduler.finish(2, 0, CommitOutcome.VERIFIED))
    // A late copy of a block is not needed.
    assertEquals(TorrentPieceScheduler.Arrival.Unneeded,
      scheduler.received(1, 0, 2 * block, data(0, 2 * block)))
    for (peer in 1..2) scheduler.remove(peer)
    assertEquals(0, budget.allocated)
  }

  @Test
  fun rejectedBlock_isFreeForAnyPeer() = runTest {
    val budget = TorrentBufferBudget(piece)
    val scheduler = scheduler(pieces = 1, budget)
    scheduler.availability(1, all(1))
    scheduler.availability(2, all(1))
    val asked = scheduler.request(1, max = 4).requests
    scheduler.release(1, listOf(asked[1]))
    assertEquals(asked[1], scheduler.request(2, max = 1).requests.single())
    for (peer in 1..2) scheduler.remove(peer)
    assertEquals(0, budget.allocated)
  }

  @Test
  fun endgameDuplicatesOutstandingBlocks_andTheFirstCopyCancelsTheOther() = runTest {
    val budget = TorrentBufferBudget(piece)
    val scheduler = scheduler(pieces = 1, budget)
    scheduler.availability(1, all(1))
    scheduler.availability(2, all(1))
    val slow = scheduler.request(1, max = 4).requests
    // Every piece is open and every block asked for: peer 2 asks for peer 1's too.
    val fast = scheduler.request(2, max = 4).requests
    assertEquals(slow, fast)
    scheduler.deliver(2, fast.take(3))
    val cancels = scheduler.poll(1, 0).cancels
    assertEquals(fast.take(3), cancels)
    assertTrue(scheduler.poll(2, 0).cancels.isEmpty())
    // The slow copy of the last block arrives first this time.
    assertIs<TorrentPieceScheduler.Arrival.Complete>(scheduler.deliver(1, slow.drop(3)))
    assertEquals(fast.drop(3), scheduler.poll(2, 0).cancels)
    // Neither sent the piece alone, so neither is blamed for a failed check.
    assertFalse(scheduler.finish(1, 0, CommitOutcome.CORRUPT))
    for (peer in 1..2) scheduler.remove(peer)
    assertEquals(0, budget.allocated)
  }

  @Test
  fun lateBlocks_areAskedOfAnotherPeerBeforeTheEndgame() = runTest {
    var now = 0L
    // One buffer: while the slow peer holds piece 0, nobody can open piece 1.
    val budget = TorrentBufferBudget(piece)
    val scheduler = scheduler(pieces = 2, budget, clock = { now })
    scheduler.availability(1, all(2))
    scheduler.availability(2, all(2))
    val slow = scheduler.request(1, max = 4, lateAfterMs = 1_000).requests
    assertEquals(4, slow.size)
    // On time, its blocks are its own.
    assertTrue(scheduler.request(2, max = 4).requests.isEmpty())
    now = 1_500
    val fast = scheduler.request(2, max = 4).requests
    assertEquals(slow, fast)
    // The faster copy wins, and the slow peer is told to cancel its requests.
    assertIs<TorrentPieceScheduler.Arrival.Complete>(scheduler.deliver(2, fast))
    assertEquals(slow, scheduler.poll(1, 0).cancels)
    assertFalse(scheduler.finish(2, 0, CommitOutcome.VERIFIED))
    for (peer in 1..2) scheduler.remove(peer)
    assertEquals(0, budget.allocated)
  }

  @Test
  fun endgame_runsWhileAPieceNobodyHasStaysUnopened() = runTest {
    val budget = TorrentBufferBudget(2 * piece)
    val scheduler = scheduler(pieces = 2, budget)
    // Nobody has piece 1.
    scheduler.availability(1, booleanArrayOf(true, false))
    scheduler.availability(2, booleanArrayOf(true, false))
    val first = scheduler.request(1, max = 4, lateAfterMs = 60_000).requests
    // Piece 1 can never open, so the last piece left is fetched by both at once.
    assertEquals(first, scheduler.request(2, max = 4).requests)
    for (peer in 1..2) scheduler.remove(peer)
    assertEquals(0, budget.allocated)
  }

  @Test
  fun corruptPiece_blamesItsOnlySender_andOtherwiseGoesToOnePeer() = runTest {
    val budget = TorrentBufferBudget(piece)
    val scheduler = scheduler(pieces = 1, budget)
    scheduler.availability(1, all(1))
    scheduler.availability(2, all(1))
    scheduler.deliver(1, scheduler.request(1, max = 2).requests)
    scheduler.deliver(2, scheduler.request(2, max = 2).requests)
    // Sent by two peers: nobody is blamed, and one peer fetches it whole next.
    assertFalse(scheduler.finish(2, 0, CommitOutcome.CORRUPT))
    val whole = scheduler.request(2, max = 2).requests
    assertEquals(2, whole.size)
    assertTrue(scheduler.request(1, max = 4).requests.isEmpty())
    scheduler.deliver(2, whole + scheduler.request(2, max = 2).requests)
    // Sent by one peer alone: that peer is blamed.
    assertTrue(scheduler.finish(2, 0, CommitOutcome.CORRUPT))
    scheduler.remove(2)
    // Gone, it leaves the piece to anyone.
    assertEquals(4, scheduler.request(1, max = 4).requests.size)
    scheduler.remove(1)
    assertEquals(0, budget.allocated)
  }

  @Test
  fun abandonedCommit_freesThePieceForAnotherFetch() = runTest {
    val budget = TorrentBufferBudget(piece)
    val scheduler = scheduler(pieces = 1, budget)
    scheduler.availability(1, all(1))
    scheduler.deliver(1, scheduler.request(1, max = 4).requests)
    assertFalse(scheduler.finish(1, 0, null))
    assertEquals(4, scheduler.request(1, max = 4).requests.size)
    scheduler.remove(1)
    assertEquals(0, budget.allocated)
  }

  @Test
  fun stalePartialPiece_givesItsBufferToAnother() = runTest {
    var now = 0L
    val budget = TorrentBufferBudget(piece)
    val scheduler = scheduler(pieces = 2, budget, clock = { now })
    scheduler.availability(1, booleanArrayOf(true, false))
    scheduler.availability(2, booleanArrayOf(false, true))
    scheduler.deliver(1, scheduler.request(1, max = 2).requests)
    // Peer 1 chokes us for good: piece 0 keeps its blocks and its buffer for a while.
    scheduler.releasePeer(1)
    assertTrue(scheduler.request(2, max = 4).starved)
    now = 10_000
    assertEquals(listOf(1), scheduler.request(2, max = 4).requests.map { it.index }.distinct())
    assertEquals(piece, budget.allocated)
    for (peer in 1..2) scheduler.remove(peer)
    assertEquals(0, budget.allocated)
  }

  @Test
  fun partialPieces_giveTheirBuffersUpWhenAbandoned() = runTest {
    var now = 0L
    val budget = TorrentBufferBudget(2 * piece)
    val scheduler = scheduler(pieces = 2, budget, clock = { now })
    scheduler.availability(1, booleanArrayOf(true, false))
    scheduler.availability(2, all(2))
    scheduler.deliver(1, scheduler.request(1, max = 2).requests)
    scheduler.releasePeer(1)
    scheduler.deliver(2, scheduler.request(2, max = 4).requests.filter { it.index == 1 }.take(1))
    scheduler.releasePeer(2)
    assertEquals(2 * piece, budget.allocated)
    // Piece 1 waits for peer 2, piece 0 for either peer, however long it takes.
    now = 59_000
    scheduler.poll(1, 0)
    assertEquals(2 * piece, budget.allocated)
    now = 61_000
    scheduler.poll(1, 0)
    assertEquals(0, budget.allocated)
    // A piece nobody connected has goes as its last holder does.
    scheduler.deliver(1, scheduler.request(1, max = 2).requests)
    scheduler.releasePeer(1)
    assertEquals(piece, budget.allocated)
    scheduler.remove(2)
    assertEquals(piece, budget.allocated)
    scheduler.remove(1)
    assertEquals(0, budget.allocated)
    scheduler.close()
  }

  @Test
  fun close_freesEveryBuffer() = runTest {
    val budget = TorrentBufferBudget(4 * piece)
    val scheduler = scheduler(pieces = 4, budget)
    scheduler.availability(-1, all(4))
    scheduler.availability(1, all(4))
    scheduler.request(1, max = 6)
    scheduler.claimRun(-1, maxPieces = 1, maxBytes = piece)
    assertEquals(3 * piece, budget.allocated)
    scheduler.close()
    assertEquals(0, budget.allocated)
  }

  @Test
  fun interestFollowsWhatThePeerHasAndWeStillWant() = runTest {
    val budget = TorrentBufferBudget(piece)
    val scheduler = scheduler(pieces = 2, budget)
    scheduler.availability(1, booleanArrayOf(false, false))
    assertFalse(scheduler.poll(1, 0).wanted)
    scheduler.announce(1, 1)
    assertTrue(scheduler.poll(1, 0).wanted)
    scheduler.verified(1)
    assertFalse(scheduler.poll(1, 0).wanted)
    scheduler.revoke(1)
    assertTrue(scheduler.poll(1, 0).wanted)
    scheduler.retarget { view(wanted = booleanArrayOf(true, false)) }
    assertFalse(scheduler.poll(1, 0).wanted)
    scheduler.announce(1, 0)
    // It has every piece, but we have not: a selection change may want them yet.
    assertFalse(scheduler.poll(1, 0).bothSeeds)
    scheduler.verified(0)
    scheduler.verified(1)
    assertTrue(scheduler.poll(1, 0).bothSeeds)
    scheduler.remove(1)
  }

  @Test
  fun retarget_closesPiecesNoLongerWanted_andCancelsTheirRequests() = runTest {
    val budget = TorrentBufferBudget(2 * piece)
    val scheduler = scheduler(pieces = 2, budget)
    scheduler.availability(1, all(2))
    val asked = scheduler.request(1, max = 2).requests
    assertEquals(0, asked.first().index)
    val before = scheduler.wantVersion
    scheduler.retarget { view(wanted = booleanArrayOf(false, true)) }
    assertTrue(scheduler.wantVersion > before)
    assertFalse(scheduler.isWanted(0))
    assertEquals(asked, scheduler.poll(1, 0).cancels)
    assertEquals(0, budget.allocated)
    assertEquals(setOf(1), scheduler.request(1, max = 4).requests.map { it.index }.toSet())
    scheduler.retarget { view(wanted = booleanArrayOf(false, false)) }
    assertTrue(scheduler.request(1, max = 4).requests.isEmpty())
    scheduler.remove(1)
    assertEquals(0, budget.allocated)
  }

  @Test
  fun verifiedPieces_areLoggedOnceForHaves() = runTest {
    val budget = TorrentBufferBudget(piece)
    val scheduler = scheduler(pieces = 3, budget, verified = booleanArrayOf(true, false, false))
    val start = assertNotNull(scheduler.poll(1, 0).haves)
    assertContentEquals(intArrayOf(0), start)
    val cursor = scheduler.haveCursor()
    assertNull(scheduler.poll(1, cursor).haves)
    scheduler.retarget { view(wanted = all(3), verified = booleanArrayOf(true, true, false)) }
    scheduler.verified(2)
    scheduler.revoke(2)
    scheduler.verified(2)
    val poll = scheduler.poll(1, cursor)
    assertContentEquals(intArrayOf(1, 2), poll.haves)
    assertNull(scheduler.poll(1, poll.haveCursor).haves)
  }

  @Test
  fun revokedPiece_isFetchedAgain() = runTest {
    val budget = TorrentBufferBudget(piece)
    val scheduler = scheduler(pieces = 1, budget)
    scheduler.availability(1, all(1))
    scheduler.verified(0)
    assertTrue(scheduler.request(1, max = 4).requests.isEmpty())
    scheduler.revoke(0)
    assertFalse(scheduler.isVerified(0))
    assertEquals(4, scheduler.request(1, max = 4).requests.size)
    scheduler.remove(1)
    assertEquals(0, budget.allocated)
  }

  @Test
  fun webSeedRuns_andPeersNeverFetchTheSamePieceOutsideTheEndgame() = runTest {
    val budget = TorrentBufferBudget(8 * piece)
    val scheduler = scheduler(pieces = 8, budget)
    scheduler.availability(-1, all(8))
    scheduler.availability(1, all(8))
    // The peer opens piece 0; the seed's run starts after it and takes its share.
    assertEquals(setOf(0), scheduler.request(1, max = 4).requests.map { it.index }.toSet())
    val run = scheduler.claimRun(-1, maxPieces = 16, maxBytes = 16 * piece)
    assertEquals(listOf(1, 2, 3), run.map { it.index })
    // The peer opens what nobody fetches.
    assertEquals(setOf(4), scheduler.request(1, max = 4).requests.map { it.index }.toSet())
    for (claim in run) scheduler.release(-1, claim.index)
    scheduler.remove(-1)
    scheduler.remove(1)
    assertEquals(0, budget.allocated)
  }

  @Test
  fun budget_reservationCannotExceedCapacityAndCloseIsIdempotent() {
    val budget = TorrentBufferBudget(16)
    val first = assertNotNull(budget.reserve(12))
    assertNull(budget.reserve(5))
    val second = assertNotNull(budget.reserve(4))
    assertEquals(16, budget.allocated)
    first.close()
    first.close()
    assertEquals(4, budget.allocated)
    second.close()
    assertEquals(0, budget.allocated)
  }

  private fun view(
    wanted: BooleanArray = booleanArrayOf(true, true),
    verified: BooleanArray = BooleanArray(wanted.size),
  ) = SelectionView(1, wanted, verified, IntArray(0))
}
