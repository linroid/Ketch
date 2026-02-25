package com.linroid.ketch.torrent

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/**
 * Additional edge case tests for [TorrentMetadata.fromBencode]
 * beyond the basics covered in [TorrentMetadataTest].
 */
class TorrentMetadataEdgeCaseTest {

  private fun buildTorrent(
    info: Map<String, Any>,
    vararg extra: Pair<String, Any>,
  ): ByteArray {
    val torrent = mutableMapOf<String, Any>("info" to info)
    extra.forEach { (k, v) -> torrent[k] = v }
    return Bencode.encode(torrent)
  }

  private fun singleFileInfo(
    name: String = "test.txt",
    pieceLength: Long = 262144L,
    length: Long = 1024L,
  ): Map<String, Any> = mapOf(
    "name" to name,
    "piece length" to pieceLength,
    "length" to length,
    "pieces" to ByteArray(20),
  )

  // -- Single file edge cases --

  @Test
  fun fromBencode_singleFile_zeroBytes() {
    val data = buildTorrent(singleFileInfo(length = 0L))
    val metadata = TorrentMetadata.fromBencode(data)
    assertEquals(0L, metadata.totalBytes)
    assertEquals(1, metadata.files.size)
    assertEquals(0L, metadata.files[0].size)
  }

  @Test
  fun fromBencode_singleFile_largeSize() {
    val size = 10_000_000_000L // 10 GB
    val data = buildTorrent(singleFileInfo(length = size))
    val metadata = TorrentMetadata.fromBencode(data)
    assertEquals(size, metadata.totalBytes)
  }

  @Test
  fun fromBencode_singleFile_missingLength_throws() {
    val info = mapOf(
      "name" to "test.txt",
      "piece length" to 256L,
      "pieces" to ByteArray(20),
      // no "length" and no "files"
    )
    val data = buildTorrent(info)
    assertFailsWith<IllegalArgumentException> {
      TorrentMetadata.fromBencode(data)
    }
  }

  // -- Multi file edge cases --

  @Test
  fun fromBencode_multiFile_singleFileInList() {
    val files = listOf(
      mapOf(
        "path" to listOf("readme.txt".encodeToByteArray()),
        "length" to 100L,
      ),
    )
    val info = mapOf(
      "name" to "my-dir",
      "piece length" to 1024L,
      "files" to files,
      "pieces" to ByteArray(20),
    )
    val data = buildTorrent(info)
    val metadata = TorrentMetadata.fromBencode(data)
    assertEquals(1, metadata.files.size)
    assertEquals("my-dir/readme.txt", metadata.files[0].path)
    assertEquals(100L, metadata.totalBytes)
  }

  @Test
  fun fromBencode_multiFile_totalBytesIsSumOfAllFiles() {
    val files = listOf(
      mapOf(
        "path" to listOf("a.txt".encodeToByteArray()),
        "length" to 100L,
      ),
      mapOf(
        "path" to listOf("b.txt".encodeToByteArray()),
        "length" to 200L,
      ),
      mapOf(
        "path" to listOf("c.txt".encodeToByteArray()),
        "length" to 300L,
      ),
    )
    val info = mapOf(
      "name" to "pack",
      "piece length" to 1024L,
      "files" to files,
      "pieces" to ByteArray(20),
    )
    val data = buildTorrent(info)
    val metadata = TorrentMetadata.fromBencode(data)
    assertEquals(600L, metadata.totalBytes)
    assertEquals(3, metadata.files.size)
  }

  @Test
  fun fromBencode_multiFile_deeplyNestedPath() {
    val files = listOf(
      mapOf(
        "path" to listOf(
          "dir1".encodeToByteArray(),
          "dir2".encodeToByteArray(),
          "dir3".encodeToByteArray(),
          "file.txt".encodeToByteArray(),
        ),
        "length" to 42L,
      ),
    )
    val info = mapOf(
      "name" to "root",
      "piece length" to 256L,
      "files" to files,
      "pieces" to ByteArray(20),
    )
    val data = buildTorrent(info)
    val metadata = TorrentMetadata.fromBencode(data)
    assertEquals(
      "root/dir1/dir2/dir3/file.txt",
      metadata.files[0].path,
    )
  }

  @Test
  fun fromBencode_multiFile_missingPathInEntry_throws() {
    val files = listOf(
      mapOf("length" to 100L), // missing "path"
    )
    val info = mapOf(
      "name" to "pack",
      "piece length" to 1024L,
      "files" to files,
      "pieces" to ByteArray(20),
    )
    val data = buildTorrent(info)
    assertFailsWith<IllegalArgumentException> {
      TorrentMetadata.fromBencode(data)
    }
  }

  @Test
  fun fromBencode_multiFile_missingLengthInEntry_throws() {
    val files = listOf(
      mapOf(
        "path" to listOf("file.txt".encodeToByteArray()),
        // missing "length"
      ),
    )
    val info = mapOf(
      "name" to "pack",
      "piece length" to 1024L,
      "files" to files,
      "pieces" to ByteArray(20),
    )
    val data = buildTorrent(info)
    assertFailsWith<IllegalArgumentException> {
      TorrentMetadata.fromBencode(data)
    }
  }

