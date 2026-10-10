package com.linroid.ketch.torrent

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okio.FileSystem
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.incrementAndFetch
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * One Kotlin engine seeds a hybrid torrent and another downloads it, changing its files while
 * the transfer runs: the change never costs a connection.
 */
@OptIn(ExperimentalAtomicApi::class)
class V2SelectionTwoEngineTest {
  // Pieces of 32 KiB: a spans pieces 0 and 1, b is piece 2, c is piece 3; padding between.
  private val fixture = TorrentV2Fixture.build(listOf("a" to 40_000, "b" to 5, "c" to 20_000),
    hybrid = true)

  /** Counts the connections the seed's engine accepts. */
  private class CountingNetwork(private val delegate: TorrentNetwork) : TorrentNetwork by delegate {
    val accepted = AtomicInt(0)

    override suspend fun listen(local: PeerEndpoint): TorrentListener {
      val listener = delegate.listen(local)
      return object : TorrentListener by listener {
        override suspend fun accept(): TorrentConnection =
          listener.accept().also { accepted.incrementAndFetch() }
      }
    }
  }

  @Test
  fun liveExpandThenShrink_hybrid_completesWithoutReconnecting() = runTest {
    withContext(Dispatchers.Default) {
      withTimeout(30_000) {
        val root = FileSystem.SYSTEM_TEMPORARY_DIRECTORY /
          "ketch-v2-selection-${InfoHash.fromBytes(torrentRandomBytes(20)).hex}"
        torrentFileSystem.createDirectories(root)
        val network = CountingNetwork(createTorrentNetwork())
        val seeder = KotlinTorrentEngine(TorrentConfig(dhtEnabled = false,
          uploadPolicy = TorrentUploadPolicy.SEED_AFTER_COMPLETION), rawNetwork = network,
          listenHost = "127.0.0.1")
        val leecher = KotlinTorrentEngine(TorrentConfig(dhtEnabled = false))
        try {
          seeder.start()
          leecher.start()
          val seedOutput = root / "seed"
          val seed = seeder.addV2Task(TorrentV2TaskSpec("seed", fixture.document,
            seedOutput.toString(), checkpoint = fixture.preseed(seedOutput, "seed"),
            privacy = TorrentDiscoveryPrivacy.PUBLIC))
          seed.resume()
          assertEquals(TorrentSessionState.SEEDING, seed.state.first {
            it == TorrentSessionState.SEEDING || it == TorrentSessionState.STOPPED
          }, seed.failure.value?.stackTraceToString())
          // A hybrid's file IDs skip its padding files.
          val (a, b, c) = TorrentOutputMapping.from(fixture.document).files.map { it.id }
          val output = root / "leech"
          // No block is requested until the selection changed twice.
          val gate = CompletableDeferred<Unit>()
          val session = leecher.addV2Task(TorrentV2TaskSpec("leech", fixture.document,
            output.toString(), selected = setOf(a), privacy = TorrentDiscoveryPrivacy.PUBLIC,
            throttle = { gate.await() }, discover = { peers ->
              peers.send(PeerEndpoint("127.0.0.1", seeder.listenPort))
              awaitCancellation()
            }))
          session.resume()
          while (network.accepted.load() == 0) delay(10)
          assertTrue(session.changeSelection(setOf(a, b, c)))
          assertTrue(session.changeSelection(setOf(a, c)))
          assertEquals(60_000L, session.totalBytes)
          gate.complete(Unit)
          assertEquals(TorrentSessionState.FINISHED, session.state.first {
            it == TorrentSessionState.FINISHED || it == TorrentSessionState.STOPPED
          }, session.failure.value?.stackTraceToString())
          assertContentEquals(fixture.payloads[0],
            torrentFileSystem.read(output / "a") { readByteArray() })
          assertContentEquals(fixture.payloads[2],
            torrentFileSystem.read(output / "c") { readByteArray() })
          assertFalse(session.fileProgress().containsKey(b))
          // Both changes went over the one connection the seed accepted.
          assertEquals(1, network.accepted.load())
          leecher.removeTorrent(fixture.document.info.hash.hex)
          seeder.removeTorrent(fixture.document.info.hash.hex)
          assertEquals(0, leecher.admittedSessionBytes)
          assertEquals(0, seeder.admittedSessionBytes)
        } finally {
          leecher.stop()
          seeder.stop()
          torrentFileSystem.deleteRecursively(root, mustExist = false)
        }
        assertEquals(0, leecher.allocatedExchangeBytes)
        assertEquals(0, seeder.allocatedExchangeBytes)
      }
    }
  }
}
