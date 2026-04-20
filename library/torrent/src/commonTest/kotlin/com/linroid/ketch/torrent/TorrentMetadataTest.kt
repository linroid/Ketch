package com.linroid.ketch.torrent

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class TorrentMetadataTest {

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
