package com.linroid.ketch.torrent

import com.linroid.ketch.api.Destination
import com.linroid.ketch.api.DownloadRequest
import com.linroid.ketch.api.DownloadState
import com.linroid.ketch.core.Ketch
import com.linroid.ketch.core.engine.HttpEngine
import com.linroid.ketch.core.engine.ServerInfo
import com.linroid.ketch.core.engine.SourceResumeState
import com.linroid.ketch.core.task.TaskControl
import com.linroid.ketch.core.task.TaskRecord
import com.linroid.ketch.core.task.TaskState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import okio.FileSystem
import okio.IOException
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

@OptIn(ExperimentalAtomicApi::class)
class KotlinRuntimePrivateTest {
  @Test
  fun trackerFailover_closesOldPeersBeforeConnectingNewAndNeverStartsDhtOrPex() = runTest {
    withContext(Dispatchers.Default) {
      withTimeout(15_000) {
        coroutineScope {
          val bytes = byteArrayOf(1, 2, 3, 4)
          val metadata = TorrentMetadata.fromBencode(Bencode.encode(mapOf(
            "announce-list" to listOf(listOf("http://a/announce"), listOf("http://b/announce")),
            "info" to mapOf("name" to "private", "private" to 1L, "length" to 4L,
              "piece length" to 4L, "pieces" to sha1Digest(bytes))
          )))
          val root = FileSystem.SYSTEM_TEMPORARY_DIRECTORY /
            "ketch-private-${InfoHash.fromBytes(torrentRandomBytes(20)).hex}"
          val clock = AtomicLong(0)
          val oldClosed = AtomicBoolean(false)
          val udpSockets = AtomicInt(0)
          val transport = createTorrentNetwork()
          val first = transport.listen(PeerEndpoint("127.0.0.1", 0))
          val second = transport.listen(PeerEndpoint("127.0.0.1", 0))
          val network = object : TorrentNetwork by transport {
            override suspend fun bindUdp(local: PeerEndpoint): TorrentDatagramSocket {
              udpSockets.fetchAndAdd(1)
              return transport.bindUdp(local)
            }
            override suspend fun connect(remote: PeerEndpoint): TorrentConnection {
              if (remote == second.local) assertTrue(oldClosed.load())
              val connection = transport.connect(remote)
              return object : TorrentConnection by connection {
                override fun close() {
                  if (remote == first.local) oldClosed.store(true)
                  connection.close()
                }
              }
            }
          }
          val http = object : HttpEngine {
            override suspend fun head(url: String, headers: Map<String, String>): ServerInfo =
              error("unused")
            override suspend fun download(url: String, range: LongRange?,
              headers: Map<String, String>, onData: suspend (ByteArray) -> Unit) {
              if (url.startsWith("http://a/") && clock.load() > 0) throw IOException("Unavailable")
              val endpoint = if (url.startsWith("http://a/")) first.local else second.local
              onData(Bencode.encode(mapOf("interval" to 30L, "peers" to listOf(
                mapOf("ip" to endpoint.host, "port" to endpoint.port.toLong())
              ))))
            }
            override fun close() = Unit
          }
          val servers = listOf(first, second).mapIndexed { index, listener -> launch {
            val connection = listener.accept()
            try {
              val wire = PeerWire(connection, metadata)
              wire.handshake(PeerHandshake(metadata.infoHash, torrentRandomBytes(20), true, false))
              val extension = wire.read() as PeerMessage.Extended
              assertEquals(0, extension.id)
              val handshake = Bencode.parse(extension.payload)
              assertNull(handshake["m"]?.get("ut_pex"))
              // A private torrent never offers its info dictionary, even while uploading.
              assertNull(handshake["m"]?.get("ut_metadata"))
              assertNull(handshake["metadata_size"])
              if (index == 0) clock.store(60_000) else {
                wire.send(PeerMessage.Bitfield(byteArrayOf(128.toByte())))
                wire.send(PeerMessage.Control(PeerMessage.Signal.UNCHOKE))
              }
              while (true) {
                if (wire.read() is PeerMessage.Request) wire.send(PeerMessage.Piece(0, 0, bytes))
              }
            } catch (_: IOException) {
              // Failover/completion closes the corresponding peer.
            } finally { connection.close() }
          } }
          val engine = KotlinTorrentEngine(TorrentConfig(dhtEnabled = true,
            uploadPolicy = TorrentUploadPolicy.WHILE_DOWNLOADING), network,
            TorrentHttp(http), nowMs = { clock.load() })
          try {
            engine.start()
            val session = engine.addTask(TorrentTaskSpec("private-task", metadata,
              (root / "output").toString(), emptySet()))
            session.resume()
            assertEquals(TorrentSessionState.FINISHED, session.state.first {
              it == TorrentSessionState.FINISHED || it == TorrentSessionState.STOPPED
            }, session.failure.value?.stackTraceToString())
            assertTrue(oldClosed.load())
            assertEquals(0, udpSockets.load())
          } finally {
            engine.stop()
            servers.forEach { it.cancelAndJoin() }
            network.close()
            torrentFileSystem.deleteRecursively(root, mustExist = false)
          }
        }
      }
    }
  }

