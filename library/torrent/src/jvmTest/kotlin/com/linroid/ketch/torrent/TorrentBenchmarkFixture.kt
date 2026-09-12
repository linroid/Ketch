package com.linroid.ketch.torrent

import java.io.File
import java.security.MessageDigest
import java.util.Random

/** Streaming fixture construction; even the 10 GiB fixture uses one piece-sized payload buffer. */
internal data class TorrentBenchmarkFixture(val metainfo: ByteArray, val sha256: ByteArray) {
  companion object {
    const val PIECE_BYTES: Int = 256 * 1024

    fun create(file: File, bytes: Long): TorrentBenchmarkFixture {
      require(bytes in 1..10L * 1024 * 1024 * 1024)
      val random = Random(162)
      val content = MessageDigest.getInstance("SHA-256")
      val piece = MessageDigest.getInstance("SHA-1")
      val hashes = java.io.ByteArrayOutputStream()
      val buffer = ByteArray(PIECE_BYTES)
      file.outputStream().buffered().use { output ->
        var remaining = bytes
        while (remaining > 0) {
          random.nextBytes(buffer)
          val count = minOf(remaining, buffer.size.toLong()).toInt()
          output.write(buffer, 0, count)
          content.update(buffer, 0, count)
          piece.update(buffer, 0, count)
          hashes.write(piece.digest())
          remaining -= count
        }
      }
      val metainfo = Bencode.encode(mapOf("info" to mapOf(
        "name" to "payload", "length" to bytes, "piece length" to PIECE_BYTES.toLong(),
        "pieces" to hashes.toByteArray()
      )))
      return TorrentBenchmarkFixture(metainfo, content.digest())
    }

    fun digest(file: File): ByteArray {
      val digest = MessageDigest.getInstance("SHA-256")
      val buffer = ByteArray(PIECE_BYTES)
      file.inputStream().buffered().use { input ->
        while (true) {
          val count = input.read(buffer)
          if (count == -1) break
          digest.update(buffer, 0, count)
        }
      }
      return digest.digest()
    }
  }
}
