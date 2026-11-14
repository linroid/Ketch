package com.linroid.ketch.torrent

import com.linroid.ketch.api.Destination
import com.linroid.ketch.api.DownloadRequest
import com.linroid.ketch.api.DownloadState
import com.linroid.ketch.core.Ketch
import com.linroid.ketch.torrent.crypto.torrentRandomBytes
import com.linroid.ketch.torrent.engine.KotlinTorrentEngine
import com.linroid.ketch.torrent.metainfo.InfoHash
import com.linroid.ketch.torrent.net.TorrentHttp
import com.linroid.ketch.torrent.storage.torrentFileSystem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okio.FileSystem
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * A peer at a blocked address is never dialed, and is dialed, with the download completing,
 * once the address is no longer blocked.
 */
@OptIn(ExperimentalAtomicApi::class)
class TorrentBlockedPeersTest {
  @Test
  fun `a blocked peer is never dialed until its address is unblocked`() = runTest {
    withContext(Dispatchers.Default) {
      withTimeout(60_000) {
        coroutineScope {
          val seeder = GatedSeeder()
          seeder.start(this)
          seeder.openAll()
          val root = FileSystem.SYSTEM_TEMPORARY_DIRECTORY /
            "ketch-blocked-${InfoHash.fromBytes(torrentRandomBytes(20)).hex}"
          torrentFileSystem.createDirectories(root)
          val network = CountingTorrentNetwork()
          val engines = mutableListOf<KotlinTorrentEngine>()
          // The seeder listens on loopback, which the whole block covers.
          val source = TorrentDownloadSource(TorrentConfig(dhtEnabled = false,
            uploadPolicy = TorrentUploadPolicy.DISABLED, portMapping = false,
            localDiscovery = false, trackerListsEnabled = false,
            blockedAddresses = listOf("127.0.0.0/8", "not an address")), seeder.http)
          source.engineFactory = { config ->
            KotlinTorrentEngine(config, network, TorrentHttp(seeder.http),
              listenHost = "127.0.0.1").also { engines += it }
          }
          val ketch = Ketch(seeder.http, taskStore = MemoryTaskStore(),
            additionalSources = listOf(source))
          try {
            ketch.start()
            val task = ketch.download(DownloadRequest(GatedSeeder.URL,
              destination = Destination((root / "out").toString())))
            task.state.first { it is DownloadState.Downloading }
            delay(2_500)

            assertEquals(0, network.connects.load())
            assertIs<DownloadState.Downloading>(task.state.value)
            assertEquals(listOf("127.0.0.0/8", "not an address"),
              source.settings.value.blockedAddresses)

            source.setBlockedAddresses(emptyList())

            assertIs<DownloadState.Completed>(
              task.state.first { it is DownloadState.Completed || it is DownloadState.Failed }
            )
          } finally {
            ketch.close()
            engines.forEach { it.stop() }
            seeder.close()
            torrentFileSystem.deleteRecursively(root, mustExist = false)
          }
        }
      }
    }
  }
}
