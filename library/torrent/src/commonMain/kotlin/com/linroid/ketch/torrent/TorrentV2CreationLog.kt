package com.linroid.ketch.torrent

import okio.Buffer
import okio.FileSystem
import okio.Path
import okio.use

/** Single-writer, checksummed creation records in a caller-private directory outside payloads. */
internal class TorrentV2CreationLog(
  private val path: Path,
  private val binding: ByteArray,
  private val fileSystem: FileSystem,
) {
  private val records = mutableListOf<TorrentV2Checkpoint.Owned>()
  private val indices = mutableMapOf<List<String>, Int>()
  private var position = 0L
  private var identity: String? = null

  fun open(): List<TorrentV2Checkpoint.Owned> {
    check(position == 0L)
    require(binding.size == 32)
    val parent = checkNotNull(path.parent)
    val metadata = fileSystem.metadata(parent)
    require(metadata.isDirectory && metadata.symlinkTarget == null)
    if (!fileSystem.exists(path)) {
      val temp = parent / (".creation-" + InfoHash.fromBytes(torrentRandomBytes(20)).hex + ".tmp")
      var created: String? = null
      try {
        fileSystem.openReadWrite(temp, mustCreate = true).use { handle ->
          created = requireNotNull(torrentFileIdentity(temp))
          val bytes = frame(binding)
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
    fileSystem.read(path) {
      while (size - position >= 4) {
        val length = readInt()
        require(length in 1..16_384)
        if (size - position - 4 < length + 32) break
        val bytes = readByteArray(length.toLong())
        val checksum = readByteArray(32)
        require(sha256Digest(bytes).contentEquals(checksum)) { "Corrupt creation log frame" }
        if (first) {
          require(bytes.contentEquals(binding)) { "Creation log binding mismatch" }
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
    return records.toList()
  }

  /** A failed append retains its offset so a retry overwrites the incomplete tail. */
  fun append(record: TorrentV2Checkpoint.Owned) {
    check(position > 0)
    val existing = indices[record.components]
    if (existing != null) {
      require(records[existing] == record) { "Creation ownership changed" }
      return
    }
    validate(record)
    val parent = if (record.components.isEmpty()) 0 else
      indices.getValue(record.components.dropLast(1)) + 1
    val bytes = frame(Bencode.encode(listOf(
      if (record.directory) parent.toLong() else -parent.toLong(),
      record.components.lastOrNull() ?: "", record.identity
    ), maxBytes = 16_384))
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
