package com.linroid.ketch.torrent

import okio.ByteString
import okio.ByteString.Companion.toByteString

/** Matching hybrid layouts retain original v1 indices and represent padding as zero spans. */
internal class TorrentHybridLayout private constructor(
  val identity: TorrentIdentity,
  val files: List<File>,
  val padding: List<Padding>,
  val totalV1Bytes: Long,
  val pieceHashes: ByteString,
) {
  data class File(val v1Index: Int, val v2Index: Int, val offset: Long, val length: Long)
  data class Padding(val offset: Long, val length: Long)

  companion object {
    /** [node] must be the info node from the same authenticated document as [v2]. */
    fun parse(node: Bencode.Node, v2: TorrentV2Info): TorrentHybridLayout? {
      if (node["pieces"] == null && node["files"] == null && node["length"] == null) return null
      val hashes = requireNotNull(node["pieces"]?.bytes) { "Missing hybrid v1 hashes" }
      val name = requireNotNull(node["name"]?.bytes) { "Missing hybrid v1 name" }.toByteString()
      require(name.size > 0)
      val listed = node["files"]
      require((listed != null) != (node["length"] != null)) { "Conflicting hybrid v1 layouts" }
      val entries = if (listed == null) listOf(node) else
        requireNotNull(listed.list) { "Invalid hybrid file list" }
      val files = mutableListOf<File>()
      val padding = mutableListOf<Padding>()
      var offset = 0L
      for ((index, entry) in entries.withIndex()) {
        requireNotNull(entry.dictionary) { "Invalid hybrid file entry" }
        val attributes = entry["attr"]?.let {
          requireNotNull(it.bytes) { "Invalid hybrid file attributes" }
        } ?: byteArrayOf()
        require(!attributes.contains('l'.code.toByte())) { "Symlink files unsupported" }
        val length = requireNotNull(entry["length"]?.integer) { "Missing hybrid file length" }
        require(length >= 0 && length <= Long.MAX_VALUE - offset) { "Hybrid length overflow" }
        if (attributes.contains('p'.code.toByte())) {
          val remainder = offset % v2.pieceLength
          require(listed != null && remainder != 0L && length == v2.pieceLength - remainder) {
            "Hybrid padding must fill the preceding piece"
          }
          // BEP 47 permits padding paths to be omitted. They are never output destinations.
          padding += Padding(offset, length)
        } else {
          val path = if (listed == null) listOf(name) else {
            requireNotNull(entry["path"]?.list) { "Missing hybrid file path" }.map {
              requireNotNull(it.bytes) { "Invalid hybrid path component" }.toByteString()
            }
          }
          val expected = v2.files.getOrNull(files.size)
          require(expected != null && expected.path == path && expected.length == length) {
            "Hybrid filenames, order, or lengths differ"
          }
          require(length == 0L || offset % v2.pieceLength == 0L) { "Hybrid file is not aligned" }
          files += File(index, files.size, offset, length)
        }
        offset += length
      }
      require(files.size == v2.files.size) { "Hybrid file list is incomplete" }
      val pieces = offset / v2.pieceLength + if (offset % v2.pieceLength == 0L) 0 else 1
      require(hashes.size % 20 == 0 && hashes.size.toLong() / 20 == pieces) {
        "Hybrid v1 hash count differs from layout"
      }
      return TorrentHybridLayout(TorrentIdentity(
        InfoHash.fromBytes(sha1Digest(v2.rawInfo.toByteArray())), v2.hash),
        files.toList(), padding.toList(), offset, hashes.toByteString())
    }
  }
}
