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
  fun discardedPayload_countsWhatNoVerifiedPieceKeeps() = runTest {
    val budget = TorrentBufferBudget(2 * piece)
    var discarded = 0L
    val scheduler = TorrentPieceScheduler(all(2), BooleanArray(2), { piece }, budget, { 0 },
      onDiscard = { discarded += it })
    scheduler.availability(1, all(2))
    scheduler.availability(2, all(2))
    val first = scheduler.request(1, max = 4).requests
    assertEquals(List(4) { 0 }, first.map { it.index })
    scheduler.deliver(1, first.take(3))
    // A second copy of a block it holds, and a block of a piece nobody opened.
    assertIs<TorrentPieceScheduler.Arrival.Unneeded>(scheduler.received(1, 0, 0, data(0, 0)))
    assertIs<TorrentPieceScheduler.Arrival.Unneeded>(scheduler.received(1, 1, 0, data(1, 0)))
    assertEquals(2L * block, discarded)
    // A piece that fails its hash check is fetched again, all of it.
    assertIs<TorrentPieceScheduler.Arrival.Complete>(scheduler.deliver(1, first.drop(3)))
    scheduler.finish(1, 0, CommitOutcome.CORRUPT)
    assertEquals(2L * block + piece, discarded)
    // One that verifies keeps what it received.
    val again = scheduler.request(2, max = 4).requests
    assertEquals(List(4) { 0 }, again.map { it.index })
    assertIs<TorrentPieceScheduler.Arrival.Complete>(scheduler.deliver(2, again))
    scheduler.finish(2, 0, CommitOutcome.VERIFIED)
    assertEquals(2L * block + piece, discarded)
    // A partial piece no connected peer has gives up the blocks it holds.
    val partial = scheduler.request(2, max = 1).requests
    assertEquals(1 to 0, partial.single().let { it.index to it.begin })
    scheduler.deliver(2, partial)
    scheduler.remove(1)
    assertEquals(2L * block + piece, discarded)
    scheduler.remove(2)
    assertEquals(3L * block + piece, discarded)
    // So does one the stopping swarm still assembles.
    scheduler.availability(3, all(2))
    scheduler.deliver(3, scheduler.request(3, max = 1).requests)
    scheduler.close()
    assertEquals(4L * block + piece, discarded)
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
    // Every piece is open and every block asked for: peer 2 asks for peer 1's too, those peer 1
    // would send last first.
    val fast = scheduler.request(2, max = 4).requests
    assertEquals(slow.reversed(), fast)
    scheduler.deliver(2, fast.take(3))
    val cancels = scheduler.poll(1, 0).cancels
    assertEquals(fast.take(3), cancels)
    assertTrue(scheduler.poll(2, 0).cancels.isEmpty())
    // The slow copy of the last block arrives first this time.
    assertIs<TorrentPieceScheduler.Arrival.Complete>(scheduler.deliver(1, slow.take(1)))
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
    assertEquals(first.reversed(), scheduler.request(2, max = 4).requests)
    for (peer in 1..2) scheduler.remove(peer)
    assertEquals(0, budget.allocated)
  }

  @Test
  fun endgame_copiesAFewBlocksAtOnce_thoseTheirOwnerWouldSendLast() = runTest {
    val copies = TorrentPieceScheduler.ENDGAME_DUPLICATES
    // Pieces of four blocks: a torrent the budget holds whole, with more blocks than copies.
    val pieces = copies / 4 + 2
    val budget = TorrentBufferBudget(pieces * piece)
    val scheduler = scheduler(pieces, budget)
    scheduler.availability(1, all(pieces))
    scheduler.availability(2, all(pieces))
    val owed = scheduler.request(1, max = pieces * 4).requests
    assertEquals(pieces * 4, owed.size)
    // Peer 1 sends its blocks in order: copies of its last ones arrive before it gets to them.
    val copied = scheduler.request(2, max = pieces * 4).requests
    assertEquals(owed.reversed().take(copies), copied)
    assertTrue(scheduler.request(2, max = pieces * 4).requests.isEmpty())
    // As copies arrive, peer 2 takes the next ones from the end.
    val last =
      assertIs<TorrentPieceScheduler.Arrival.Complete>(scheduler.deliver(2, copied.take(4)))
    assertFalse(scheduler.finish(2, last.index, CommitOutcome.VERIFIED))
    assertEquals(owed.reversed().drop(copies).take(4),
      scheduler.request(2, max = pieces * 4).requests)
    for (peer in 1..2) scheduler.remove(peer)
    assertEquals(0, budget.allocated)
  }

  @Test
  fun endgame_peerThatOwesBlocksCopiesOnlyThoseOthersAreLateWith() = runTest {
    var now = 0L
    val copies = TorrentPieceScheduler.ENDGAME_DUPLICATES
    val pieces = copies / 4 + 2
    val budget = TorrentBufferBudget(pieces * piece)
    val scheduler = scheduler(pieces, budget, clock = { now })
    scheduler.availability(1, all(pieces))
    scheduler.availability(2, all(pieces))
    assertEquals(4, scheduler.request(1, max = 4, lateAfterMs = 60_000).requests.size)
    val slow = scheduler.request(2, max = pieces * 4, lateAfterMs = 1_000).requests
    assertEquals((pieces - 1) * 4, slow.size)
    // Every block is asked for, but peer 1 still owes its own: it copies none on time.
    assertTrue(scheduler.request(1, max = pieces * 4).requests.isEmpty())
    now = 1_500
    // Late blocks are copied as the pipeline allows, beyond the few on-time copies.
    assertEquals(slow, scheduler.request(1, max = pieces * 4).requests)
    for (peer in 1..2) scheduler.remove(peer)
    assertEquals(0, budget.allocated)
  }

  @Test
  fun endgame_fastPeerCopiesMoreOfADeepQueueThanASlowOne() = runTest {
    val fast = TorrentPieceScheduler.endgameCopies(2.0 * 1024 * 1024, pipeline = 500)
    val slow = TorrentPieceScheduler.endgameCopies(64.0 * 1024, pipeline = 500)
    // Peer 1 owes every block of a torrent the budget holds whole, more than both copy.
    val pieces = (fast + slow) / 4 + 2
    val budget = TorrentBufferBudget(pieces * piece)
    val scheduler = scheduler(pieces, budget)
    for (peer in 1..3) scheduler.availability(peer, all(pieces))
    val owed = scheduler.request(1, max = pieces * 4).requests
    assertEquals(pieces * 4, owed.size)
    // From the end of its queue, a fast peer takes about a second of its rate, a slow one the
    // fewest.
    val fastCopies = scheduler.request(2, max = 500, copies = fast).requests
    assertTrue(fastCopies.size > TorrentPieceScheduler.ENDGAME_DUPLICATES)
    assertEquals(owed.reversed().take(fast), fastCopies)
    assertEquals(owed.reversed().drop(fast).take(slow),
      scheduler.request(3, max = 500, copies = slow).requests)
    for (peer in 1..3) scheduler.remove(peer)
    assertEquals(0, budget.allocated)
  }

  @Test
  fun endgame_copiesWhatTheOwnerWasAskedForLast_afterAPieceIsFreedAgain() = runTest {
    val budget = TorrentBufferBudget(2 * piece)
    val scheduler = scheduler(pieces = 2, budget)
    for (peer in 1..3) scheduler.availability(peer, all(2))
    // Peer 3 opens piece 0, then peer 1 opens piece 1 and takes half of it.
    val rejected = scheduler.request(3, max = 4).requests
    assertEquals(listOf(0, 0, 0, 0), rejected.map { it.index })
    assertEquals(2, scheduler.request(1, max = 2).requests.size)
    // Peer 3 rejects piece 0, whose blocks are free again after piece 1's.
    scheduler.release(3, rejected)
    // In one batch, all due together, peer 1 is asked for the rest of piece 1, then piece 0.
    val owed = scheduler.request(1, max = 6).requests
    assertEquals(listOf(1, 1, 0, 0, 0, 0), owed.map { it.index })
    // Peer 1 sends piece 0's blocks last, though piece 0 opened first: those are copied.
    assertEquals(owed.takeLast(4).reversed(), scheduler.request(2, max = 8, copies = 4).requests)
    for (peer in 1..3) scheduler.remove(peer)
    assertEquals(0, budget.allocated)
  }

  @Test
  fun endgame_ownerGivenLessTime_isNeverDueSoonerForWhatItWasAskedLater() = runTest {
    var now = 0L
    val budget = TorrentBufferBudget(2 * piece)
    val scheduler = scheduler(pieces = 2, budget, clock = { now })
    for (peer in 1..2) scheduler.availability(peer, all(2))
    val first = scheduler.request(1, max = 4, lateAfterMs = 6_000).requests
    assertEquals(listOf(0, 0, 0, 0), first.map { it.index })
    // Measured faster, peer 1 is given less time for what it is asked next, which it still sends
    // after piece 0.
    now = 500
    val second = scheduler.request(1, max = 4, lateAfterMs = 4_000).requests
    assertEquals(listOf(1, 1, 1, 1), second.map { it.index })
    // Its blocks are due last: those copied are the ones it sends last.
    assertEquals(second.reversed(), scheduler.request(2, max = 4, copies = 4).requests)
    // Nor is it late with them while it is not late with piece 0's, sent before them.
    now = 4_600
    assertEquals(0, scheduler.health(limit = 0).lateBlocks)
    for (peer in 1..2) scheduler.remove(peer)
    assertEquals(0, budget.allocated)
  }

  @Test
  fun peerThatGaveItsBlocksBack_isDueByWhatItIsGivenNow() = runTest {
    var now = 0L
    val budget = TorrentBufferBudget(2 * piece)
    val scheduler = scheduler(pieces = 2, budget, clock = { now })
    scheduler.availability(1, all(2))
    assertEquals(4, scheduler.request(1, max = 4, lateAfterMs = 30_000).requests.size)
    // It chokes us without the fast extension: everything it owed goes back.
    scheduler.releasePeer(1)
    now = 100
    assertEquals(4, scheduler.request(1, max = 4, lateAfterMs = 4_000).requests.size)
    now = 4_200
    assertEquals(4, scheduler.health(limit = 0).lateBlocks)
    scheduler.remove(1)
    assertEquals(0, budget.allocated)
  }

  @Test
  fun peerThatSentEverythingItOwed_isDueByWhatItIsGivenNow() = runTest {
    var now = 0L
    val budget = TorrentBufferBudget(2 * piece)
    val scheduler = scheduler(pieces = 2, budget, clock = { now })
    scheduler.availability(1, all(2))
    val first = scheduler.request(1, max = 4, lateAfterMs = 30_000).requests
    assertIs<TorrentPieceScheduler.Arrival.Complete>(scheduler.deliver(1, first))
    assertFalse(scheduler.finish(1, 0, CommitOutcome.VERIFIED))
    now = 100
    assertEquals(listOf(1, 1, 1, 1),
      scheduler.request(1, max = 4, lateAfterMs = 4_000).requests.map { it.index })
    now = 4_200
    assertEquals(4, scheduler.health(limit = 0).lateBlocks)
    scheduler.remove(1)
    assertEquals(0, budget.allocated)
  }

  @Test
  fun endgame_pieceAWebSeedFetches_isNotLateBeforeWhatItsPeerWasAskedEarlier() = runTest {
    var now = 0L
    // Room for the peer's piece, the seed's and the peer's copy of the seed's.
    val budget = TorrentBufferBudget(3 * piece)
    val scheduler = scheduler(pieces = 2, budget, clock = { now })
    scheduler.availability(-1, all(2))
    scheduler.availability(1, all(2))
    assertEquals(listOf(0, 0, 0, 0),
      scheduler.request(1, max = 4, lateAfterMs = 10_000).requests.map { it.index })
    assertEquals(listOf(1), scheduler.claimRun(-1, maxPieces = 2, maxBytes = 2 * piece)
      .map { it.index })
    // In the endgame the peer also fetches the piece the seed fetches, after piece 0.
    assertEquals(listOf(1, 1, 1, 1),
      scheduler.request(1, max = 4, lateAfterMs = 4_000).requests.map { it.index })
    now = 6_500
    assertEquals(0, scheduler.health(limit = 0).lateBlocks)
    scheduler.release(-1, 1)
    for (peer in listOf(-1, 1)) scheduler.remove(peer)
    assertEquals(0, budget.allocated)
  }

  @Test
  fun endgame_copyItsOwnerGaveUp_keepsWhenItWasAskedFor() = runTest {
    var now = 0L
    val budget = TorrentBufferBudget(2 * piece)
    val scheduler = scheduler(pieces = 2, budget, clock = { now })
    for (peer in 1..3) scheduler.availability(peer, all(2))
    assertEquals(8, scheduler.request(1, max = 8).requests.size)
    // Peer 2 copies every block, those peer 1 would send last first, and sends them in that order.
    val copied = scheduler.request(2, max = 8, lateAfterMs = 1_000, copies = 8).requests
    assertEquals(8, copied.size)
    // Peer 1 leaves: peer 2 owes its copies, those it was asked for last still due last.
    scheduler.remove(1)
    assertEquals(copied.takeLast(2).reversed(),
      scheduler.request(3, max = 8, copies = 2).requests)
    // Peer 2 is late with the rest once its copies' deadline passed, not one from when peer 1
    // left.
    now = 1_500
    assertEquals(copied.dropLast(2).sortedWith(compareBy({ it.index }, { it.begin })),
      scheduler.request(3, max = 8, copies = 2).requests)
    for (peer in 2..3) scheduler.remove(peer)
    assertEquals(0, budget.allocated)
  }

  @Test
  fun endgameCopies_peerRate_aboutASecondOfItWithinThePipeline() {
    val fewest = TorrentPieceScheduler.ENDGAME_DUPLICATES
    // A second of 2 MiB/s is 128 blocks.
    assertEquals(128, TorrentPieceScheduler.endgameCopies(2.0 * 1024 * 1024, pipeline = 500))
    // A slow peer, or one whose rate is not known yet, holds the fewest.
    assertEquals(fewest, TorrentPieceScheduler.endgameCopies(64.0 * 1024, pipeline = 500))
    assertEquals(fewest, TorrentPieceScheduler.endgameCopies(0.0, pipeline = 500))
    // Never more than the requests it keeps outstanding.
    assertEquals(250, TorrentPieceScheduler.endgameCopies(100.0 * 1024 * 1024, pipeline = 250))
    assertEquals(8, TorrentPieceScheduler.endgameCopies(0.0, pipeline = 8))
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
  fun revokedPiece_verifiedAgain_isAnnouncedAgain() = runTest {
    val budget = TorrentBufferBudget(piece)
    val scheduler = scheduler(pieces = 2, budget, verified = all(2))
    val cursor = scheduler.haveCursor()
    scheduler.revoke(1)
    // Peers that heard of the piece forget it; none is told it has it while it is gone.
    val revoked = scheduler.poll(1, cursor)
    assertContentEquals(intArrayOf(1), revoked.revoked)
    assertNull(revoked.haves)
    scheduler.verified(1)
    val again = scheduler.poll(1, revoked.haveCursor)
    assertContentEquals(intArrayOf(1), again.haves)
    assertNull(again.revoked)
    // A peer that polls across both hears it forget the piece and have it again.
    val across = scheduler.poll(2, cursor)
    assertContentEquals(intArrayOf(1), across.revoked)
    assertContentEquals(intArrayOf(1), across.haves)
  }

  @Test
  fun haveLog_revokedAndVerifiedAgainOften_outgrowsTheTorrent() = runTest {
    val budget = TorrentBufferBudget(piece)
    val scheduler = scheduler(pieces = 1, budget, verified = all(1))
    val cursor = scheduler.haveCursor()
    repeat(40) {
      scheduler.revoke(0)
      scheduler.verified(0)
    }
    val poll = scheduler.poll(1, cursor)
    assertContentEquals(intArrayOf(0), poll.haves)
    assertContentEquals(intArrayOf(0), poll.revoked)
  }

  @Test
  fun health_pieceEveryHolderRefused_isMissing() = runTest {
    val budget = TorrentBufferBudget(2 * piece)
    val scheduler = scheduler(pieces = 2, budget)
    scheduler.availability(1, all(2))
    scheduler.availability(2, booleanArrayOf(true, false))
    assertEquals(0, scheduler.health(limit = 4).missingCount)
    // Piece 1 has one holder, which refuses it; piece 0 still has a holder that sends it.
    scheduler.refusing(1, setOf(0, 1))
    val health = scheduler.health(limit = 4)
    assertEquals(1, health.missingCount)
    assertContentEquals(intArrayOf(1), health.missing)
    scheduler.refusing(2, setOf(0))
    assertEquals(2, scheduler.health(limit = 4).missingCount)
    // A peer that lifts its refusal, or leaves, takes it with it.
    scheduler.refusing(2, emptySet())
    assertEquals(1, scheduler.health(limit = 4).missingCount)
    scheduler.refusing(1, emptySet())
    assertEquals(0, scheduler.health(limit = 4).missingCount)
    scheduler.refusing(1, setOf(1))
    scheduler.remove(1)
    scheduler.availability(1, all(2))
    assertEquals(0, scheduler.health(limit = 4).missingCount)
    scheduler.remove(1)
    scheduler.remove(2)
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
  fun setAsidePiece_isNeverFetchedNorWanted() = runTest {
    val budget = TorrentBufferBudget(piece)
    val scheduler = scheduler(pieces = 1, budget)
    scheduler.availability(1, all(1))
    scheduler.verified(0)
    scheduler.setAside(0)
    assertFalse(scheduler.isVerified(0))
    assertFalse(scheduler.isWanted(0))
    assertFalse(scheduler.poll(1, 0).wanted)
    assertTrue(scheduler.request(1, max = 4).requests.isEmpty())
    scheduler.remove(1)
    assertEquals(0, budget.allocated)
  }

  @Test
  fun setAside_openPiece_closesItAndCancelsItsRequests() = runTest {
    val budget = TorrentBufferBudget(piece)
    val scheduler = scheduler(pieces = 1, budget)
    scheduler.availability(1, all(1))
    val requests = scheduler.request(1, max = 4).requests
    assertEquals(4, requests.size)
    scheduler.setAside(0)
    assertEquals(0, budget.allocated)
    assertEquals(requests, scheduler.poll(1, 0).cancels)
    assertFalse(scheduler.isWanted(0))
    assertTrue(scheduler.request(1, max = 4).requests.isEmpty())
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
