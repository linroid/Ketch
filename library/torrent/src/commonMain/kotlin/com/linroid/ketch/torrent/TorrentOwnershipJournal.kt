package com.linroid.ketch.torrent

import okio.Buffer
import okio.FileSystem
import okio.Path
import okio.use

/** Append-only ownership records survive a process exit before TaskStore publishes a checkpoint. */
internal class TorrentOwnershipJournal(
  private val path: Path,
  private val binding: ByteArray,
  private val fileSystem: FileSystem,
) {
  private var position = 0L
  private var compactedAt = 0L
  val needsCompaction: Boolean get() = position - compactedAt >= 1024 * 1024 ||
    position >= MAX_BYTES - 1024 * 1024

  fun load(): List<Pair<Boolean, TorrentOwnedPath>> {
    position = 0
    if (!fileSystem.exists(path)) return emptyList()
    require(fileSystem.metadata(path).isRegularFile && fileSystem.metadata(path).symlinkTarget == null)
    val size = fileSystem.metadata(path).size ?: 0
    require(size <= MAX_BYTES)
    val bytes = fileSystem.read(path) { readByteArray() }
    val input = Buffer().write(bytes)
    val records = mutableListOf<Pair<Boolean, TorrentOwnedPath>>()
    var first = true
    while (input.size >= 4) {
      val length = input.readInt()
      require(length in 1..16_384)
      if (input.size < length) break // Discard a torn final append; never infer ownership from it.
      val value = input.readByteArray(length.toLong())
      if (first) {
        require(value.contentEquals(binding)) { "Ownership journal belongs to another task" }
        first = false
      } else {
        val node = Bencode.parse(value, 16_384)
        val directory = node["directory"]?.integer
        require(directory == 0L || directory == 1L)
        val name = requireNotNull(node["path"]?.text())
        val identity = requireNotNull(node["identity"]?.text())
        require(name.length <= 8192 && identity.length in 1..512)
        records += (directory == 1L) to TorrentOwnedPath(name, identity)
        require(records.size <= 250_000)
      }
      position += length + 4
    }
    require(!first) { "Ownership journal header is incomplete" }
    if (position < size) fileSystem.openReadWrite(path, mustExist = true).use {
      it.resize(position)
      it.flush()
    }
    return records
  }

  fun initialize() {
    if (position != 0L) return
    require(!fileSystem.exists(path)) { "Ownership journal was not loaded" }
    val temp = checkNotNull(path.parent) /
      ("ownership-init-" + InfoHash.fromBytes(torrentRandomBytes(20)).hex + ".tmp")
    var created = false
    try {
      fileSystem.openReadWrite(temp, mustCreate = true).use { handle ->
        created = true
        val bytes = Buffer().writeInt(binding.size).write(binding).readByteArray()
        handle.write(0, bytes, 0, bytes.size)
        handle.flush()
        position = bytes.size.toLong()
      }
      fileSystem.atomicMove(temp, path)
    } finally {
      if (created) fileSystem.delete(temp, mustExist = false)
    }
  }

  fun append(directory: Boolean, owned: TorrentOwnedPath) {
    val data = Bencode.encode(mapOf("directory" to if (directory) 1L else 0L,
      "path" to owned.path, "identity" to owned.identity))
    require(data.size <= 16_384 && data.size + 4 <= MAX_BYTES - position)
    val bytes = Buffer().writeInt(data.size).write(data).readByteArray()
    fileSystem.openReadWrite(path, mustExist = true).use { handle ->
      handle.write(position, bytes, 0, bytes.size)
      handle.flush()
      position += bytes.size
    }
  }

  /** Atomically replaces stale creation records with the current ownership set. */
  fun compact(records: List<Pair<Boolean, TorrentOwnedPath>>): String {
    val temp = checkNotNull(path.parent) /
      ("ownership-init-" + InfoHash.fromBytes(torrentRandomBytes(20)).hex + ".tmp")
    var created = false
    var written = 0L
    var identity: String? = null
    try {
      fileSystem.openReadWrite(temp, mustCreate = true).use { handle ->
        created = true
        identity = requireNotNull(torrentFileIdentity(temp))
        fun frame(bytes: ByteArray) {
          require(bytes.size <= 16_384 && written + bytes.size + 4 <= MAX_BYTES)
          val framed = Buffer().writeInt(bytes.size).write(bytes).readByteArray()
          handle.write(written, framed, 0, framed.size)
          written += framed.size
        }
        frame(binding)
        val current = records.filter { it.second.path != path.toString() } +
          (false to TorrentOwnedPath(path.toString(), identity!!))
        for ((directory, owned) in current) {
          frame(Bencode.encode(mapOf("directory" to if (directory) 1L else 0L,
            "path" to owned.path, "identity" to owned.identity)))
        }
        handle.flush()
      }
      fileSystem.atomicMove(temp, path)
      position = written
      compactedAt = position
      return checkNotNull(identity)
    } finally {
      if (created) fileSystem.delete(temp, mustExist = false)
    }
  }

  companion object {
    private const val MAX_BYTES = 32L * 1024 * 1024
  }
}
