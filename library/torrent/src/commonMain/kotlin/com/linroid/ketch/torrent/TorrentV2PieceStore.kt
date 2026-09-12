package com.linroid.ketch.torrent

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okio.FileSystem
import okio.Path
import okio.use

/** Fresh-directory v2/hybrid storage. Existing destinations require the future import path. */
internal class TorrentV2PieceStore(
  document: TorrentV2Document,
  private val output: Path,
  selectedIds: Set<String>,
  private val buffers: TorrentBufferBudget,
  private val storageSlots: Semaphore,
  private val fileSystem: FileSystem = torrentFileSystem,
) {
  private val verifier = TorrentPayloadVerifier(document)
  private val mapping = TorrentOutputMapping.from(document)
  private val mutex = Mutex()
  private val selected = selectedIds.ifEmpty { mapping.files.map { it.id }.toSet() }.toSet()
  private val filesById = mapping.files.associateBy { it.id }
  private val progress = LongArray(mapping.files.size)
  private val verified: BooleanArray
  private val owned = linkedMapOf<Path, String>()
  private var root: Path? = null
  private var initialized = false
  private var closed = false
  private val totalSelected = document.info.files.indices.sumOf { index ->
    if (mapping.files[index].id in selected) document.info.files[index].length else 0
  }

  init {
    require(output.isAbsolute && output.parent != null)
    TorrentMetadata.validatePathComponent(output.name)
    require(selected.all { it in filesById }) { "Unknown selected file" }
    // The runtime session must admit metadata/layout state before constructing this adapter.
    require(verifier.layout.pieceCount <= 1_000_000) { "Piece index exceeds desktop profile" }
    verified = BooleanArray(verifier.layout.pieceCount.toInt())
  }

  suspend fun initialize() = mutex.withLock {
    check(!closed)
    if (initialized) return@withLock
    storageOperation {
      val destination = root ?: (fileSystem.canonicalize(checkNotNull(output.parent)) / output.name)
      if (root == null) {
        fileSystem.createDirectory(destination, mustCreate = true)
        root = destination
        record(destination)
      }
      validateOwned(destination, directory = true)
      for (file in mapping.files.filter { it.id in selected }) {
        var parent = destination
        for (component in file.components.dropLast(1)) {
          parent /= component
          if (parent !in owned) {
            fileSystem.createDirectory(parent, mustCreate = true)
            record(parent)
          }
          validateOwned(parent, directory = true)
        }
        val path = parent / file.components.last()
        if (path !in owned) {
          fileSystem.openReadWrite(path, mustCreate = true).use { handle ->
            record(path)
            handle.flush()
          }
        } else {
          validateOwned(path, directory = false)
          fileSystem.openReadWrite(path, mustExist = true).use { it.flush() }
        }
        validateOwned(path, directory = false)
      }
    }
    currentCoroutineContext().ensureActive()
    initialized = true
  }

  /** Hash mismatch writes nothing. Verified progress is published only after flush and return. */
  suspend fun commit(index: Int, payload: ByteArray): Boolean = mutex.withLock {
    check(initialized && !closed)
    require(index in verified.indices)
    val extent = verifier.layout.v2Piece(index.toLong())
    val file = filesById.getValue(checkNotNull(extent.fileId))
    require(file.id in selected && payload.size.toLong() == extent.length)
    val lease = checkNotNull(buffers.reserve(payload.size)) { "Payload buffer budget exhausted" }
    try {
      // The caller can reuse/mutate its receive buffer while blocking I/O runs. Hash and write
      // the same privately owned copy, with its reservation retained until the provider returns.
      val bytes = payload.copyOf()
      val verification = verifier.piece(index.toLong())
      verification.update(bytes)
      if (!verification.verify()) return@withLock false
      if (verified[index]) return@withLock true
      storageOperation {
        val path = destination(file)
        validateOwned(path, directory = false)
        fileSystem.openReadWrite(path, mustExist = true).use { handle ->
          handle.write(extent.fileOffset, bytes, 0, bytes.size)
          handle.flush()
        }
      }
      currentCoroutineContext().ensureActive()
      verified[index] = true
      progress[file.v2Index] += extent.length
      true
    } finally {
      lease.close()
    }
  }

  suspend fun progress(): Map<String, Long> = mutex.withLock {
    mapping.files.filter { it.id in selected }.associate { it.id to progress[it.v2Index] }
  }

  suspend fun completed(): Boolean = mutex.withLock {
    initialized && !closed && progress.sum() == totalSelected
  }

  suspend fun verifiedPieces(): BooleanArray = mutex.withLock { verified.copyOf() }

  /** Joining the mutex waits for outstanding provider I/O; no later commits are accepted. */
  suspend fun close() = mutex.withLock { closed = true }

  /** Deletes only paths created by this live store whose identity is unchanged. */
  suspend fun cleanup() = mutex.withLock {
    closed = true
    storageOperation {
      for ((path, identity) in owned.toList().asReversed()) {
        validateParents(path)
        if (torrentFileIdentity(path) != identity) continue
        val metadata = fileSystem.metadataOrNull(path) ?: continue
        if (metadata.isRegularFile || metadata.isDirectory && fileSystem.list(path).isEmpty()) {
          fileSystem.delete(path, mustExist = true)
        }
      }
      owned.clear()
    }
  }

  private suspend fun <T> storageOperation(block: () -> T): T =
    storageSlots.withPermit { withContext(Dispatchers.IO) { block() } }

  private fun destination(file: TorrentOutputMapping.File): Path =
    file.components.fold(checkNotNull(root)) { path, component -> path / component }

  private fun record(path: Path) {
    owned[path] = requireNotNull(torrentFileIdentity(path)) { "Filesystem has no safe identity" }
  }

  private fun validateParents(path: Path) {
    var parent = path.parent
    while (parent != null && parent != root?.parent) {
      validateOwned(parent, directory = true)
      parent = parent.parent
    }
  }

  private fun validateOwned(path: Path, directory: Boolean) {
    val metadata = fileSystem.metadataOrNull(path)
    require(metadata?.symlinkTarget == null && metadata != null &&
      (if (directory) metadata.isDirectory else metadata.isRegularFile) &&
      owned[path] != null && torrentFileIdentity(path) == owned[path]) { "Storage path changed" }
    if (!directory) validateParents(path)
  }
}
