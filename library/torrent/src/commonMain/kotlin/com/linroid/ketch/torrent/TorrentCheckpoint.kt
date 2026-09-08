package com.linroid.ketch.torrent

import okio.Path
import okio.Path.Companion.toPath

internal data class TorrentOwnedPath(val path: String, val identity: String)

/** Versioned Kotlin state. The bitmap is a hint; recovery always rehashes the actual output. */
internal data class TorrentCheckpoint(
  val taskId: String,
  val metadata: TorrentMetadata,
  val output: String,
  val selected: Set<Int>,
  val verified: BooleanArray,
  val files: List<TorrentOwnedPath>,
  val directories: List<TorrentOwnedPath>,
  val receivedBytes: Long = 0,
  val uploadedBytes: Long = 0,
) {
  fun encode(): ByteArray = Bencode.encode(mapOf(
    "received" to receivedBytes, "uploaded" to uploadedBytes,
    "kind" to KIND, "version" to 1L, "task" to taskId,
    "metainfo" to metadata.metainfoBytes, "hash" to metadata.infoHash.toBytes(),
    "output" to output, "selected" to selected.sorted().map { it.toLong() },
    "verified" to pieceBitfield(verified),
    "files" to files.map { mapOf("path" to it.path, "identity" to it.identity) },
    "directories" to directories.map { mapOf("path" to it.path, "identity" to it.identity) }
  ), maxBytes = MAX_BYTES - MAGIC.size).let { MAGIC + it }.also { require(it.size <= MAX_BYTES) }

  companion object {
    const val MAX_BYTES = 16 * 1024 * 1024
    private val MAGIC = "KETCH-TORRENT\n".encodeToByteArray()
    private const val KIND = "ketch-kotlin-torrent"

    /** Returns null for legacy native blobs. Malformed recognized Kotlin state is an error. */
    fun decode(bytes: ByteArray): TorrentCheckpoint? {
      if (bytes.size < MAGIC.size || !bytes.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)) {
        return null
      }
      require(bytes.size <= MAX_BYTES)
      val root = Bencode.parse(bytes.copyOfRange(MAGIC.size, bytes.size), MAX_BYTES, 1_000_000)
      require(root["kind"]?.text() == KIND)
      require(root["version"]?.integer == 1L) { "Unsupported torrent checkpoint version" }
      val metadata = TorrentMetadata.fromBencode(requireNotNull(root["metainfo"]?.bytes))
      require(root["hash"]?.bytes?.contentEquals(metadata.infoHash.toBytes()) == true)
      val task = requireNotNull(root["task"]?.text())
      require(task.isNotEmpty() && task.length <= 128 && task.all { it.isLetterOrDigit() || it == '-' })
      val output = requireNotNull(root["output"]?.text())
      require(output.length <= 8192 && output.toPath().isAbsolute)
      val selection = requireNotNull(root["selected"]?.list)
      require(selection.size <= metadata.files.size)
      val selected = selection.map { item ->
        val index = requireNotNull(item.integer)
        require(index in 0 until metadata.files.size.toLong())
        index.toInt()
      }.toSet()
      require(selected.size == selection.size)
      val count = metadata.pieceHashes.size / 20
      val bits = requireNotNull(root["verified"]?.bytes)
      require(bits.size == (count + 7) / 8)
      if (count % 8 != 0) require(bits.last().toInt() and ((1 shl (8 - count % 8)) - 1) == 0)
      val verified = BooleanArray(count) { bits[it / 8].toInt() and (128 ushr (it % 8)) != 0 }
      fun owned(key: String): List<TorrentOwnedPath> {
        val list = requireNotNull(root[key]?.list)
        require(list.size <= 250_000)
        return list.map {
          val path = requireNotNull(it["path"]?.text())
          val identity = requireNotNull(it["identity"]?.text())
          require(path.length <= 8192 && path.toPath().isAbsolute && identity.length in 1..512)
          TorrentOwnedPath(path, identity)
        }.also { paths -> require(paths.map { it.path }.distinct().size == paths.size) }
      }
      val receivedBytes = root["received"]?.integer ?: 0L
      val uploadedBytes = root["uploaded"]?.integer ?: 0L
      require(receivedBytes >= 0 && uploadedBytes >= 0)
      return TorrentCheckpoint(task, metadata, output, selected, verified,
        owned("files"), owned("directories"), receivedBytes, uploadedBytes)
    }
  }
}

/** Stable OS file identity, read without following a symlink. Null means ownership is unprovable. */
internal expect fun torrentFileIdentity(path: Path): String?
