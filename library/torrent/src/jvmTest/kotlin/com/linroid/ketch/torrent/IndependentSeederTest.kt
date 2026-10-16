package com.linroid.ketch.torrent

import com.linroid.ketch.torrent.crypto.sha1Digest
import com.linroid.ketch.torrent.dht.DhtNode
import com.linroid.ketch.torrent.engine.TorrentBufferBudget
import com.linroid.ketch.torrent.magnet.MagnetUri
import com.linroid.ketch.torrent.magnet.TorrentMetadataExchange
import com.linroid.ketch.torrent.metainfo.Bencode
import com.linroid.ketch.torrent.metainfo.TorrentMetadata
import com.linroid.ketch.torrent.mse.TorrentPeerEncryption
import com.linroid.ketch.torrent.net.PeerEndpoint
import com.linroid.ketch.torrent.net.TorrentConnection
import com.linroid.ketch.torrent.net.TorrentNetwork
import com.linroid.ketch.torrent.net.TorrentUdpPort
import com.linroid.ketch.torrent.net.createTorrentNetwork
import com.linroid.ketch.torrent.storage.TorrentPieceStore
import com.linroid.ketch.torrent.utp.TorrentUtpFallback
import com.linroid.ketch.torrent.utp.UtpEndpoint
import com.linroid.ketch.torrent.v1.TorrentSwarm
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okio.IOException
import okio.Path.Companion.toPath
import org.libtorrent4j.LibTorrent
import org.libtorrent4j.SessionManager
import org.libtorrent4j.TorrentInfo
import org.libtorrent4j.swig.settings_pack
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** libtorrent is used only as an independent local reference seeder for this test. */
class IndependentSeederTest {
  @Test
  fun kotlinDownloader_completesVerifiedTransferFromIndependentSeeder() = runTest {
    withContext(Dispatchers.Default) {
      withTimeout(20_000) {
        NativeLibraryLoader.ensureLoaded()
        assertEquals(ConformanceClients.version("libtorrent4j"), LibTorrent.libtorrent4jVersion())
        println("CONFORMANCE_CLIENT libtorrent ${LibTorrent.version()} " +
          "libtorrent4j ${LibTorrent.libtorrent4jVersion()}")
        val root = Files.createTempDirectory("ketch-interop").toFile()
        val seed = root.resolve("seed").apply { mkdirs() }
        val payload = ByteArray(80_037) { (it * 31 + 17).toByte() }
        seed.resolve("fixture.bin").writeBytes(payload)
        val hashes = payload.asList().chunked(16_384)
          .fold(ByteArray(0)) { bytes, chunk -> bytes + sha1Digest(chunk.toByteArray()) }
        val data = Bencode.encode(mapOf("info" to mapOf(
          "name" to "fixture.bin", "length" to payload.size.toLong(),
          "piece length" to 16_384L, "pieces" to hashes
        )))
        val manager = SessionManager()
        val network = createTorrentNetwork()
        val dhtNodes = mutableListOf<DhtNode>()
        try {
          val seederPort = manager.startSeeding(TorrentInfo(data), seed)
          val output = root.resolve("download/fixture.bin")
          val expected = TorrentMetadata.fromBencode(data)
          suspend fun dhtNode(): DhtNode = DhtNode(
            network.bindUdp(PeerEndpoint("127.0.0.1", 0)), this,
            allowLocalAddresses = true,
          ).also { it.start(); dhtNodes += it }
          val router = dhtNode()
          val announcing = dhtNode()
          val seeking = dhtNode()
          announcing.bootstrap(listOf(router.local))
          announcing.peers(expected.infoHash, announcePort = seederPort)
          seeking.bootstrap(listOf(router.local))
          val endpoint = seeking.peers(expected.infoHash).single()
          val metadata = TorrentMetadataExchange(network).fetch(expected.infoHash, endpoint)
          assertContentEquals(expected.infoBytes, metadata.infoBytes)
          val store = TorrentPieceStore(metadata, output.absolutePath.toPath(), emptySet(), "interop")
          var received = 0L
          val progress = mutableListOf<Long>()
          val peers = Channel<PeerEndpoint>(1)
          peers.send(endpoint)
          peers.close()
          TorrentSwarm(store, network, TorrentBufferBudget(4 * 1024 * 1024),
            downloadPayload = { received += it }, onProgress = { progress.add(it) }).run(peers)
          assertContentEquals(payload, output.readBytes())
          assertEquals(payload.size.toLong(), received)
          assertEquals(payload.size.toLong(), progress.last())
          assertTrue(store.completed())
        } finally {
          dhtNodes.forEach { it.close() }
          network.close()
          manager.stop()
          root.deleteRecursively()
        }
      }
    }
  }

