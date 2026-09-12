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

/**
 * Atomic task snapshots in an existing caller-private state directory. The runtime must provide
 * one writer per task; the mutex serializes this instance, not other processes or instances.
 * File flush plus atomic replacement protects readers from partial snapshots, but does not yet
 * guarantee directory-entry persistence after power loss.
 */
internal class TorrentCheckpointFile(
  private val path: Path,
  private val taskId: String,
  private val catalog: TorrentContentCatalog,
  private val fileSystem: FileSystem = torrentFileSystem,
  private val slots: Semaphore = Semaphore(1),
) {
  private val mutex = Mutex()
  private var parentIdentity: String? = null

  init {
    require(path.isAbsolute && path.parent != null)
    TorrentMetadata.validatePathComponent(path.name)
    require(taskId.length in 1..128 && taskId.all { it.isLetterOrDigit() || it == '-' })
  }

  class Loaded(val checkpoint: TorrentV2Checkpoint, val document: TorrentV2Document)

  /** Missing state is distinct from malformed state or missing/corrupt catalog content. */
  suspend fun load(): Loaded? = mutex.withLock {
    val checkpoint = operation { read(target()) } ?: return@withLock null
    val document = requireNotNull(catalog.get(checkpoint.identity)) { "Missing checkpoint content" }
    checkpoint.validateContent(document)
    Loaded(checkpoint, document)
  }

  /** Catalog content is published before a task can publish a reference to it. */
  suspend fun save(document: TorrentV2Document, checkpoint: TorrentV2Checkpoint) = mutex.withLock {
    require(checkpoint.taskId == taskId) { "Checkpoint task mismatch" }
    checkpoint.validateContent(document)
    val bytes = checkpoint.encode()
    catalog.put(document)
    val context = currentCoroutineContext()
    operation {
      val target = target()
      val previous = read(target)
      if (previous != null) {
        previous.validateContent(document)
        require(previous.identity == checkpoint.identity && previous.output == checkpoint.output) {
          "Checkpoint binding changed"
        }
        require(checkpoint.layoutGeneration >= previous.layoutGeneration &&
          checkpoint.selectionGeneration >= previous.selectionGeneration &&
          checkpoint.receivedBytes >= previous.receivedBytes &&
          checkpoint.uploadedBytes >= previous.uploadedBytes) { "Checkpoint counters regressed" }
        require(checkpoint.selected == previous.selected ||
          checkpoint.selectionGeneration > previous.selectionGeneration) {
          "Selection changed without a new generation"
        }
      }
      val temp = checkNotNull(target.parent) /
        (".checkpoint-" + InfoHash.fromBytes(torrentRandomBytes(20)).hex + ".tmp")
      var identity: String? = null
      try {
        fileSystem.openReadWrite(temp, mustCreate = true).use { handle ->
          identity = requireNotNull(torrentFileIdentity(temp))
          handle.write(0, bytes, 0, bytes.size)
          handle.flush()
        }
        context.ensureActive()
        require(target() == target)
        fileSystem.atomicMove(temp, target)
      } finally {
        if (identity != null && torrentFileIdentity(temp) == identity) {
          fileSystem.delete(temp, mustExist = false)
        }
      }
    }
  }

  private suspend fun <T> operation(block: () -> T): T =
    slots.withPermit { withContext(Dispatchers.IO) { block() } }

  private fun target(): Path {
    val parent = checkNotNull(path.parent)
    val metadata = fileSystem.metadata(parent)
    require(metadata.isDirectory && metadata.symlinkTarget == null) { "Unsafe state directory" }
    val identity = requireNotNull(torrentFileIdentity(parent))
    require(parentIdentity == null || parentIdentity == identity) { "State directory changed" }
    parentIdentity = identity
    return fileSystem.canonicalize(parent) / path.name
  }

  private fun read(target: Path): TorrentV2Checkpoint? {
    val metadata = fileSystem.metadataOrNull(target) ?: return null
    require(metadata.isRegularFile && metadata.symlinkTarget == null) { "Unsafe checkpoint file" }
    val size = requireNotNull(metadata.size)
    require(size in 1..TorrentV2Checkpoint.MAX_BYTES.toLong()) { "Checkpoint exceeds byte limit" }
    val bytes = fileSystem.read(target) {
      val result = readByteArray(size)
      require(exhausted()) { "Checkpoint grew during read" }
      result
    }
    val checkpoint = requireNotNull(TorrentV2Checkpoint.decode(bytes)) { "Not a v2 checkpoint" }
    require(checkpoint.taskId == taskId) { "Checkpoint task mismatch" }
    return checkpoint
  }
}