  @Test
  fun fromBencode_multiFile_fileIndicesAreSequential() {
    val files = listOf(
      mapOf(
        "path" to listOf("a.txt".encodeToByteArray()),
        "length" to 10L,
      ),
      mapOf(
        "path" to listOf("b.txt".encodeToByteArray()),
        "length" to 20L,
      ),
      mapOf(
        "path" to listOf("c.txt".encodeToByteArray()),
        "length" to 30L,
      ),
    )
    val info = mapOf(
      "name" to "pack",
      "piece length" to 256L,
      "files" to files,
      "pieces" to ByteArray(20),
    )
    val data = buildTorrent(info)
    val metadata = TorrentMetadata.fromBencode(data)
    assertEquals(0, metadata.files[0].index)
    assertEquals(1, metadata.files[1].index)
    assertEquals(2, metadata.files[2].index)
  }

  // -- Tracker parsing edge cases --

  @Test
  fun fromBencode_noTrackers() {
    val data = buildTorrent(singleFileInfo())
    val metadata = TorrentMetadata.fromBencode(data)
    assertEquals(0, metadata.trackers.size)
  }

  @Test
  fun fromBencode_announceOnly() {
    val data = buildTorrent(
      singleFileInfo(),
      "announce" to "http://tracker.example.com/announce",
    )
    val metadata = TorrentMetadata.fromBencode(data)
    assertEquals(1, metadata.trackers.size)
    assertEquals(
      "http://tracker.example.com/announce",
      metadata.trackers[0],
    )
  }

  @Test
  fun fromBencode_announceListOverridesAnnounce() {
    // When both "announce" and "announce-list" present,
    // announce-list is used
    val announceList = listOf(
      listOf("http://t1.com".encodeToByteArray()),
      listOf("http://t2.com".encodeToByteArray()),
    )
    val data = buildTorrent(
      singleFileInfo(),
      "announce" to "http://ignored.com",
      "announce-list" to announceList,
    )
    val metadata = TorrentMetadata.fromBencode(data)
    assertEquals(2, metadata.trackers.size)
    assertEquals("http://t1.com", metadata.trackers[0])
    assertEquals("http://t2.com", metadata.trackers[1])
  }

  @Test
  fun fromBencode_announceList_multiTier_flattened() {
    val announceList = listOf(
      listOf(
        "http://tier1-a.com".encodeToByteArray(),
        "http://tier1-b.com".encodeToByteArray(),
      ),
      listOf("http://tier2.com".encodeToByteArray()),
    )
    val data = buildTorrent(
      singleFileInfo(),
      "announce-list" to announceList,
    )
    val metadata = TorrentMetadata.fromBencode(data)
    assertEquals(3, metadata.trackers.size)
  }

  // -- Optional fields --

  @Test
  fun fromBencode_noComment() {
    val data = buildTorrent(singleFileInfo())
    val metadata = TorrentMetadata.fromBencode(data)
    assertNull(metadata.comment)
    assertNull(metadata.createdBy)
  }

  @Test
  fun fromBencode_commentAndCreatedBy() {
    val data = buildTorrent(
      singleFileInfo(),
      "comment" to "My Comment",
      "created by" to "TestTool v1.0",
    )
    val metadata = TorrentMetadata.fromBencode(data)
    assertEquals("My Comment", metadata.comment)
    assertEquals("TestTool v1.0", metadata.createdBy)
  }

  // -- Root-level validation --

  @Test
  fun fromBencode_rootNotDictionary_throws() {
    // Root is a list, not a dict
    val data = Bencode.encode(listOf("not", "a", "dict"))
    assertFailsWith<IllegalArgumentException> {
      TorrentMetadata.fromBencode(data)
    }
  }

  @Test
  fun fromBencode_rootIsInteger_throws() {
    val data = Bencode.encode(42L)
    assertFailsWith<IllegalArgumentException> {
      TorrentMetadata.fromBencode(data)
    }
  }

  @Test
  fun fromBencode_rootIsString_throws() {
    val data = Bencode.encode("not a dict")
    assertFailsWith<IllegalArgumentException> {
      TorrentMetadata.fromBencode(data)
    }
  }

  // -- Info hash consistency --

  @Test
  fun fromBencode_differentInfoDicts_differentHashes() {
    val data1 = buildTorrent(singleFileInfo(name = "file-a.txt"))
    val data2 = buildTorrent(singleFileInfo(name = "file-b.txt"))
    val meta1 = TorrentMetadata.fromBencode(data1)
    val meta2 = TorrentMetadata.fromBencode(data2)
    // Different info dicts must produce different info hashes
    assertTrue(meta1.infoHash != meta2.infoHash)
  }

  @Test
  fun fromBencode_sameInfoDict_differentOuterFields_sameHash() {
    val info = singleFileInfo()
    val data1 = buildTorrent(info, "comment" to "comment1")
    val data2 = buildTorrent(info, "comment" to "comment2")
    val meta1 = TorrentMetadata.fromBencode(data1)
    val meta2 = TorrentMetadata.fromBencode(data2)
    // Info hash is derived only from info dict
    assertEquals(meta1.infoHash, meta2.infoHash)
  }

  private fun assertTrue(condition: Boolean) {
    kotlin.test.assertTrue(condition)
  }
}
