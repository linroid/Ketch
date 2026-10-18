package com.linroid.ketch.torrent

import com.linroid.ketch.api.DownloadRequest
import com.linroid.ketch.api.KetchError
import com.linroid.ketch.api.ResolvedSource
import com.linroid.ketch.core.engine.DownloadContext
import com.linroid.ketch.core.file.FileAccessor
import com.linroid.ketch.torrent.crypto.torrentRandomBytes
import com.linroid.ketch.torrent.engine.KotlinTorrentEngine
import com.linroid.ketch.torrent.engine.TorrentEngine
import com.linroid.ketch.torrent.engine.TorrentSession
import com.linroid.ketch.torrent.engine.TorrentTaskSpec
import com.linroid.ketch.torrent.metainfo.Bencode
import com.linroid.ketch.torrent.metainfo.InfoHash
import com.linroid.ketch.torrent.metainfo.TorrentMetadata
import com.linroid.ketch.torrent.storage.torrentFileSystem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okio.FileSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

/**
 * A task resumes from the metainfo it saved, read without the checks a new input passes: those
 * it passed when it was added, or predates. Only admission refuses what no session can run.
 */
class TorrentSavedMetainfoTest {
  private object NoIo : FileAccessor {
    override suspend fun writeAt(offset: Long, data: ByteArray): Unit = error("Source owns I/O")
    override suspend fun flush(): Unit = error("Source owns I/O")
    override fun close() = Unit
    override suspend fun delete(): Unit = error("Source owns I/O")
    override suspend fun size(): Long = error("Source owns I/O")
    override suspend fun preallocate(size: Long): Unit = error("Source owns I/O")
  }

  /** Records the tasks it is given, whose sessions finish as soon as they resume. */
  private class Engine : TorrentEngine by FakeTorrentEngine() {
    val tasks = mutableListOf<TorrentTaskSpec>()

    override suspend fun addTask(spec: TorrentTaskSpec): TorrentSession {
      tasks += spec
      val session = FakeTorrentSession(spec.metadata.infoHash.hex)
      return object : TorrentSession by session {
        override suspend fun resume() {
          session.resume()
          session.finish()
        }
      }
    }
  }

  private fun context(url: String, output: String, resolved: ResolvedSource? = null) =
    DownloadContext(
      taskId = "saved", url = url, request = DownloadRequest(url = url, resolvedSource = resolved),
      fileAccessor = NoIo, segments = MutableStateFlow(emptyList()), onProgress = { _, _ -> },
      throttle = {}, headers = emptyMap(), preResolved = resolved, outputPath = output,
    )

  @Test
  fun download_savedTaskWithoutAUsableTracker_resumes() = runTest {
    // Private, with a WebSocket tracker alone: no longer added, as it could never find a peer.
    val metainfo = Bencode.encode(mapOf("announce" to "wss://tracker.test/announce",
      "info" to mapOf("name" to "empty", "length" to 0L, "piece length" to 16_384L,
        "pieces" to ByteArray(0), "private" to 1L)))
    val metadata = TorrentMetadata.fromBencode(metainfo)
    val engine = Engine()
    val source = TorrentDownloadSource().also { it.engineFactory = { engine } }
    try {
      assertFailsWith<IllegalArgumentException> { source.resolveMetainfo(metainfo) }
      // Older builds kept the metainfo in the state, newer ones in the task's resolution.
      val state = TorrentSourceState(metadata.infoHash.hex, 0, "", setOf("0"), "/tmp/empty",
        encodeBase64(metainfo), version = 2, privacy = TorrentDiscoveryPrivacy.PUBLIC)
      source.download(context("torrent:${metadata.infoHash.hex}", "/tmp/empty"), state)
      assertEquals(metadata.infoHash, engine.tasks.single().metadata.infoHash)
    } finally {
      source.close()
    }
  }

  @Test
  fun download_savedV2TaskWithLargerPieces_refusedByAdmission() = runTest {
    withContext(Dispatchers.Default) {
      withTimeout(20_000) {
        for (hybrid in listOf(false, true)) {
          val metainfo = TorrentV2Fixture.build(listOf("a.bin" to 40_000),
            pieceLength = 32 * 1024 * 1024, hybrid = hybrid).metainfo
          // What a task resolved before such torrents were refused kept.
          val resolved = assertNotNull(resolveV2Metainfo(null, metainfo, TorrentConfig(),
            TorrentDiscoveryPrivacy.PUBLIC, newInput = false))
          val engines = mutableListOf<KotlinTorrentEngine>()
          val source = TorrentDownloadSource(TorrentConfig(dhtEnabled = false)).also {
            it.engineFactory = { config ->
              KotlinTorrentEngine(config, listenHost = "127.0.0.1").also { engines += it }
            }
          }
          val root = FileSystem.SYSTEM_TEMPORARY_DIRECTORY /
            "ketch-saved-${InfoHash.fromBytes(torrentRandomBytes(20)).hex}"
          try {
            val hash = requireNotNull(resolved.metadata[TorrentDownloadSource.META_INFO_HASH])
            val state = TorrentSourceState(hash, 40_000, "",
              setOf(resolved.files.single().id), "", version = 3,
              privacy = TorrentDiscoveryPrivacy.PUBLIC)
            val error = assertFailsWith<KetchError.SourceError> {
              source.download(context(resolved.url, (root / "out").toString(), resolved), state)
            }
            assertEquals("The torrent's pieces are 32 MiB, larger than the 16 MiB Ketch supports",
              error.cause?.message)
            // Refused by the engine's admission, not as the task read its metainfo.
            assertEquals(1, engines.size)
          } finally {
            source.close()
            torrentFileSystem.deleteRecursively(root)
          }
        }
      }
    }
  }
}