  @Test
  fun selectionChange_privateTorrent_bindsNoUdp() = runTest {
    withContext(Dispatchers.Default) {
      withTimeout(15_000) {
        coroutineScope {
          val payloads = listOf(byteArrayOf(1, 2, 3, 4), byteArrayOf(5, 6, 7, 8))
          val metadata = TorrentMetadata.fromBencode(Bencode.encode(mapOf(
            "announce" to "http://a/announce",
            "info" to mapOf("name" to "private", "private" to 1L, "piece length" to 4L,
              "pieces" to sha1Digest(payloads[0]) + sha1Digest(payloads[1]),
              "files" to listOf(
                mapOf("length" to 4L, "path" to listOf("first")),
                mapOf("length" to 4L, "path" to listOf("second"))))
          )))
          val root = FileSystem.SYSTEM_TEMPORARY_DIRECTORY /
            "ketch-private-selection-${InfoHash.fromBytes(torrentRandomBytes(20)).hex}"
          val udpSockets = AtomicInt(0)
          val connects = AtomicInt(0)
          val transport = createTorrentNetwork()
          val seeder = transport.listen(PeerEndpoint("127.0.0.1", 0))
          val network = object : TorrentNetwork by transport {
            override suspend fun bindUdp(local: PeerEndpoint): TorrentDatagramSocket {
              udpSockets.fetchAndAdd(1)
              return transport.bindUdp(local)
            }
            override suspend fun connect(remote: PeerEndpoint): TorrentConnection {
              connects.fetchAndAdd(1)
              return transport.connect(remote)
            }
          }
          val http = object : HttpEngine {
            override suspend fun head(url: String, headers: Map<String, String>): ServerInfo =
              error("unused")
            override suspend fun download(url: String, range: LongRange?,
              headers: Map<String, String>, onData: suspend (ByteArray) -> Unit) {
              onData(Bencode.encode(mapOf("interval" to 30L, "peers" to listOf(
                mapOf("ip" to seeder.local.host, "port" to seeder.local.port.toLong())
              ))))
            }
            override fun close() = Unit
          }
          val server = launch {
            val connection = seeder.accept()
            try {
              val wire = PeerWire(connection, metadata)
              wire.handshake(PeerHandshake(metadata.infoHash, torrentRandomBytes(20), false, false))
              wire.send(PeerMessage.Bitfield(byteArrayOf(0xC0.toByte())))
              wire.send(PeerMessage.Control(PeerMessage.Signal.UNCHOKE))
              while (true) {
                val message = wire.read()
                if (message is PeerMessage.Request) {
                  wire.send(PeerMessage.Piece(message.index, 0, payloads[message.index]))
                }
              }
            } catch (_: IOException) {
              // The engine stopped.
            } finally { connection.close() }
          }
          val engine = KotlinTorrentEngine(TorrentConfig(dhtEnabled = true,
            uploadPolicy = TorrentUploadPolicy.SEED_AFTER_COMPLETION), network, TorrentHttp(http))
          try {
            engine.start()
            val session = engine.addTask(TorrentTaskSpec("private-selection", metadata,
              (root / "private").toString(), setOf(0)))
            session.resume()
            assertEquals(TorrentSessionState.SEEDING, session.state.first {
              it == TorrentSessionState.SEEDING || it == TorrentSessionState.STOPPED
            }, session.failure.value?.stackTraceToString())
            assertTrue(session.changeSelection(setOf("0", "1")))
            session.downloadedBytes.first { it == 8L }
            assertEquals(TorrentSessionState.SEEDING, session.state.first {
              it == TorrentSessionState.SEEDING || it == TorrentSessionState.STOPPED
            }, session.failure.value?.stackTraceToString())
            assertEquals(0, udpSockets.load())
            assertEquals(1, connects.load())
          } finally {
            engine.stop()
            server.cancelAndJoin()
            network.close()
            torrentFileSystem.deleteRecursively(root, mustExist = false)
          }
          assertEquals(0, engine.allocatedExchangeBytes)
        }
      }
    }
  }

