package com.linroid.ketch.torrent.metainfo

import com.linroid.ketch.torrent.crypto.sha1Digest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class TorrentMetadataTest {
  /** A multi-file torrent named [name] of empty files at [paths]. */
  private fun files(paths: List<List<String>>, name: String = "root"): Map<String, Any> = mapOf(
    "name" to name, "piece length" to 1L, "pieces" to ByteArray(0),
    "files" to paths.map { mapOf("path" to it, "length" to 0L) }
  )

  private fun parse(info: Map<String, Any>, root: Map<String, Any> = emptyMap()) =
    TorrentMetadata.fromBencode(Bencode.encode(root + ("info" to info)))

  @Test
  fun fromBencode_unsafeNames_storedUnderCleanedNames() {
    val info = files(listOf(listOf("Who Are You?.flac"), listOf("Vol.", "a.txt"),
      listOf("aux.c")), name = "Show: Part 2")

    val metadata = parse(info)

    assertEquals("Show: Part 2", metadata.name)
    assertEquals(listOf("Show_ Part 2/Who Are You_.flac", "Show_ Part 2/Vol/a.txt",
      "Show_ Part 2/_aux.c"), metadata.files.map { it.path })
    // Web seeds ask for the names the torrent lists.
    assertEquals(listOf("Show: Part 2", "Vol.", "a.txt"), metadata.files[1].rawPath)
    // The identity is the raw info dictionary's.
    assertEquals(InfoHash.fromBytes(sha1Digest(Bencode.encode(info))), metadata.infoHash)
  }

  @Test
  fun fromBencode_singleFileUnsafeName_storedUnderCleanedName() {
    val metadata = parse(mapOf("name" to "Why?.iso", "piece length" to 1L, "length" to 0L,
      "pieces" to ByteArray(0)))

    assertEquals("Why_.iso", metadata.files.single().path)
    assertEquals(listOf("Why?.iso"), metadata.files.single().rawPath)
  }

  @Test
  fun fromBencode_traversalNames_rejected() {
    for (path in listOf(listOf(".."), listOf("."), listOf(""), listOf("a/b"), listOf("a\\b"),
      listOf("dir", "x\u0001y"))) {
      assertFailsWith<IllegalArgumentException>(path.toString()) { parse(files(listOf(path))) }
    }
    assertFailsWith<IllegalArgumentException> { parse(files(listOf(listOf("a")), name = "..")) }
  }

  @Test
  fun fromBencode_namesEqualOnceCleaned_rejected() {
    val error = assertFailsWith<IllegalArgumentException> {
      parse(files(listOf(listOf("a?b"), listOf("A_b"))))
    }
    assertEquals("Colliding torrent paths", error.message)
  }

  @Test
  fun fromBencode_v1Padding_keptOutOfTheContentFiles() {
    // libtorrent names each pad `.pad/<length>`, so pads of one length share a path; BitComet's
    // carry no attribute.
    val pad = mapOf("attr" to "p", "length" to 1L, "path" to listOf(".pad", "1"))
    val info = mapOf("name" to "root", "piece length" to 4L, "pieces" to ByteArray(80),
      "files" to listOf(
        mapOf("length" to 3L, "path" to listOf("a")), pad,
        mapOf("length" to 3L, "path" to listOf("b")), pad,
        mapOf("length" to 2L, "path" to listOf("_____padding_file_0_upgrade to BitComet")),
        mapOf("length" to 2L, "path" to listOf("c")),
        mapOf("attr" to "p", "length" to 1L)
      ))

    val metadata = parse(info)

    assertEquals(listOf(0, 2, 5), metadata.contentFiles.map { it.index })
    assertEquals(listOf(false, true, false, true, true, false, true),
      metadata.files.map { it.padding })
    // Padding shapes the pieces: it counts in the torrent's size.
    assertEquals(13L, metadata.totalBytes)
    assertEquals("root/.pad/1", metadata.files.last().path)
  }

  @Test
  fun fromBencode_moreThan128Trackers_keepsTheFirst() {
    val tiers = (0 until 3).map { tier -> (0 until 50).map { "udp://t$tier-$it.test:1" } }

    val metadata = parse(files(listOf(listOf("a"))), mapOf("announce-list" to tiers))

    assertEquals(128, metadata.trackers.size)
    assertEquals(listOf(50, 50, 28), metadata.trackerTiers.map { it.size })
    assertEquals("udp://t0-0.test:1", metadata.trackers.first())
  }

  @Test
  fun fromBencode_deepTreeWithinTheFileLimit_parses() {
    // 8,000 files ten folders deep take more than 100,000 bencode values.
    val paths = (0 until 8_000).map { index ->
      (0 until 9).map { "d$it" } + "f$index"
    }

    assertEquals(8_000, parse(files(paths)).files.size)
  }

  @Test
  fun rejectsCanonicallyEquivalentPaths() {
    val info = mapOf(
      "name" to "root",
      "piece length" to 1L,
      "pieces" to ByteArray(0),
      "files" to listOf("é.txt", "e\u0301.txt").map {
        mapOf("path" to listOf(it), "length" to 0L)
      },
    )
    assertFailsWith<IllegalArgumentException> {
      TorrentMetadata.fromBencode(Bencode.encode(mapOf("info" to info)))
    }
  }

  @Test
  fun boundsPathComponentsByEncodedBytes() {
    validateDiskComponent("界".repeat(85))
    assertFailsWith<IllegalArgumentException> {
      validateDiskComponent("界".repeat(86))
    }
  }


  @Test
  fun fromBencode_singleFileTorrent() {
    val info = mapOf(
      "name" to "test.txt",
      "piece length" to 262144L,
      "length" to 1024L,
      "pieces" to ByteArray(20),
    )
    val torrent = mapOf(
      "info" to info,
      "announce" to "http://tracker.example.com/announce",
    )
    val data = Bencode.encode(torrent)
    val metadata = TorrentMetadata.fromBencode(data)

    assertEquals("test.txt", metadata.name)
    assertEquals(262144L, metadata.pieceLength)
    assertEquals(1024L, metadata.totalBytes)
    assertEquals(1, metadata.files.size)
    assertEquals("test.txt", metadata.files[0].path)
    assertEquals(1024L, metadata.files[0].size)
    assertEquals(0, metadata.files[0].index)
    assertEquals(1, metadata.trackers.size)
    assertEquals(
      "http://tracker.example.com/announce",
      metadata.trackers[0],
    )
  }

  @Test
  fun fromBencode_multiFileTorrent() {
    val files = listOf(
      mapOf(
        "path" to listOf(
          "dir".encodeToByteArray(),
          "file1.txt".encodeToByteArray(),
        ),
        "length" to 500L,
      ),
      mapOf(
        "path" to listOf("file2.txt".encodeToByteArray()),
        "length" to 300L,
      ),
    )
    val info = mapOf(
      "name" to "my-torrent",
      "piece length" to 65536L,
      "files" to files,
      "pieces" to ByteArray(20),
    )
    val torrent = mapOf("info" to info)
    val data = Bencode.encode(torrent)
    val metadata = TorrentMetadata.fromBencode(data)

    assertEquals("my-torrent", metadata.name)
    assertEquals(800L, metadata.totalBytes)
    assertEquals(2, metadata.files.size)
    assertEquals("my-torrent/dir/file1.txt", metadata.files[0].path)
    assertEquals(500L, metadata.files[0].size)
    assertEquals("my-torrent/file2.txt", metadata.files[1].path)
    assertEquals(300L, metadata.files[1].size)
  }

  @Test
  fun fromBencode_announceList() {
    val info = mapOf(
      "name" to "test",
      "piece length" to 1024L,
      "length" to 100L,
      "pieces" to ByteArray(20),
    )
    val announceList = listOf(
      listOf(
        "http://t1.example.com/announce".encodeToByteArray(),
        "http://t2.example.com/announce".encodeToByteArray(),
      ),
      listOf("udp://t3.example.com:6881".encodeToByteArray()),
    )
    val torrent = mapOf(
      "info" to info,
      "announce-list" to announceList,
    )
    val data = Bencode.encode(torrent)
    val metadata = TorrentMetadata.fromBencode(data)

    assertEquals(3, metadata.trackers.size)
    assertEquals(
      "http://t1.example.com/announce",
      metadata.trackers[0],
    )
  }

  @Test
  fun fromBencode_infoHashIsConsistent() {
    val info = mapOf(
      "name" to "test",
      "piece length" to 256L,
      "length" to 10L,
      "pieces" to ByteArray(20),
    )
    val torrent = mapOf("info" to info)
    val data = Bencode.encode(torrent)

    val metadata1 = TorrentMetadata.fromBencode(data)
    val metadata2 = TorrentMetadata.fromBencode(data)

    assertEquals(metadata1.infoHash, metadata2.infoHash)
    assertEquals(40, metadata1.infoHash.hex.length)
  }

  @Test
  fun fromBencode_missingInfo_throws() {
    val torrent = mapOf("announce" to "http://tracker.example.com")
    val data = Bencode.encode(torrent)

    assertFailsWith<IllegalArgumentException> {
      TorrentMetadata.fromBencode(data)
    }
  }

  @Test
  fun fromBencode_missingName_throws() {
    val info = mapOf(
      "piece length" to 256L,
      "length" to 10L,
    )
    val torrent = mapOf("info" to info)
    val data = Bencode.encode(torrent)

    assertFailsWith<IllegalArgumentException> {
      TorrentMetadata.fromBencode(data)
    }
  }

  @Test
  fun fromBencode_missingPieceLength_throws() {
    val info = mapOf(
      "name" to "test",
      "length" to 10L,
    )
    val torrent = mapOf("info" to info)
    val data = Bencode.encode(torrent)

    assertFailsWith<IllegalArgumentException> {
      TorrentMetadata.fromBencode(data)
    }
  }

  @Test
  fun fromBencode_comment() {
    val info = mapOf(
      "name" to "test",
      "piece length" to 256L,
      "length" to 10L,
      "pieces" to ByteArray(20),
    )
    val torrent = mapOf(
      "info" to info,
      "comment" to "Test torrent",
      "created by" to "Ketch Test",
    )
    val data = Bencode.encode(torrent)
    val metadata = TorrentMetadata.fromBencode(data)

    assertEquals("Test torrent", metadata.comment)
    assertEquals("Ketch Test", metadata.createdBy)
  }
}
