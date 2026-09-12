package com.linroid.ketch.torrent

import okio.ByteString
import okio.ByteString.Companion.toByteString

/**
 * Validated v2 fields from a raw info dictionary, as received by BEP 9 or extracted from metainfo.
 * This does not validate external piece layers, hybrid consistency, or filesystem output paths.
 */
internal data class TorrentV2Info(
  val hash: V2InfoHash,
  val rawInfo: ByteString,
  val pieceLength: Long,
  val files: List<File>,
  val totalBytes: Long,
  val privateTorrent: Boolean,
  val displayName: ByteString?,
) {
  /** Original binary path components and the optional root of a non-empty file. */
  data class File(val path: List<ByteString>, val length: Long, val piecesRoot: ByteString?)

  companion object {
    /** Callers must reserve parser memory before invoking this bounded structural decoder. */
    fun parse(
      rawInfo: ByteArray,
      maxBytes: Int = 4 * 1024 * 1024,
      maxFiles: Int = 10_000,
      maxNodes: Int = 100_000,
      expectedIdentity: TorrentIdentity? = null,
    ): TorrentV2Info {
      require(maxBytes in 1..32 * 1024 * 1024)
      require(maxFiles in 1..100_000)
      require(rawInfo.size <= maxBytes) { "Raw info exceeds byte limit" }
      require(expectedIdentity == null || expectedIdentity.matchesInfo(rawInfo)) {
        "Exact topic hash mismatch"
      }
      val info = Bencode.parse(rawInfo, maxBytes, maxNodes)
      requireNotNull(info.dictionary) { "Info must be a dictionary" }
      val version = requireNotNull(info["meta version"]?.integer) { "Missing meta version" }
      if (version != 2L) throw UnsupportedTorrentMetaVersion(version)
      val pieceLength = requireNotNull(info["piece length"]?.integer) { "Missing piece length" }
      require(pieceLength >= 16_384 && pieceLength and (pieceLength - 1) == 0L) {
        "V2 piece length must be a power of two and at least 16 KiB"
      }
      val tree = requireNotNull(info["file tree"]) { "Missing file tree" }
      val files = mutableListOf<File>()
      var total = 0L
      fun visit(node: Bencode.Node, path: List<ByteString>) {
        val entries = requireNotNull(node.dictionary) { "File tree node must be a dictionary" }
        val properties = node[""]
        if (properties != null) {
          require(path.isNotEmpty() && entries.size == 1) { "File overlaps directory children" }
          requireNotNull(properties.dictionary) { "File properties must be a dictionary" }
          val attributes = properties["attr"]?.let {
            requireNotNull(it.bytes) { "File attributes must be a byte string" }
          }
          require(attributes?.contains('l'.code.toByte()) != true) { "Symlink files unsupported" }
          val length = requireNotNull(properties["length"]?.integer) { "Missing file length" }
          require(length >= 0 && length <= Long.MAX_VALUE - total) { "Invalid total file length" }
          val root = properties["pieces root"]?.let {
            requireNotNull(it.bytes) { "Pieces root must be a byte string" }.toByteString()
          }
          require(if (length == 0L) root == null else root?.size == 32) { "Invalid pieces root" }
          require(files.size < maxFiles) { "V2 file count exceeds limit" }
          files += File(path, length, root)
          total += length
        } else {
          require(entries.isNotEmpty()) { "Empty directory node in file tree" }
          for ((component, child) in entries) visit(child, path + component)
        }
      }
      visit(tree, emptyList())
      require(files.isNotEmpty()) { "File tree contains no files" }
      val privateFlag = info["private"]?.let {
        requireNotNull(it.integer) { "Private flag must be an integer" }
      } ?: 0L
      require(privateFlag in 0..1) { "Invalid private flag" }
      val name = info["name"]?.let {
        requireNotNull(it.bytes) { "Name must be a byte string" }.toByteString()
      }
      return TorrentV2Info(V2InfoHash.fromBytes(sha256Digest(rawInfo)), rawInfo.toByteString(),
        pieceLength, files, total, privateFlag == 1L, name)
    }
  }
}

internal class UnsupportedTorrentMetaVersion(val version: Long) :
  IllegalArgumentException("Unsupported torrent meta version: $version")
