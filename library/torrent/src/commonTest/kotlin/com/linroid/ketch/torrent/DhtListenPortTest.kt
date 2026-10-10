package com.linroid.ketch.torrent

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
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

  /** A port no listener holds; the system picks it, and it is free again once closed. */
  private suspend fun freePort(network: TorrentNetwork): Int {
    val listener = network.listen(PeerEndpoint("0.0.0.0", 0))
    return listener.local.port.also { listener.close() }
  }

  /** Starts the engine's DHT through a public magnet lookup that finds no peers. */
  private suspend fun lookUp(engine: KotlinTorrentEngine) {
    val magnet = MagnetUri(InfoHash.fromBytes(torrentRandomBytes(20))).toUri()
    try { engine.fetchMetadata(magnet) } catch (_: Exception) {
      // The lookup times out; only the DHT it started matters.
    }
  }
}
