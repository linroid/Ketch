package com.linroid.ketch.torrent

import com.linroid.ketch.torrent.crypto.torrentRandomBytes
import com.linroid.ketch.torrent.magnet.MagnetUri
import com.linroid.ketch.torrent.metainfo.Bencode
import com.linroid.ketch.torrent.metainfo.InfoHash
import com.linroid.ketch.torrent.metainfo.TorrentMetadata
import com.linroid.ketch.torrent.metainfo.TorrentV2Document
import com.linroid.ketch.torrent.storage.torrentFileSystem
import com.linroid.ketch.torrent.storage.torrentSystemFileSystem
import kotlinx.coroutines.test.runTest
import okio.FileSystem
import okio.Path
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Created torrents hash as the fixtures do, which are written by hand and which the engine
 * downloads from independent clients' swarms: v1, v2 and hybrid, single files and folders.
 */
class TorrentCreatorTest {
  private val bare = TorrentCreateOptions(createdBy = null)

  private suspend fun inFolder(block: suspend (Path) -> Unit) {
    val root = FileSystem.SYSTEM_TEMPORARY_DIRECTORY /
      "ketch-create-${InfoHash.fromBytes(torrentRandomBytes(20)).hex}"
    torrentFileSystem.createDirectories(root)
    try {
      block(root)
    } finally {
      torrentFileSystem.deleteRecursively(root, mustExist = false)
    }
  }

  private suspend fun create(path: Path, options: TorrentCreateOptions) =
    createTorrent(torrentSystemFileSystem, path, options) { _, _ -> }

  private fun write(path: Path, bytes: ByteArray) {
    path.parent?.let { torrentFileSystem.createDirectories(it) }
    torrentFileSystem.write(path) { write(bytes) }
  }

  @Test
  fun `a v1 torrent of a folder hashes like the handwritten one`() = runTest {
    inFolder { root ->
      val torrent = MultiFileTorrent()
      torrent.writeTo(root / "pack")

      val created = create(root / "pack", bare.copy(pieceLength = torrent.pieceLength))

      assertEquals(torrent.metadata.infoHash.hex, created.infoHash)
      assertNull(created.infoHashV2)
      assertContentEquals(torrent.metainfo, created.metainfo)
      assertEquals(torrent.sizes.sum().toLong(), created.totalBytes)
      assertEquals(3, created.fileCount)
    }
  }

  @Test
  fun `v2 and hybrid torrents hash like the handwritten ones`() = runTest {
    inFolder { root ->
      val files = listOf("a" to 40_000, "b" to 5, "c" to 100_000, "d" to 32_768)
      for (hybrid in listOf(false, true)) {
        val fixture = TorrentV2Fixture.build(files, hybrid = hybrid)
        fixture.names.forEachIndexed { index, name ->
          write(root / "pack" / name, fixture.payloads[index])
        }
        val format = if (hybrid) TorrentFormat.HYBRID else TorrentFormat.V2

        val created = create(root / "pack", bare.copy(format = format, pieceLength = 32_768))

        assertEquals(fixture.document.identity.v2?.hex, created.infoHashV2)
        assertEquals(fixture.document.identity.v1?.hex, created.infoHash)
        assertContentEquals(fixture.metainfo, created.metainfo)
        // The engine's own reader accepts it, piece layers and hybrid layout included.
        assertEquals(4, TorrentV2Document.parse(created.metainfo).info.files.size)
      }
    }
  }

  @Test
  fun `a single file becomes a single file torrent in every format`() = runTest {
    inFolder { root ->
      val payload = ByteArray(70_000) { (it * 7 + it / 300).toByte() }
      write(root / "movie.mkv", payload)

      val v1 = create(root / "movie.mkv", bare.copy(pieceLength = 16_384))
      val metadata = TorrentMetadata.fromBencode(v1.metainfo)
      assertEquals("movie.mkv", metadata.name)
      assertEquals(listOf(70_000L), metadata.files.map { it.size })
      assertEquals(5, metadata.pieceHashes.size / 20)

      val hybrid = create(root / "movie.mkv",
        bare.copy(format = TorrentFormat.HYBRID, pieceLength = 16_384))
      val document = TorrentV2Document.parse(hybrid.metainfo)
      assertTrue(assertNotNull(document.hybrid).singleFile)
      assertEquals(v1.infoHash?.length, hybrid.infoHash?.length)
      assertEquals(listOf(70_000L), document.info.files.map { it.length })
      // The same content hashes to the same v1 pieces, with or without the v2 half.
      val hybridInfo = checkNotNull(Bencode.parse(hybrid.metainfo)["info"])
      assertContentEquals(metadata.pieceHashes, hybridInfo["pieces"]?.bytes)
    }
  }

