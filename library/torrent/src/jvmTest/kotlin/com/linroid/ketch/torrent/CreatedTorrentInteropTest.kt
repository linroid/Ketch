package com.linroid.ketch.torrent

import com.linroid.ketch.torrent.storage.torrentSystemFileSystem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okio.Path.Companion.toOkioPath
import org.libtorrent4j.SessionManager
import org.libtorrent4j.TorrentInfo
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * libtorrent, an independent implementation, opens the torrents Ketch creates and, checking the
 * files against their hashes, finds them complete: it would stay a downloader if one piece hash
 * or tree root were wrong.
 */
class CreatedTorrentInteropTest {
  @Test fun libtorrentSeedsACreatedV1Torrent() = runTest { seeds(TorrentFormat.V1) }

  @Test fun libtorrentSeedsACreatedV2Torrent() = runTest { seeds(TorrentFormat.V2) }

  @Test fun libtorrentSeedsACreatedHybridTorrent() = runTest { seeds(TorrentFormat.HYBRID) }

  private suspend fun seeds(format: TorrentFormat) = withContext(Dispatchers.Default) {
    withTimeout(60_000) {
      NativeLibraryLoader.ensureLoaded()
      val root = Files.createTempDirectory("ketch-created-interop").toFile()
      val pack = root.resolve("pack").apply { mkdirs() }
      // Several pieces, one piece exactly, less than a block, and a file in a folder.
      val files = mapOf("a.bin" to 100_000, "b.bin" to 16_384, "c.txt" to 5,
        "sub/d.bin" to 40_000)
      for ((name, size) in files) {
        val file = pack.resolve(name).apply { parentFile.mkdirs() }
        file.writeBytes(ByteArray(size) { (it * 31 + size + it / 257).toByte() })
      }
      val manager = SessionManager()
      try {
        val created = createTorrent(torrentSystemFileSystem, pack.toOkioPath(),
          TorrentCreateOptions(format = format, pieceLength = 16_384,
            trackers = listOf(listOf("http://tracker.invalid/announce")))) { _, _ -> }

        val info = TorrentInfo(created.metainfo)

        assertEquals("pack", info.name())
        if (format == TorrentFormat.V1) {
          // With a v2 half, libtorrent counts the padding between files too.
          assertEquals(files.values.sum().toLong(), info.totalSize())
        }
        // libtorrent names a torrent with a v2 half by its truncated v2 hash.
        val expected = created.infoHashV2?.take(40) ?: created.infoHash
        assertEquals(expected, info.infoHash().toHex())
        // Waits until libtorrent has checked the files and seeds them.
        assertTrue(manager.startSeeding(info, root) > 0)
      } finally {
        manager.stop()
        root.deleteRecursively()
      }
    }
  }
}
