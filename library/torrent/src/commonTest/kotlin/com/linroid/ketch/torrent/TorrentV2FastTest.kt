package com.linroid.ketch.torrent

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * A v2 swarm loop against scripted peers that negotiate the Fast extension (BEP 6), on v2
 * connections and on a hybrid's connections to v1 peers.
 */
@OptIn(ExperimentalAtomicApi::class)
class TorrentV2FastTest {
  // Pieces of 32 KiB: a spans pieces 0 and 1, b is piece 2, c is piece 3.
  private val files = listOf("a" to 40_000, "b" to 5, "c" to 20_000)
  private val pure = TorrentV2Fixture.build(files)
  private val hybrid = TorrentV2Fixture.build(files, hybrid = true)

  // Enough pieces that the canonical allowed-fast set (10) leaves some out.
  private val large = TorrentV2Fixture.build(listOf("a" to 40 * 16_384), pieceLength = 16_384)
  private val largeHybrid = TorrentV2Fixture.build(
    listOf("a" to 20 * 16_384 + 5, "b" to 20 * 16_384), pieceLength = 16_384, hybrid = true)

  private val v1 = PeerIdentityHandshake.Mode.V1
  private val v2 = PeerIdentityHandshake.Mode.V2
  private val interested = PeerMessage.Control(PeerMessage.Signal.INTERESTED)
  private val unchoke = PeerMessage.Control(PeerMessage.Signal.UNCHOKE)
  private val choke = PeerMessage.Control(PeerMessage.Signal.CHOKE)

  /** Downloads over a pure v2 connection, then over a hybrid's v1 connection. */
  private val routes = listOf(pure to v2, hybrid to v1)

  private fun fastTest(block: suspend () -> Unit) = runTest {
    withContext(Dispatchers.Default) { withTimeout(30_000) { block() } }
  }

  /** One swarm loop over [fixture], ending once complete unless [seeding]. */
  private suspend fun rig(
    fixture: TorrentV2Fixture,
    seeding: Boolean = false,
    test: suspend TorrentV2SwarmRig.() -> Unit,
  ) {
    val rig = TorrentV2SwarmRig(fixture, onCompleted = { seeding })
    try {
      rig.store.initialize()
      rig.test()
      rig.checkReleased()
    } finally {
      rig.close()
    }
  }

  /** The block [request] names, as a peer of [mode] sends it: v1 blocks include padding. */
  private fun TorrentV2Fixture.block(
    mode: PeerIdentityHandshake.Mode,
    request: PeerMessage.Request,
  ): PeerMessage.Piece {
    val piece = if (mode == v1) v1Piece(request.index) else v2Piece(request.index)
    return PeerMessage.Piece(request.index, request.begin,
      piece.copyOfRange(request.begin, request.begin + request.length))
  }

  @Test
  fun chokingPeer_servesAllowedFastPiecesAfterHaveAll() = fastTest {
    for ((fixture, mode) in routes) rig(fixture) {
      run { loop ->
        val peer = connect(1, mode, fast = true)
        // We have nothing yet, so the loop says so without a bitfield.
        assertEquals(PeerMessage.HaveNone, peer.next())
        peer.send(PeerMessage.HaveAll)
        peer.send(PeerMessage.Suggest(1))
        for (index in 0 until pieceCount) peer.send(PeerMessage.AllowedFast(index))
        // It never unchokes the loop.
        coroutineScope {
          val server = launch {
            while (true) {
              val message = peer.next()
              if (message is PeerMessage.Request) peer.send(fixture.block(mode, message))
            }
          }
          loop.await()
          server.cancelAndJoin()
        }
        assertTrue(store.completed())
      }
    }
  }

  @Test
  fun rejectedPiece_isRequestedFromAnotherPeerWithoutDroppingTheFirst() = fastTest {
    rig(pure) {
      run { loop ->
        val rejected = CompletableDeferred<Unit>()
        // The first peer refuses piece 0, which the second peer alone has, and serves the rest.
        val first = connect(1, fast = true)
        assertEquals(PeerMessage.HaveNone, first.next())
        first.send(PeerMessage.HaveAll)
        first.send(unchoke)
        coroutineScope {
          val servers = launch {
            launch {
              while (true) {
                val message = first.next()
                if (message !is PeerMessage.Request) continue
                if (message.index == 0) {
                  first.send(PeerMessage.Reject(message.index, message.begin, message.length))
                  rejected.complete(Unit)
                } else first.send(pure.block(v2, message))
              }
            }
            rejected.await()
            delay(200)
            // A reject is no fault of the peer: it stays.
            assertFalse(first.closed.isCompleted)
            val second = connect(2, fast = true)
            second.send(PeerMessage.Bitfield(pieceBitfield(BooleanArray(pieceCount) { it == 0 })))
            second.send(unchoke)
            while (true) {
              val message = second.next()
              if (message is PeerMessage.Request) second.send(pure.block(v2, message))
            }
          }
          loop.await()
          servers.cancelAndJoin()
        }
        assertTrue(store.completed())
      }
    }
  }

