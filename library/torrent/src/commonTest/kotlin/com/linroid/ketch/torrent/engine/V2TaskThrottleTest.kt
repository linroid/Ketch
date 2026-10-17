package com.linroid.ketch.torrent.engine

import com.linroid.ketch.torrent.TorrentConfig
import com.linroid.ketch.torrent.TorrentDiscoveryPrivacy
import com.linroid.ketch.torrent.TorrentUploadPolicy
import com.linroid.ketch.torrent.TorrentV2Fixture
import com.linroid.ketch.torrent.crypto.torrentRandomBytes
import com.linroid.ketch.torrent.metainfo.InfoHash
import com.linroid.ketch.torrent.net.PeerEndpoint
import com.linroid.ketch.torrent.storage.torrentFileSystem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okio.FileSystem
import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A v2 owner pays the task limits the run that follows it set last, not the ones it was opened
 * with: a run that takes over a seeding owner brings its own.
 */
@OptIn(ExperimentalAtomicApi::class)
class V2TaskThrottleTest {
  private val fixture = TorrentV2Fixture.build(listOf("a" to 40_000, "b" to 20_000))

  @Test
  fun setTaskThrottle_replacesTheLimitsTheOwnerWasOpenedWith() = runTest {
    withContext(Dispatchers.Default) {
      withTimeout(30_000) {
        val root = FileSystem.SYSTEM_TEMPORARY_DIRECTORY /
          "ketch-v2-throttle-${InfoHash.fromBytes(torrentRandomBytes(20)).hex}"
        torrentFileSystem.createDirectories(root)
        val seeder = KotlinTorrentEngine(TorrentConfig(dhtEnabled = false,
          uploadPolicy = TorrentUploadPolicy.SEED_AFTER_COMPLETION), listenHost = "127.0.0.1")
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
          val opened = AtomicLong(0)
          val replaced = AtomicLong(0)
          val output = root / "leech"
          val session = leecher.addV2Task(TorrentV2TaskSpec("leech", fixture.document,
            output.toString(), privacy = TorrentDiscoveryPrivacy.PUBLIC,
            throttle = { opened.addAndFetch(it.toLong()) }, discover = { peers ->
              peers.send(PeerEndpoint("127.0.0.1", seeder.listenPort))
              awaitCancellation()
            }))
          session.setTaskThrottle { replaced.addAndFetch(it.toLong()) }
          session.resume()
          assertEquals(TorrentSessionState.FINISHED, session.state.first {
            it == TorrentSessionState.FINISHED || it == TorrentSessionState.STOPPED
          }, session.failure.value?.stackTraceToString())
          assertContentEquals(fixture.payloads[0],
            torrentFileSystem.read(output / "a") { readByteArray() })
          assertTrue(replaced.load() >= 60_000, "paid ${replaced.load()} bytes")
          assertEquals(0, opened.load())
          leecher.removeTorrent(fixture.document.info.hash.hex)
          seeder.removeTorrent(fixture.document.info.hash.hex)
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