  @Test
  fun liveSelectionExpand_keepsTheIndependentSeederConnection() = runTest {
    withContext(Dispatchers.Default) {
      withTimeout(60_000) {
        NativeLibraryLoader.ensureLoaded()
        assertEquals(ConformanceClients.version("libtorrent4j"),
          LibTorrent.libtorrent4jVersion())
        println("CONFORMANCE_CLIENT libtorrent ${LibTorrent.version()} " +
          "libtorrent4j ${LibTorrent.libtorrent4jVersion()}")
        val root = Files.createTempDirectory("ketch-interop-selection").toFile()
        val seed = root.resolve("seed").apply { mkdirs() }
        // Three files whose boundaries fall inside pieces; libtorrent seeds them from seed/pack.
        val torrent = MultiFileTorrent()
        torrent.writeTo(seed.absolutePath.toPath() / "pack")
        val manager = SessionManager()
        try {
          val port = manager.startSeeding(TorrentInfo(torrent.metainfo), seed)
          val magnet = MagnetUri(torrent.metadata.infoHash,
            explicitPeers = listOf("127.0.0.1:$port")).toUri()
          val output = root.resolve("out")
          val expansion = downloadExpandingLive(magnet, torrent.metainfo, output.absolutePath,
            first = setOf("0"), expanded = setOf("0", "1"))
          assertContentEquals(torrent.payloads[0], output.resolve("f0").readBytes())
          assertContentEquals(torrent.payloads[1], output.resolve("f1").readBytes())
          assertFalse(output.resolve("f2").exists())
          // The expansion reached the running swarm: no peer was dialed again for it. The first
          // dial may take two connections, when an older libtorrent refuses the MSE secret.
          assertEquals(expansion.connectsAtChange, expansion.connects)
        } finally {
          manager.stop()
          root.deleteRecursively()
        }
      }
    }
  }

  @Test
  fun encryptionOnlySeeder_downloadsOverRc4() = runTest {
    withContext(Dispatchers.Default) {
      withTimeout(30_000) {
        NativeLibraryLoader.ensureLoaded()
        val root = Files.createTempDirectory("ketch-interop-mse").toFile()
        val seed = root.resolve("seed").apply { mkdirs() }
        val payload = ByteArray(80_037) { (it * 13 + 5).toByte() }
        seed.resolve("fixture.bin").writeBytes(payload)
        val hashes = payload.asList().chunked(16_384)
          .fold(ByteArray(0)) { bytes, chunk -> bytes + sha1Digest(chunk.toByteArray()) }
        val data = Bencode.encode(mapOf("info" to mapOf(
          "name" to "fixture.bin", "length" to payload.size.toLong(),
          "piece length" to 16_384L, "pieces" to hashes
        )))
        // libtorrent refuses every connection that does not negotiate RC4.
        val settings = loopbackSettings().also {
          it.setInteger(settings_pack.int_types.in_enc_policy.swigValue(),
            settings_pack.enc_policy.pe_forced.swigValue())
          it.setInteger(settings_pack.int_types.out_enc_policy.swigValue(),
            settings_pack.enc_policy.pe_forced.swigValue())
          it.setInteger(settings_pack.int_types.allowed_enc_level.swigValue(),
            settings_pack.enc_level.pe_rc4.swigValue())
        }
        val manager = SessionManager()
        val raw = createTorrentNetwork()
        try {
          val endpoint = PeerEndpoint("127.0.0.1", manager.startSeeding(TorrentInfo(data), seed,
            settings))
          val expected = TorrentMetadata.fromBencode(data)
          // In plaintext the seeder hangs up.
          assertFailsWith<Exception> {
            TorrentMetadataExchange(TorrentPeerEncryption(raw, TorrentEncryption.DISABLED))
              .fetch(expected.infoHash, endpoint)
          }
          val network = TorrentPeerEncryption(raw, TorrentEncryption.REQUIRED)
          val metadata = TorrentMetadataExchange(network).fetch(expected.infoHash, endpoint)
          assertContentEquals(expected.infoBytes, metadata.infoBytes)
          val output = root.resolve("download/fixture.bin")
          val store = TorrentPieceStore(metadata, output.absolutePath.toPath(), emptySet(), "mse")
          val peers = Channel<PeerEndpoint>(1)
          peers.send(endpoint)
          peers.close()
          TorrentSwarm(store, network, TorrentBufferBudget(4 * 1024 * 1024)).run(peers)
          assertContentEquals(payload, output.readBytes())
        } finally {
          raw.close()
          manager.stop()
          root.deleteRecursively()
        }
      }
    }
  }

