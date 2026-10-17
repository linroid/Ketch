package com.linroid.ketch.torrent

import com.linroid.ketch.api.DownloadRequest
import com.linroid.ketch.api.ResolvedSource
import com.linroid.ketch.api.Segment
import com.linroid.ketch.core.engine.DownloadContext
import com.linroid.ketch.core.file.FileAccessor
import com.linroid.ketch.torrent.crypto.torrentRandomBytes
import com.linroid.ketch.torrent.engine.KotlinTorrentEngine
import com.linroid.ketch.torrent.engine.TorrentSessionState
import com.linroid.ketch.torrent.metainfo.InfoHash
import com.linroid.ketch.torrent.net.TorrentHttp
import com.linroid.ketch.torrent.net.createTorrentNetwork
import com.linroid.ketch.torrent.storage.torrentFileSystem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okio.FileSystem
import okio.Path
import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Each run of a torrent task follows the task's session as Ketch runs it: the run pays its own
 * task limits, also on a session an earlier run opened.
 */
@OptIn(ExperimentalAtomicApi::class)
class TorrentSourceRunTest {
  private object NoIo : FileAccessor {
    override suspend fun writeAt(offset: Long, data: ByteArray): Unit = error("Source owns I/O")
    override suspend fun flush(): Unit = error("Source owns I/O")
    override fun close() = Unit
    override suspend fun delete(): Unit = error("Source owns I/O")
    override suspend fun size(): Long = error("Source owns I/O")
    override suspend fun preallocate(size: Long): Unit = error("Source owns I/O")
  }

  /** A run of task [taskId] over [files] of [resolved], as Ketch would start it. */
  private fun context(
    taskId: String,
    resolved: ResolvedSource,
    output: Path,
    files: Set<String>,
    segments: List<Segment> = emptyList(),
    throttle: suspend (Int) -> Unit = {},
    onProgress: suspend (Long, Long) -> Unit = { _, _ -> },
  ) = DownloadContext(
    taskId = taskId, url = resolved.url,
    request = DownloadRequest(resolved.url, selectedFileIds = files), fileAccessor = NoIo,
    segments = MutableStateFlow(segments), onProgress = onProgress, throttle = throttle,
    headers = emptyMap(), preResolved = resolved, outputPath = output.toString(),
  )

  @Test
  fun download_takesOverASeeder_paysItsOwnTaskLimits() = runTest {
    withContext(Dispatchers.Default) {
      withTimeout(30_000) {
        coroutineScope {
          val seeder = GatedSeeder()
          seeder.start(this)
          val root = FileSystem.SYSTEM_TEMPORARY_DIRECTORY /
            "ketch-source-run-${InfoHash.fromBytes(torrentRandomBytes(20)).hex}"
          torrentFileSystem.createDirectories(root)
          val engines = mutableListOf<KotlinTorrentEngine>()
          val source = TorrentDownloadSource(TorrentConfig(encryption = TorrentEncryption.DISABLED,
            dhtEnabled = false, uploadPolicy = TorrentUploadPolicy.SEED_AFTER_COMPLETION),
            seeder.http).also { source ->
            source.engineFactory = { config ->
              KotlinTorrentEngine(config, createTorrentNetwork(), TorrentHttp(seeder.http),
                listenHost = "127.0.0.1").also { engines += it }
            }
          }
          try {
            seeder.openAll()
            val torrent = seeder.torrent
            val resolved = source.resolveMetainfo(torrent.metainfo)
            val output = root / "out"
            // The first run downloads f0 under one per-task limit, then keeps seeding.
            val firstPaid = AtomicLong(0)
            val first = context("task", resolved, output, setOf("0"),
              throttle = { firstPaid.addAndFetch(it.toLong()) })
            source.download(first, null)
            val seeding = assertNotNull(source.sessionOf("task"))
            assertEquals(TorrentSessionState.SEEDING, seeding.state.value)
            val paidBefore = firstPaid.load()
            assertTrue(paidBefore > 0)
            // Its limit changed while it seeded: the run that adds f1 takes the seeder over.
            val state = assertNotNull(source.store(first))
            val secondPaid = AtomicLong(0)
            source.download(context("task", resolved, output, setOf("0", "1"),
              throttle = { secondPaid.addAndFetch(it.toLong()) }), state)
            assertSame(seeding, source.sessionOf("task"))
            assertContentEquals(torrent.payloads[1],
              torrentFileSystem.read(output / "f1") { readByteArray() })
            assertTrue(secondPaid.load() > 0, "the new run's limits were never paid")
            assertEquals(paidBefore, firstPaid.load(), "the finished run's limits were paid")
            source.stopSeeding("task")
            source.assertNoLeaks()
          } finally {
            source.close()
            engines.forEach { it.stop() }
            seeder.close()
            torrentFileSystem.deleteRecursively(root, mustExist = false)
          }
          engines.forEach { it.assertNoLeaks() }
        }
      }
    }
  }
}
