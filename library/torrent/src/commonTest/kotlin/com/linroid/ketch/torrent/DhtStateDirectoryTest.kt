package com.linroid.ketch.torrent

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okio.FileSystem
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.incrementAndFetch
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalAtomicApi::class)
class DhtStateDirectoryTest {
  @Test
  fun savedRoutingTable_bootstrapsTheNextEngineWithoutRouters() = runTest {
    withContext(Dispatchers.Default) {
      withTimeout(20_000) {
        coroutineScope {
          val state = FileSystem.SYSTEM_TEMPORARY_DIRECTORY /
            "ketch-dht-state-${InfoHash.fromBytes(torrentRandomBytes(20)).hex}"
          val network = createTorrentNetwork()
          val queried = AtomicInt(0)
          val socket = network.bindUdp(PeerEndpoint("127.0.0.1", 0))
          val router = DhtNode(object : TorrentDatagramSocket by socket {
            override suspend fun receive() = socket.receive().also { queried.incrementAndFetch() }
          }, this, allowLocalAddresses = true).also { it.start() }
          try {
            val first = engine(state, bootstrap = listOf("127.0.0.1:${router.local.port}"))
            try {
              first.start()
              lookUp(first)
              while (!torrentFileSystem.exists(state / "dht4.nodes")) delay(50)
            } finally { first.stop() }
            queried.store(0)

            // Only the saved snapshot can lead this engine to the router.
            val second = engine(state, bootstrap = emptyList())
            try {
              second.start()
              lookUp(second)
              while (queried.load() == 0) delay(50)
            } finally { second.stop() }
            assertEquals(emptyList(), torrentFileSystem.list(state)
              .filter { it.name.endsWith(".tmp") }, "temporary snapshot files were left behind")
          } finally {
            router.close()
            network.close()
            torrentFileSystem.deleteRecursively(state, mustExist = false)
          }
        }
      }
    }
  }

  private fun engine(state: okio.Path, bootstrap: List<String>) = KotlinTorrentEngine(
    TorrentConfig(stateDirectory = state.toString(), dhtBootstrap = bootstrap,
      metadataTimeoutSeconds = 1),
    allowLocalDiscovery = true,
    discoveryIntervalMs = 100,
  )

  /** Starts the engine's DHT through a public magnet lookup that finds no peers. */
  private suspend fun lookUp(engine: KotlinTorrentEngine) {
    val magnet = MagnetUri(InfoHash.fromBytes(torrentRandomBytes(20))).toUri()
    try { engine.fetchMetadata(magnet) } catch (_: Exception) {
      // The lookup times out; only the DHT it started matters.
    }
  }
}