  @Test
  fun chokeWithRejects_requestsThePieceAgainOnUnchoke() = fastTest {
    for ((fixture, mode) in routes) rig(fixture) {
      run { loop ->
        val peer = connect(1, mode, fast = true)
        assertEquals(PeerMessage.HaveNone, peer.next())
        peer.send(PeerMessage.HaveAll)
        peer.send(unchoke)
        var rejects = 0
        coroutineScope {
          val server = launch {
            var choked = false
            var choking = false
            while (true) {
              val message = peer.next()
              if (message !is PeerMessage.Request) continue
              if (!choking) {
                // The first request makes it choke the loop and reject what it asked for.
                choking = true
                choked = true
                peer.send(choke)
                launch {
                  delay(300)
                  choked = false
                  peer.send(unchoke)
                }
              }
              if (choked) {
                rejects++
                peer.send(PeerMessage.Reject(message.index, message.begin, message.length))
              } else peer.send(fixture.block(mode, message))
            }
          }
          loop.await()
          server.cancelAndJoin()
        }
        assertTrue(rejects > 0)
        assertTrue(store.completed())
      }
    }
  }

  @Test
  fun chokeWithoutRejects_releasesRequestsAndToleratesLateRejects() = fastTest {
    rig(pure) {
      run { loop ->
        // It chokes at the first request and never answers; a late reject follows our cancel.
        val first = connect(1, fast = true, fastRejectGraceMs = 300)
        assertEquals(PeerMessage.HaveNone, first.next())
        first.send(PeerMessage.HaveAll)
        first.send(unchoke)
        val canceled = CompletableDeferred<PeerMessage.Cancel>()
        coroutineScope {
          val servers = launch {
            launch {
              var choked = false
              while (true) {
                val message = first.next()
                if (message is PeerMessage.Request && !choked) {
                  choked = true
                  first.send(choke)
                }
                if (message is PeerMessage.Cancel) {
                  first.send(PeerMessage.Reject(message.index, message.begin, message.length))
                  canceled.complete(message)
                }
              }
            }
            canceled.await()
            delay(200)
            assertFalse(first.closed.isCompleted)
            // Its pieces go to another peer.
            val second = connect(2)
            second.send(PeerMessage.Bitfield(pieceBitfield(BooleanArray(pieceCount) { true })))
            second.send(unchoke)
            while (true) {
              val message = second.next()
              if (message is PeerMessage.Request) second.send(pure.block(v2, message))
            }
          }
          loop.await()
          servers.cancelAndJoin()
        }
        assertTrue(store.completed())
      }
    }
  }

  @Test
  fun fastMessageWithoutNegotiation_closesThePeer() = fastTest {
    rig(pure) {
      run {
        val peer = connect(1)
        peer.send(PeerMessage.HaveAll)
        peer.closed.await()
      }
    }
  }

  @Test
  fun seeder_sendsHaveAllAndServesAllowedFastWhileRejectingOthers() = fastTest {
    for ((fixture, mode) in listOf(large to v2, largeHybrid to v1)) rig(fixture, seeding = true) {
      fixture.seed(store)
      run {
        val peer = connect(1, mode, fast = true)
        assertEquals(PeerMessage.HaveAll, peer.next())
        // The canonical set of the swarm the connection joined, for its /24.
        val offered = allowedFastOffer("192.0.2.1", fixture.document.identity.wireTag(mode),
          pieceCount)
        assertEquals(ALLOWED_FAST_COUNT, offered.size)
        for (index in offered) assertEquals(PeerMessage.AllowedFast(index), peer.next())
        // Choked and not even interested, it gets the allowed-fast piece and a reject.
        val other = (0 until pieceCount).first { it !in offered }
        peer.send(PeerMessage.Request(offered[0], 0, 16_384))
        peer.send(PeerMessage.Request(other, 0, 16_384))
        val replies = mutableListOf<PeerMessage>()
        while (replies.none { it is PeerMessage.Piece } ||
          replies.none { it is PeerMessage.Reject }) replies += peer.next()
        assertEquals(PeerMessage.Reject(other, 0, 16_384), replies.single {
          it is PeerMessage.Reject
        })
        val piece = assertIs<PeerMessage.Piece>(replies.single { it is PeerMessage.Piece })
        assertEquals(offered[0], piece.index)
        assertContentEquals(fixture.block(mode, PeerMessage.Request(offered[0], 0, 16_384)).bytes,
          piece.bytes)
      }
    }
  }

  @Test
  fun choke_rejectsQueuedRequestsAfterTheChoke() = fastTest {
    rig(large) {
      // Still downloading, and uploading while it does.
      large.seed(store, 0 until 3)
      policy.store(TorrentUploadPolicy.WHILE_DOWNLOADING)
      // The shared bucket starts empty and refills a byte a second: requests wait in the queue.
      global.set(1)
      assertEquals(0L, global.requestDelay(16_384, TorrentRateLimiter()))
      run {
        val peer = connect(1, fast = true)
        assertIs<PeerMessage.Bitfield>(peer.next())
        peer.send(interested)
        assertEquals(unchoke, peer.next { it is PeerMessage.Control })
        val requests = (0 until 3).map { PeerMessage.Request(it, 0, 16_384) }
        requests.forEach(peer::send)
        delay(300)
        // Uploading stops: the peer is choked, and hears about every request it still waits for,
        // allowed fast or not, after the CHOKE.
        policy.store(TorrentUploadPolicy.DISABLED)
        policyChanged()
        assertEquals(choke, peer.next { it is PeerMessage.Control || it is PeerMessage.Reject })
        val rejected = requests.map {
          assertIs<PeerMessage.Reject>(peer.next { message -> message is PeerMessage.Reject })
        }
        assertEquals(requests.map { PeerMessage.Reject(it.index, it.begin, it.length) }, rejected)
      }
    }
  }
}
