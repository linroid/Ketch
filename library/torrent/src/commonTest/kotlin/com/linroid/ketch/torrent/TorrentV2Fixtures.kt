package com.linroid.ketch.torrent

import kotlinx.coroutines.sync.Semaphore
import okio.Buffer
import okio.ByteString.Companion.toByteString
import okio.Path

/**
 * A valid v2 or hybrid torrent built from file lengths, with deterministic payloads. Hybrids lay
 * out BEP 47 padding after every file but the last, as v1 peers expect it.
 */
internal class TorrentV2Fixture(
  val document: TorrentV2Document,
  val metainfo: ByteArray,
  /** File names in v2 order. */
  val names: List<String>,
  /** File payloads in v2 order. */
  val payloads: List<ByteArray>,
  val pieceLength: Int,
) {
  val layout: TorrentContentLayout = TorrentContentLayout.from(document.info, document.hybrid)

  /** The v1 byte stream: every file followed by its zero padding. */
  val v1Bytes: ByteArray by lazy {
    val output = ByteArray(layout.protocolBytes.toInt())
    for (file in layout.files) payloads[file.v2Index].copyInto(output, file.offset.toInt())
    output
  }

  /** The bytes of v1 piece [index], padding included. */
  fun v1Piece(index: Int): ByteArray {
    val start = index * pieceLength
    return v1Bytes.copyOfRange(start, minOf(start + pieceLength, v1Bytes.size))
  }

  /** The bytes of v2 piece [index]: its file's extent, without padding. */
  fun v2Piece(index: Int): ByteArray {
    val extent = layout.v2Piece(index.toLong())
    val file = layout.files.first { it.id == extent.fileId }
    return payloads[file.v2Index].copyOfRange(extent.fileOffset.toInt(),
      (extent.fileOffset + extent.length).toInt())
  }

  /** Commits [pieces], every piece by default, into an initialized [store]. */
  suspend fun seed(
    store: TorrentV2PieceStore,
    pieces: Iterable<Int> = 0 until layout.pieceCount.toInt(),
  ) {
    for (index in pieces) {
      check(store.commit(index, v2Piece(index)) == CommitOutcome.VERIFIED) {
        "Piece $index rejected"
      }
    }
  }

  /**
   * Writes the whole payload to [output] as an owner of [taskId] would, and returns the
   * checkpoint that lets an engine owner adopt it: a seed without downloading.
   */
  suspend fun preseed(output: Path, taskId: String): TorrentV2Checkpoint {
    val store = TorrentV2PieceStore(document, output, emptySet(), taskId,
      TorrentBufferBudget(4 * 1024 * 1024), Semaphore(1))
    try {
      store.initialize()
      seed(store)
      return store.checkpoint()
    } finally {
      store.close()
    }
  }

  companion object {
    /** [files] are names and lengths; v2 orders files by name, so they must be sorted. */
    fun build(
      files: List<Pair<String, Int>>,
      pieceLength: Int = 32_768,
      hybrid: Boolean = false,
      privateTorrent: Boolean = false,
      seed: Int = 1,
      name: String = "pack",
    ): TorrentV2Fixture {
      require(files.map { it.first } == files.map { it.first }.sorted())
      require(files.all { it.second > 0 })
      val payloads = files.mapIndexed { fileIndex, (_, length) ->
        ByteArray(length) { (it * 31 + seed * 7 + fileIndex * 13 + it / 251).toByte() }
      }
      val roots = payloads.map { merkleNodes(it).last().single() }
      val tree = files.mapIndexed { index, (file, length) ->
        file to mapOf("" to mapOf("length" to length.toLong(), "pieces root" to roots[index]))
      }.toMap()
      val info = mutableMapOf<String, Any>("meta version" to 2L, "name" to name,
        "piece length" to pieceLength.toLong(), "file tree" to tree)
      if (privateTorrent) info["private"] = 1L
      if (hybrid) {
        val entries = mutableListOf<Map<String, Any>>()
        val stream = Buffer()
        for ((index, file) in files.withIndex()) {
          entries += mapOf("path" to listOf(file.first), "length" to file.second.toLong())
          stream.write(payloads[index])
          val gap = (pieceLength - file.second % pieceLength) % pieceLength
          if (index < files.lastIndex && gap > 0) {
            entries += mapOf("attr" to "p", "path" to listOf(".pad", gap.toString()),
              "length" to gap.toLong())
            stream.write(ByteArray(gap))
          }
        }
        val bytes = stream.readByteArray()
        info["files"] = entries
        info["pieces"] = (bytes.indices step pieceLength).fold(ByteArray(0)) { hashes, start ->
          hashes + sha1Digest(bytes.copyOfRange(start, minOf(start + pieceLength, bytes.size)))
        }
      }
      val layers = payloads.withIndex().filter { it.value.size > pieceLength }.associate {
        roots[it.index].toByteString() to pieceLayer(it.value, pieceLength)
      }
      val metainfo = Bencode.encode(mapOf("info" to info, "piece layers" to layers))
      return TorrentV2Fixture(TorrentV2Document.parse(metainfo), metainfo,
        files.map { it.first }, payloads, pieceLength)
    }

    /** Every level of [payload]'s BEP 52 tree, leaves first: padding leaves are zero hashes. */
    fun merkleNodes(payload: ByteArray): List<List<ByteArray>> {
      val blocks = (payload.size + 16_383) / 16_384
      var width = 1
      while (width < blocks) width *= 2
      var row = (0 until width).map { index ->
        if (index >= blocks) ByteArray(32) else sha256Digest(payload.copyOfRange(index * 16_384,
          minOf((index + 1) * 16_384, payload.size)))
      }
      val levels = mutableListOf(row)
      while (row.size > 1) {
        row = row.chunked(2).map { Sha256().update(it[0]).update(it[1]).digest() }
        levels += row
      }
      return levels
    }

    private fun pieceLayer(payload: ByteArray, pieceLength: Int): ByteArray {
      val levels = merkleNodes(payload)
      val pieces = (payload.size + pieceLength - 1) / pieceLength
      val layer = levels[(pieceLength / 16_384).countTrailingZeroBits()]
      return layer.take(pieces).fold(ByteArray(0)) { hashes, hash -> hashes + hash }
    }
  }
}
