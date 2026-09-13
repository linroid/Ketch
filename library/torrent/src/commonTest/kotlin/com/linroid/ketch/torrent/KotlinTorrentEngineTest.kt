package com.linroid.ketch.torrent

import com.linroid.ketch.core.engine.HttpEngine
import com.linroid.ketch.core.engine.ServerInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okio.FileSystem
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFalse
import kotlin.test.assertNotNull

class KotlinTorrentEngineTest {
  @OptIn(ExperimentalAtomicApi::class)
  @Test
  fun liveTrackerControlsFollowSessionPauseAndResume() = runTest {
    withContext(Dispatchers.Default) {
      withTimeout(15_000) {
        val requests = MutableStateFlow<List<String>>(emptyList())
        val announced = CompletableDeferred<Unit>()
        val reply = CompletableDeferred<Unit>()
        var scrapeHash = ByteArray(0)
        val http = TorrentHttp(object : HttpEngine {
          override suspend fun head(url: String, headers: Map<String, String>): ServerInfo =
            error("Unused")
          override suspend fun download(
            url: String,
            range: LongRange?,
            headers: Map<String, String>,
            onData: suspend (ByteArray) -> Unit,
          ) {
            requests.value += url
            if ("/scrape?" in url) {
              onData(Bencode.encode(mapOf("files" to mapOf(scrapeHash to mapOf(
                "complete" to 11L, "downloaded" to 22L, "incomplete" to 33L
              )))))
              return
            }
            announced.complete(Unit)
            reply.await()
            onData(Bencode.encode(mapOf("interval" to 3600L, "min interval" to 60L,
              "peers" to ByteArray(0))))
          }
          override fun close() = Unit
        })
        val clock = AtomicLong(0)
        val bytes = byteArrayOf(1, 2, 3, 4)
        val metadata = TorrentMetadata.fromBencode(Bencode.encode(mapOf(
          "announce" to "https://tracker/announce", "info" to mapOf("name" to "seed",
            "length" to 4L, "piece length" to 4L, "pieces" to sha1Digest(bytes))
        )))
        scrapeHash = metadata.infoHash.toBytes()
        val root = FileSystem.SYSTEM_TEMPORARY_DIRECTORY /
          "ketch-tracker-control-${InfoHash.fromBytes(torrentRandomBytes(20)).hex}"
        FileSystem.SYSTEM.createDirectories(root)
        FileSystem.SYSTEM.write(root / "seed") { write(bytes) }
        val spec = TorrentTaskSpec("tracker-control", metadata,
          (root / "seed").toString(), emptySet())
        val limit = sessionStateWeight(spec).toInt()
        val engine = KotlinTorrentEngine(TorrentConfig(dhtEnabled = false,
          maxMetadataBytes = 64 * 1024,
          maxSessionStateBytes = limit,
          uploadPolicy = TorrentUploadPolicy.SEED_AFTER_COMPLETION), http = http,
          nowMs = { clock.load() })
        try {
          engine.start()
          val session = engine.addTask(spec)
          assertFalse(session.scrapeTracker())
          assertFalse(session.reannounceTrackers())
          session.resume()
          announced.await()
          session.trackerStatus.first {
            it.singleOrNull()?.outcome == TrackerStatus.Outcome.ANNOUNCING
          }
          assertEquals(limit, engine.admittedSessionBytes)
          reply.complete(Unit)
          session.trackerStatus.first {
            it.singleOrNull()?.outcome == TrackerStatus.Outcome.SUCCEEDED
          }
          assertFalse(session.reannounceTrackers())
          assertTrue(session.scrapeTracker())
          assertEquals(TrackerScrape(11, 22, 33), session.trackerStatus.value.single().scrape)
          assertFalse(session.scrapeTracker())
          clock.store(60_000)
          assertTrue(session.reannounceTrackers())
          assertEquals(2L, session.trackerStatus.value.single().attempts)
          session.pause()
          assertFalse(session.scrapeTracker())
          assertFalse(session.reannounceTrackers())
          assertTrue(session.trackerStatus.value.isEmpty())
          assertEquals(1, requests.value.count { "event=stopped" in it })
          session.resume()
          session.trackerStatus.first {
            it.singleOrNull()?.outcome == TrackerStatus.Outcome.SUCCEEDED
          }
          assertEquals(2, requests.value.count { "event=started" in it })
        } finally {
          engine.stop()
          FileSystem.SYSTEM.deleteRecursively(root, mustExist = false)
        }
        assertEquals(0, engine.admittedSessionBytes)
      }
    }
  }