  @Test
  fun `files are listed in v2 order and links and empty folders are left out`() = runTest {
    inFolder { root ->
      write(root / "pack" / "b" / "2.bin", ByteArray(10) { 2 })
      write(root / "pack" / "a.txt", ByteArray(10) { 1 })
      write(root / "pack" / "Z.txt", ByteArray(0))
      write(root / "pack" / "b" / "10.bin", ByteArray(20_000) { 3 })
      torrentFileSystem.createDirectories(root / "pack" / "empty")

      val created = create(root / "pack", bare.copy(format = TorrentFormat.HYBRID))

      val document = TorrentV2Document.parse(created.metainfo)
      assertEquals(
        listOf("Z.txt", "a.txt", "b/10.bin", "b/2.bin"),
        document.info.files.map { file -> file.path.joinToString("/") { it.utf8() } },
      )
      assertEquals(16_384, created.pieceLength)
      assertEquals(20_020L, created.totalBytes)
    }
  }

  @Test
  fun `trackers web seeds and the private flag are written and named by the magnet`() = runTest {
    inFolder { root ->
      write(root / "a.bin", ByteArray(100) { it.toByte() })
      val options = bare.copy(
        trackers = listOf(listOf("http://a.example/announce"), listOf("udp://b.example:1337")),
        webSeeds = listOf("https://mirror.example/a.bin"),
        comment = "hello",
        creationDate = 1_700_000_000,
        source = "TAG",
      )

      val created = create(root / "a.bin", options)

      val metadata = TorrentMetadata.fromBencode(created.metainfo)
      assertEquals(options.trackers, metadata.trackerTiers)
      assertEquals(options.webSeeds, metadata.webSeeds)
      assertEquals("hello", metadata.comment)
      val magnet = MagnetUri.parse(assertNotNull(created.magnet))
      assertEquals(metadata.infoHash, magnet.identity.v1)
      assertEquals(options.trackers.flatten(), magnet.trackers)
      // The source tag is part of the hash; the comment and date are not.
      val plain = create(root / "a.bin", bare)
      assertTrue(plain.infoHash != created.infoHash)
      assertEquals(plain.infoHash, create(root / "a.bin", bare.copy(comment = "x")).infoHash)

      val private = create(root / "a.bin", options.copy(privateTorrent = true))
      assertTrue(TorrentMetadata.fromBencode(private.metainfo).isPrivate)
      assertNull(private.magnet)
    }
  }

  @Test
  fun `the piece length is picked for about a thousand pieces and progress reaches the end`() =
    runTest {
      inFolder { root ->
        write(root / "big.bin", ByteArray(40 * 1024 * 1024))
        val progress = mutableListOf<Pair<Long, Long>>()

        val created = createTorrent(torrentSystemFileSystem, root / "big.bin", bare) { done, all ->
          progress += done to all
        }

        assertEquals(64 * 1024, created.pieceLength)
        assertEquals(640, progress.size)
        assertEquals(40L * 1024 * 1024 to 40L * 1024 * 1024, progress.last())
      }
    }

  @Test
  fun `content and options a torrent cannot carry are refused`() = runTest {
    inFolder { root ->
      write(root / "empty" / "a.bin", ByteArray(0))
      write(root / "a.bin", ByteArray(10))
      assertFailsWith<IllegalArgumentException> { create(root / "empty", bare) }
      assertFailsWith<IllegalArgumentException> {
        create(root / "a.bin", bare.copy(pieceLength = 100_000))
      }
      assertFailsWith<IllegalArgumentException> {
        create(root / "a.bin", bare.copy(pieceLength = 8_192))
      }
      assertFailsWith<IllegalArgumentException> {
        create(root / "a.bin", bare.copy(trackers = listOf(listOf("wss://t.example/announce"))))
      }
      assertFailsWith<IllegalArgumentException> {
        create(root / "a.bin", bare.copy(privateTorrent = true))
      }
      assertFailsWith<IllegalArgumentException> {
        create(root / "a.bin", bare.copy(webSeeds = listOf("ftp://mirror.example/a.bin")))
      }
      assertFailsWith<IllegalArgumentException> { create(root / "a.bin", bare.copy(name = "a/b")) }
      // A folder's name goes into the path of every file below it.
      write(root / "odd" / "tab\there" / "a.bin", ByteArray(10))
      assertFailsWith<IllegalArgumentException> { create(root / "odd", bare) }
    }
  }
}
