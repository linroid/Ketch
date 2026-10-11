package com.linroid.ketch.torrent.dht

import com.linroid.ketch.torrent.TorrentConfig
import com.linroid.ketch.torrent.crypto.torrentRandomBytes
import com.linroid.ketch.torrent.engine.KotlinTorrentEngine
import com.linroid.ketch.torrent.magnet.MagnetUri
import com.linroid.ketch.torrent.metainfo.InfoHash
import com.linroid.ketch.torrent.net.PeerEndpoint
import com.linroid.ketch.torrent.net.TorrentDatagramSocket
import com.linroid.ketch.torrent.net.TorrentNetwork
import com.linroid.ketch.torrent.net.createTorrentNetwork
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals

class DhtListenPortTest {
  @Test
  fun fixedListenPort_dhtBindsTheSamePortOverUdp() = runTest {
    withContext(Dispatchers.Default) {
      withTimeout(20_000) {
        val bound = MutableStateFlow(emptyList<PeerEndpoint>())
        val real = createTorrentNetwork()
        val network = object : TorrentNetwork by real {
          override suspend fun bindUdp(local: PeerEndpoint): TorrentDatagramSocket =
            real.bindUdp(local).also { bound.update { it + local } }
        }
        val port = freePort(real)
        val engine = KotlinTorrentEngine(
          TorrentConfig(listenPort = port, dhtBootstrap = emptyList(), metadataTimeoutSeconds = 1),
          rawNetwork = network,
        )
        try {
          engine.start()
          assertEquals(port, engine.listenPort)
          lookUp(engine)
          assertContains(bound.value, PeerEndpoint("0.0.0.0", port))
        } finally {
          engine.stop()
          real.close()
        }
      }
    }
  }

  /**
   * A port no TCP listener holds, from below every system's ephemeral range (Linux starts at
   * 32768, macOS and Windows at 49152). A port the system hands out for port 0 is free for TCP
   * only, and any UDP socket bound to port 0 meanwhile, by another test or process, may get it,
   * which leaves DHT on another port. UDP is not probed: a closed UDP socket frees its port only
   * once its selector gets to it, so the probe itself would hold the port the engine wants.
   */
  private suspend fun freePort(network: TorrentNetwork): Int {
    repeat(100) {
      val port = Random.nextInt(20_000, 32_000)
      try {
        network.listen(PeerEndpoint("0.0.0.0", port)).close()
        return port
      } catch (e: CancellationException) {
        throw e
      } catch (_: Exception) {
        // Taken, or reserved by the system: try another.
      }
    }
    error("No free TCP port")
  }

  /** Starts the engine's DHT through a public magnet lookup that finds no peers. */
  private suspend fun lookUp(engine: KotlinTorrentEngine) {
    val magnet = MagnetUri(InfoHash.fromBytes(torrentRandomBytes(20))).toUri()
    try { engine.fetchMetadata(magnet) } catch (_: Exception) {
      // The lookup times out; only the DHT it started matters.
    }
  }
}