  @Test
  fun restoreSeeding_policyOff_bindsNothing() = runTest {
    withContext(Dispatchers.Default) {
      withTimeout(15_000) {
        val bytes = byteArrayOf(1, 2, 3, 4)
        val metainfo = Bencode.encode(mapOf("announce" to "http://a/announce",
          "info" to mapOf("name" to "private", "private" to 1L, "length" to 4L,
            "piece length" to 4L, "pieces" to sha1Digest(bytes))))
        val metadata = TorrentMetadata.fromBencode(metainfo)
        val root = FileSystem.SYSTEM_TEMPORARY_DIRECTORY /
          "ketch-private-restore-${InfoHash.fromBytes(torrentRandomBytes(20)).hex}"
        torrentFileSystem.createDirectories(root)
        torrentFileSystem.write(root / "private") { write(bytes) }
        val sockets = AtomicInt(0)
        val requests = AtomicInt(0)
        val transport = createTorrentNetwork()
        val network = object : TorrentNetwork by transport {
          override suspend fun bindUdp(local: PeerEndpoint): TorrentDatagramSocket {
            sockets.fetchAndAdd(1)
            return transport.bindUdp(local)
          }
          override suspend fun listen(local: PeerEndpoint): TorrentListener {
            sockets.fetchAndAdd(1)
            return transport.listen(local)
          }
          override suspend fun connect(remote: PeerEndpoint): TorrentConnection {
            sockets.fetchAndAdd(1)
            return transport.connect(remote)
          }
        }
        val http = object : HttpEngine {
          override suspend fun head(url: String, headers: Map<String, String>): ServerInfo =
            error("unused")
          override suspend fun download(url: String, range: LongRange?,
            headers: Map<String, String>, onData: suspend (ByteArray) -> Unit) {
            requests.fetchAndAdd(1)
            error("No tracker is contacted")
          }
          override fun close() = Unit
        }
        val now = Instant.fromEpochMilliseconds(1_000)
        val state = TorrentResumeState(metadata.infoHash.hex, 4, "", setOf("0"),
          (root / "private").toString(), encodeBase64(metainfo), version = 2,
          privacy = TorrentDiscoveryPrivacy.PUBLIC)
        val store = MemoryTaskStore()
        store.save(TaskRecord(taskId = "private-seed",
          request = DownloadRequest("http://a/private.torrent",
            destination = Destination((root / "private").toString())),
          outputPath = (root / "private").toString(), state = TaskState.COMPLETED,
          totalBytes = 4, sourceType = TorrentDownloadSource.TYPE,
          sourceResumeState = SourceResumeState(TorrentDownloadSource.TYPE,
            Json.encodeToString(state)),
          createdAt = now, updatedAt = now, completedAt = now,
          control = TaskControl(seeding = true)))
        var created = 0
        val source = TorrentDownloadSource(TorrentConfig(dhtEnabled = true,
          uploadPolicy = TorrentUploadPolicy.WHILE_DOWNLOADING), http).also {
          it.engineFactory = { config ->
            created++
            KotlinTorrentEngine(config, network, TorrentHttp(http))
          }
        }
        val ketch = Ketch(http, taskStore = store, additionalSources = listOf(source))
        try {
          ketch.start()
          delay(500)
          val task = ketch.tasks.value.single()
          assertFalse(assertIs<DownloadState.Completed>(task.state.value).seeding)
          // The seeding intent is kept, but nothing starts while uploads stop at completion.
          assertEquals(true, store.load("private-seed")?.control?.seeding)
          assertEquals(0, created)
          assertEquals(0, sockets.load())
          assertEquals(0, requests.load())
          assertEquals(0, source.slotsInUse())
          assertEquals(0, source.reservedTasks())
        } finally {
          ketch.close()
          network.close()
          torrentFileSystem.deleteRecursively(root, mustExist = false)
        }
      }
    }
  }
}
