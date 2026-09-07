package com.linroid.ketch.torrent

import com.linroid.ketch.api.DownloadRequest
import com.linroid.ketch.core.engine.DownloadContext
import com.linroid.ketch.core.file.FileAccessor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okio.FileSystem
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class KotlinRuntimeDhtTest {
  @Test
  fun trackerlessPublicSource_bootstrapsDhtResolvesMetadataAndDownloads() = runTest {
    withContext(Dispatchers.Default) {
      withTimeout(25_000) {
        coroutineScope {
          val root = FileSystem.SYSTEM_TEMPORARY_DIRECTORY /
            "ketch-runtime-dht-${InfoHash.fromBytes(torrentRandomBytes(20)).hex}"
          val bytes = ByteArray(80_037) { (it * 7).toByte() }
          val hashes = bytes.asList().chunked(16_384).fold(ByteArray(0)) { hash, part ->
            hash + sha1Digest(part.toByteArray())
          }
          val metadata = TorrentMetadata.fromBencode(Bencode.encode(mapOf("info" to mapOf(
            "name" to "fixture", "length" to bytes.size.toLong(),
            "piece length" to 16_384L, "pieces" to hashes
          ))))
          torrentFileSystem.createDirectories(root)
          torrentFileSystem.write(root / "seed") { write(bytes) }
          val seeder = KotlinTorrentEngine(TorrentConfig(dhtEnabled = false,
            uploadPolicy = TorrentUploadPolicy.SEED_AFTER_COMPLETION))
          val network = createTorrentNetwork()
          fun node(socket: TorrentDatagramSocket) = DhtNode(socket, this,
            allowLocalAddresses = true).also { it.start() }
          val router = node(network.bindUdp(PeerEndpoint("127.0.0.1", 0)))
          val announcer = node(network.bindUdp(PeerEndpoint("127.0.0.1", 0)))
          val config = TorrentConfig(dhtBootstrap = listOf("127.0.0.1:${router.local.port}"))
          val source = TorrentDownloadSource(config).also {
            it.engineFactory = { KotlinTorrentEngine(config, allowLocalDiscovery = true,
              discoveryIntervalMs = 100) }
          }
          try {
            seeder.start()
            val session = seeder.addTask(TorrentTaskSpec("dht-seed", metadata,
              (root / "seed").toString(), emptySet()))
            session.resume()
            assertEquals(TorrentSessionState.SEEDING, session.state.first {
              it == TorrentSessionState.SEEDING || it == TorrentSessionState.STOPPED
            }, session.failure.value?.stackTraceToString())
            announcer.bootstrap(listOf(router.local))
            announcer.peers(metadata.infoHash, seeder.listenPort)
            val magnet = MagnetUri(metadata.infoHash).toUri()
            val resolved = source.resolve(magnet, emptyMap())
            val context = DownloadContext(taskId = "dht-client", url = magnet,
              request = DownloadRequest(magnet), fileAccessor = object : FileAccessor {
                override suspend fun writeAt(offset: Long, data: ByteArray) = Unit
                override suspend fun flush() = Unit
                override fun close() = Unit
                override suspend fun delete() = Unit
                override suspend fun size(): Long = 0
                override suspend fun preallocate(size: Long) = Unit
              },
              segments = MutableStateFlow(emptyList()), onProgress = { _, _ -> }, throttle = {},
              headers = emptyMap(), preResolved = resolved,
              outputPath = (root / "result").toString(),
            )
            source.download(context)
            assertContentEquals(bytes, torrentFileSystem.read(root / "result") { readByteArray() })
          } finally {
            source.close()
            seeder.stop()
            announcer.close()
            router.close()
            network.close()
            torrentFileSystem.deleteRecursively(root, mustExist = false)
          }
        }
      }
    }
  }
}
