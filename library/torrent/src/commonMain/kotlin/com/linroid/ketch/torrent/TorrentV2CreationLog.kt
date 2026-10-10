package com.linroid.ketch.torrent

import okio.Buffer
import okio.FileSystem
import okio.Path
import okio.use

/**
 * Single-writer, checksummed creation records in a caller-private directory outside payloads. Its
 * first frame binds it to one task's content and output; [open] accepts any of several bindings,
 * and [rewrite] replaces the log as a whole, binding included.
 */
internal class TorrentV2CreationLog(
  private val path: Path,
  private val fileSystem: FileSystem,
) {
  /** What [open] read: the records, and which of the accepted bindings the log has. */
  class Opened(val records: List<TorrentV2Checkpoint.Owned>, val matched: Int)

  private val records = mutableListOf<TorrentV2Checkpoint.Owned>()
  private val indices = mutableMapOf<List<String>, Int>()
  private var position = 0L
  private var identity: String? = null
  private var binding = ByteArray(0)

  /**
   * Reads the log, which must be bound to one of [bindings]; a missing log is created bound to
   * `bindings[initial]`.
   */
  fun open(bindings: List<ByteArray>, initial: Int = 0): Opened {
    check(position == 0L)
    require(bindings.isNotEmpty() && bindings.all { it.size == 32 } && initial in bindings.indices)
    val parent = checkNotNull(path.parent)
    val metadata = fileSystem.metadata(parent)
    require(metadata.isDirectory && metadata.symlinkTarget == null)
    if (!fileSystem.exists(path)) {
      val temp = parent / (".creation-" + InfoHash.fromBytes(torrentRandomBytes(20)).hex + ".tmp")
      var created: String? = null
      try {
        fileSystem.openReadWrite(temp, mustCreate = true).use { handle ->
          created = requireNotNull(torrentFileIdentity(temp))
          val bytes = frame(bindings[initial])
          handle.write(0, bytes, 0, bytes.size)
          handle.flush()
        }
        torrentPublishCatalogObject(temp, path)
      } finally {
        if (created != null && torrentFileIdentity(temp) == created) {
          fileSystem.delete(temp, mustExist = false)
        }
      }
    }
    val entry = fileSystem.metadata(path)
    require(entry.isRegularFile && entry.symlinkTarget == null)
    val size = requireNotNull(entry.size)
    require(size in 68..MAX_BYTES)
    identity = requireNotNull(torrentFileIdentity(path))
    var first = true
    var matched = -1
    fileSystem.read(path) {
      while (size - position >= 4) {
        val length = readInt()
        require(length in 1..16_384)
        if (size - position - 4 < length + 32) break
        val bytes = readByteArray(length.toLong())
        val checksum = readByteArray(32)
        require(sha256Digest(bytes).contentEquals(checksum)) { "Corrupt creation log frame" }
        if (first) {
          matched = bindings.indexOfFirst { it.contentEquals(bytes) }
          require(matched >= 0) { "Creation log binding mismatch" }
          binding = bytes
          first = false
        } else {
          val row = requireNotNull(Bencode.parse(bytes, 16_384).list)
          require(row.size == 3)
          val reference = requireNotNull(row[0].integer)
          val name = requireNotNull(row[1].bytes).decodeToString(throwOnInvalidSequence = true)
          val id = requireNotNull(row[2].bytes).decodeToString(throwOnInvalidSequence = true)
          val components = if (records.isEmpty()) {
            require(reference == 0L && name.isEmpty())
            emptyList()
          } else {
            require(reference != 0L && reference in -records.size.toLong()..records.size.toLong())
            val parentRecord = records[kotlin.math.abs(reference).toInt() - 1]
            require(parentRecord.directory)
            parentRecord.components + name
          }
          accept(TorrentV2Checkpoint.Owned(components, id, reference >= 0))
        }
        position += length + 36
      }
    }
    require(!first) { "Incomplete creation log binding" }
    validateIdentity()
    if (position < size) fileSystem.openReadWrite(path, mustExist = true).use {
      it.resize(position)
      it.flush()
    }
    return Opened(records.toList(), matched)
  }

  fun records(): List<TorrentV2Checkpoint.Owned> = records.toList()

  /**
   * A failed append retains its offset so a retry overwrites the incomplete tail. A path whose
   * claim changed, created again after it was deleted, replaces its stale claim and the claims
   * below it, which [rewrite] drops first.
   */
  fun append(record: TorrentV2Checkpoint.Owned) {
    check(position > 0)
    val existing = indices[record.components]
    if (existing != null) {
      if (records[existing] == record) return
      val stale = record.components
      rewrite(binding, records.filterNot {
        it.components.size >= stale.size && it.components.subList(0, stale.size) == stale
      })
    }
    val bytes = encode(record)
    require(bytes.size <= MAX_BYTES - position) { "Creation log exceeds byte limit" }
    validateIdentity()
    fileSystem.openReadWrite(path, mustExist = true).use { handle ->
      handle.write(position, bytes, 0, bytes.size)
      handle.resize(position + bytes.size)
      handle.flush()
    }
    position += bytes.size
    accept(record)
  }

  /** [record]'s frame, its parent referenced by its place among the records before it. */
  private fun encode(record: TorrentV2Checkpoint.Owned): ByteArray {
    validate(record)
    val parent = if (record.components.isEmpty()) 0 else
      indices.getValue(record.components.dropLast(1)) + 1
    return frame(Bencode.encode(listOf(
      if (record.directory) parent.toLong() else -parent.toLong(),
      record.components.lastOrNull() ?: "", record.identity
    ), maxBytes = 16_384))
  }

  private fun accept(record: TorrentV2Checkpoint.Owned) {
    validate(record)
    val copy = record.copy(components = record.components.toList())
    indices[copy.components] = records.size
    records += copy
  }

  private fun validate(record: TorrentV2Checkpoint.Owned) {
    require(records.size < 220_000 && record.components.size <= 64)
    require(record.identity.length in 1..512 && record.components !in indices)
    record.components.forEach(TorrentMetadata::validatePathComponent)
    if (record.components.isEmpty()) require(records.isEmpty() && record.directory) else {
      val parent = requireNotNull(indices[record.components.dropLast(1)])
      require(records[parent].directory)
    }
  }

  /**
   * Replaces the log with one bound to [binding] that holds [records], parents before children:
   * written beside it, then moved over it, so a crash leaves the old log or the new one whole.
   */
  fun rewrite(binding: ByteArray, records: List<TorrentV2Checkpoint.Owned>) {
    check(position > 0)
    require(binding.size == 32)
    validateIdentity()
    // Validated and encoded against a scratch index before anything is written.
    val staged = TorrentV2CreationLog(path, fileSystem)
    val output = Buffer().write(frame(binding))
    for (record in records) {
      output.write(staged.encode(record))
      staged.accept(record)
    }
    val bytes = output.readByteArray()
    require(bytes.size <= MAX_BYTES) { "Creation log exceeds byte limit" }
    val parent = checkNotNull(path.parent)
    val temp = parent / (".creation-" + InfoHash.fromBytes(torrentRandomBytes(20)).hex + ".tmp")
    var created: String? = null
    try {
      fileSystem.openReadWrite(temp, mustCreate = true).use { handle ->
        created = requireNotNull(torrentFileIdentity(temp))
        handle.write(0, bytes, 0, bytes.size)
        handle.flush()
      }
      fileSystem.atomicMove(temp, path)
    } finally {
      if (created != null && fileSystem.exists(temp) && torrentFileIdentity(temp) == created) {
        fileSystem.delete(temp, mustExist = false)
      }
    }
    this.records.clear()
    indices.clear()
    staged.records.forEach(::accept)
    this.binding = binding
    position = bytes.size.toLong()
    identity = requireNotNull(torrentFileIdentity(path))
  }

  fun delete() {
    validateIdentity()
    fileSystem.delete(path, mustExist = true)
  }

  private fun validateIdentity() {
    val metadata = fileSystem.metadata(path)
    require(metadata.isRegularFile && metadata.symlinkTarget == null &&
      torrentFileIdentity(path) == identity) { "Creation log changed" }
  }

  private fun frame(bytes: ByteArray): ByteArray = Buffer().writeInt(bytes.size).write(bytes)
    .write(sha256Digest(bytes)).readByteArray()

  companion object {
    private const val MAX_BYTES = 32L * 1024 * 1024
  }
}
