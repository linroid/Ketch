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
  val trackerConfiguration: TrackerConfiguration? = null,
  val trackerRevision: Long = 0,
) {
  init {
    require(trackerRevision >= 0)
    require(trackerRevision == 0L || trackerConfiguration != null)
  }

  private val version: Long
    get() = if (trackerRevision > 0) 3L else if (trackerConfiguration != null) 2L else 1L

  fun encode(): ByteArray = Bencode.encode(mapOf(
    "received" to receivedBytes, "uploaded" to uploadedBytes,
    "kind" to KIND, "version" to version, "task" to taskId,
    "metainfo" to metadata.metainfoBytes, "hash" to metadata.infoHash.toBytes(),
    "output" to output, "selected" to selected.sorted().map { it.toLong() },
    "verified" to pieceBitfield(verified),
    "files" to files.map { mapOf("path" to it.path, "identity" to it.identity) },
    "directories" to directories.map { mapOf("path" to it.path, "identity" to it.identity) }
  ) + (trackerConfiguration?.let { mapOf("tracker-tiers" to it.tiers) } ?: emptyMap()) +
    (if (trackerRevision > 0) mapOf("tracker-revision" to trackerRevision) else emptyMap()),
    maxBytes = MAX_BYTES - MAGIC.size).let { MAGIC + it }.also { require(it.size <= MAX_BYTES) }

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
      val version = root["version"]?.integer
      require(version in 1L..3L) { "Unsupported torrent checkpoint version" }
      val trackerRevision = if (version == 3L) {
        requireNotNull(root["tracker-revision"]?.integer).also { require(it > 0) }
      } else {
        require(root["tracker-revision"] == null) {
          "Tracker revision requires checkpoint version 3"
        }
        0L
      }
      val trackerConfiguration = if (version == 2L || version == 3L) {
        val tiers = requireNotNull(root["tracker-tiers"]?.list)
        require(tiers.size <= 256)
        var entries = 0
        TrackerConfiguration.prepare(tiers.map { tier ->
          val urls = requireNotNull(tier.list)
          entries += urls.size
          require(entries <= 256)
          urls.map { url ->
            require(requireNotNull(url.bytes).size <= 32_768)
            requireNotNull(url.text())
          }
        })
      } else {
        require(root["tracker-tiers"] == null) { "Tracker override requires checkpoint version 2" }
        null
      }
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
        owned("files"), owned("directories"), receivedBytes, uploadedBytes,
        trackerConfiguration, trackerRevision)
    }
  }
}

/** Stable OS file identity, read without following a symlink. Null means ownership is unprovable. */
internal expect fun torrentFileIdentity(path: Path): String?