  @Test
  fun utpOnlySeeder_downloadsOverUtp() = runTest {
    withContext(Dispatchers.Default) {
      withTimeout(30_000) {
        NativeLibraryLoader.ensureLoaded()
        val root = Files.createTempDirectory("ketch-interop-utp").toFile()
        val seed = root.resolve("seed").apply { mkdirs() }
        val payload = ByteArray(600_037) { (it * 7 + 3).toByte() }
        seed.resolve("fixture.bin").writeBytes(payload)
        val hashes = payload.asList().chunked(16_384)
          .fold(ByteArray(0)) { bytes, chunk -> bytes + sha1Digest(chunk.toByteArray()) }
        val data = Bencode.encode(mapOf("info" to mapOf(
          "name" to "fixture.bin", "length" to payload.size.toLong(),
          "piece length" to 16_384L, "pieces" to hashes
        )))
        // libtorrent speaks uTP only.
        val settings = loopbackSettings().also {
          it.setBoolean(settings_pack.bool_types.enable_incoming_tcp.swigValue(), false)
          it.setBoolean(settings_pack.bool_types.enable_outgoing_tcp.swigValue(), false)
        }
        val manager = SessionManager()
        val raw = createTorrentNetwork()
        val scope = CoroutineScope(Dispatchers.Default)
        try {
          val endpoint = PeerEndpoint("127.0.0.1", manager.startSeeding(TorrentInfo(data), seed,
            settings))
          var utp: UtpEndpoint? = null
          val port = TorrentUdpPort(raw.bindUdp(PeerEndpoint("127.0.0.1", 0)), scope,
            utp = { utp?.receive(it) })
          utp = UtpEndpoint(scope, port::send, onIncoming = { false })
          // TCP is refused, as by a peer behind NAT; the dial falls back to uTP.
          val tcpRefused = object : TorrentNetwork by raw {
            override suspend fun connect(remote: PeerEndpoint): TorrentConnection =
              throw IOException("Connection refused")
          }
          val network = TorrentUtpFallback(tcpRefused) { utp }
          val expected = TorrentMetadata.fromBencode(data)
          val metadata = TorrentMetadataExchange(network).fetch(expected.infoHash, endpoint)
          assertContentEquals(expected.infoBytes, metadata.infoBytes)
          val output = root.resolve("download/fixture.bin")
          val store = TorrentPieceStore(metadata, output.absolutePath.toPath(), emptySet(), "utp")
          val peers = Channel<PeerEndpoint>(1)
          peers.send(endpoint)
          peers.close()
          TorrentSwarm(store, network, TorrentBufferBudget(4 * 1024 * 1024)).run(peers)
          assertContentEquals(payload, output.readBytes())
        } finally {
          // Stopped before its socket closes, which fails rather than cancels its receive.
          withContext(NonCancellable) { scope.coroutineContext.job.cancelAndJoin() }
          raw.close()
          manager.stop()
          root.deleteRecursively()
        }
      }
    }
  }

}
