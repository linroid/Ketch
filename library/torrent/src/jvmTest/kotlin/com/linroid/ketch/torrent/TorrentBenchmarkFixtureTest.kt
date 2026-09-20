package com.linroid.ketch.torrent

import java.nio.file.Files
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class TorrentBenchmarkFixtureTest {
  @Test
  fun streamingFixtureIncludesTheShortFinalPieceAndDistinctPieceContent() {
    val root = Files.createTempDirectory("benchmark-fixture").toFile()
    try {
      val file = root.resolve("payload")
      val bytes = TorrentBenchmarkFixture.PIECE_BYTES.toLong() * 2 + 37
      val fixture = TorrentBenchmarkFixture.create(file, bytes)
      val metadata = TorrentMetadata.fromBencode(fixture.metainfo)
      assertEquals(bytes, file.length())
      assertEquals(bytes, metadata.totalBytes)
      assertContentEquals(fixture.sha256, TorrentBenchmarkFixture.digest(file))
      val payload = file.readBytes() // This small test alone reads its bounded fixture into memory.
      val hashes = payload.asList().chunked(TorrentBenchmarkFixture.PIECE_BYTES).flatMap {
        MessageDigest.getInstance("SHA-1").digest(it.toByteArray()).asList()
      }.toByteArray()
      assertContentEquals(hashes, metadata.pieceHashes)
      assertFalse(hashes.copyOfRange(0, 20).contentEquals(hashes.copyOfRange(20, 40)))
      assertContentEquals(fixture.sha256,
        TorrentBenchmarkFixture.create(root.resolve("again"), bytes).sha256)
    } finally { root.deleteRecursively() }
  }
}