  @Test
  fun restoredTrackerOverrideReplacesMetainfoTrackersAndPersistsAgain() = runTest {
    withContext(Dispatchers.Default) {
      withTimeout(15_000) {
        for (trackerOverride in listOf(listOf(listOf("https://new/announce")), emptyList())) {
          val requests = MutableStateFlow<List<String>>(emptyList())
          val http = TorrentHttp(object : HttpEngine {
            override suspend fun head(url: String, headers: Map<String, String>): ServerInfo =
              error("Unused")
            override suspend fun download(
              url: String,
              range: LongRange?,
              headers: Map<String, String>,
              onData: suspend (ByteArray) -> Unit,
            ) {
              requests.value += url
              onData(Bencode.encode(mapOf("interval" to 3600L, "peers" to ByteArray(0))))
            }
            override fun close() = Unit
          })
          val bytes = byteArrayOf(1, 2, 3, 4)
          val metadata = TorrentMetadata.fromBencode(Bencode.encode(mapOf(
            "announce" to "https://old/announce", "info" to mapOf("name" to "seed",
              "length" to 4L, "piece length" to 4L, "pieces" to sha1Digest(bytes), "private" to 1L)
          )))
          val root = FileSystem.SYSTEM_TEMPORARY_DIRECTORY /
            "ketch-tracker-restore-${InfoHash.fromBytes(torrentRandomBytes(20)).hex}"
          FileSystem.SYSTEM.createDirectories(root)
          FileSystem.SYSTEM.write(root / "seed") { write(bytes) }
          val store = TorrentPieceStore(metadata, root / "seed", emptySet(), "tracker-restored")
          store.initialize()
          val checkpoint = store.checkpoint().copy(
            trackerConfiguration = TrackerConfiguration.prepare(trackerOverride)).encode()
          val spec = TorrentTaskSpec("tracker-restored", metadata, (root / "seed").toString(),
            emptySet(), resumeData = checkpoint)
          val limit = sessionStateWeight(spec).toInt()
          val engine = KotlinTorrentEngine(TorrentConfig(dhtEnabled = false,
            maxSessionStateBytes = limit, uploadPolicy = TorrentUploadPolicy.SEED_AFTER_COMPLETION),
            http = http)
          try {
            engine.start()
            val session = engine.addTask(spec)
            session.resume()
            val restoredState = session.state.first {
              it == TorrentSessionState.SEEDING || it == TorrentSessionState.STOPPED
            }
            assertEquals(TorrentSessionState.SEEDING, restoredState,
              session.failure.value?.stackTraceToString())
            if (trackerOverride.isNotEmpty()) {
              session.trackerStatus.first {
                it.singleOrNull()?.outcome == TrackerStatus.Outcome.SUCCEEDED
              }
              assertTrue(requests.value.single().startsWith("https://new/announce?"))
            } else {
              assertFalse(session.reannounceTrackers())
              assertTrue(requests.value.isEmpty())
            }
            assertEquals(limit, engine.admittedSessionBytes)
            session.pause()
            val saved = assertNotNull(TorrentCheckpoint.decode(
              assertNotNull(session.saveResumeData())))
            assertEquals(trackerOverride, assertNotNull(saved.trackerConfiguration).tiers)
          } finally {
            engine.stop()
            FileSystem.SYSTEM.deleteRecursively(root, mustExist = false)
          }
          assertEquals(0, engine.admittedSessionBytes)
        }
      }
    }
  }

  @Test
  fun completedTorrent_acceptsNewIncomingPeerAndSeedsVerifiedBytes() = runTest {
    withContext(Dispatchers.Default) {
      withTimeout(15_000) {
        val bytes = byteArrayOf(8, 9, 10, 11)
        val metadata = TorrentMetadata.fromBencode(Bencode.encode(mapOf("info" to mapOf(
          "name" to "seed", "length" to 4L, "piece length" to 4L, "pieces" to sha1Digest(bytes)
        ))))
        val root = FileSystem.SYSTEM_TEMPORARY_DIRECTORY /
          "ketch-incoming-${InfoHash.fromBytes(torrentRandomBytes(20)).hex}"
        FileSystem.SYSTEM.createDirectories(root)
        FileSystem.SYSTEM.write(root / "seed") { write(bytes) }
        val engine = KotlinTorrentEngine(TorrentConfig(dhtEnabled = false,
          uploadPolicy = TorrentUploadPolicy.SEED_AFTER_COMPLETION))
        val remote = createTorrentNetwork()
        try {
          engine.start()
          val session = engine.addTask(TorrentTaskSpec("seed-task", metadata,
            (root / "seed").toString(), emptySet()))
          session.resume()
          val state = session.state.first {
            it == TorrentSessionState.SEEDING || it == TorrentSessionState.STOPPED
          }
          assertEquals(TorrentSessionState.SEEDING, state, session.failure.value?.stackTraceToString())
          val connection = remote.connect(PeerEndpoint("127.0.0.1", engine.listenPort))
          try {
            val wire = PeerWire(connection, metadata)
            wire.handshake(PeerHandshake(metadata.infoHash, torrentRandomBytes(20), false, false))
            wire.send(PeerMessage.Control(PeerMessage.Signal.INTERESTED))
            while (wire.read() != PeerMessage.Control(PeerMessage.Signal.UNCHOKE)) { }
            wire.send(PeerMessage.Request(0, 0, 4))
            var message = wire.read()
            while (message !is PeerMessage.Piece) message = wire.read()
            assertContentEquals(bytes, message.bytes)
          } finally { connection.close() }
        } finally {
          engine.stop()
          remote.close()
          FileSystem.SYSTEM.deleteRecursively(root, mustExist = false)
        }
      }
    }
  }
}
