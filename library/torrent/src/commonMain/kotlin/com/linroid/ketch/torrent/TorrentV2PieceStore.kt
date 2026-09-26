package com.linroid.ketch.torrent

import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okio.ByteString.Companion.toByteString
import okio.EOFException
import okio.FileHandle
import okio.FileSystem
import okio.IOException
import okio.Path
import okio.use

/** Owned v2/hybrid storage; existing roots require a bound checkpoint and OS identity checks. */
internal class TorrentV2PieceStore(
  private val document: TorrentV2Document,
  private val output: Path,
  selectedIds: Set<String>,
  private val taskId: String,
  private val buffers: TorrentBufferBudget,
  private val storageSlots: Semaphore,
  private val fileSystem: FileSystem = torrentFileSystem,
  private val creationLogPath: Path? = null,
) {
  private val verifier = TorrentPayloadVerifier(document)
  private val mapping = TorrentOutputMapping.from(document)
  private val mutex = Mutex()
  private val selected = selectedIds.ifEmpty { mapping.files.map { it.id }.toSet() }.toSet()
  private val filesById = mapping.files.associateBy { it.id }
  private val progress = LongArray(mapping.files.size)
  private val verified: BooleanArray
  private data class Ownership(val identity: String, val directory: Boolean)
  private val owned = linkedMapOf<Path, Ownership>()
  private var layoutGeneration = 0L
  private var selectionGeneration = 0L
  private var receivedCounter = 0L
  private var uploadedCounter = 0L
  private var root: Path? = null
  private var creationLog: TorrentV2CreationLog? = null
  private var initialized = false
  private var closed = false
  private val totalSelected = document.info.files.indices.sumOf { index ->
    if (mapping.files[index].id in selected) document.info.files[index].length else 0
  }

  init {
    require(taskId.length in 1..128 && taskId.all { it.isLetterOrDigit() || it == '-' })
    require(output.isAbsolute && output.parent != null)
    TorrentMetadata.validatePathComponent(output.name)
    require(selected.all { it in filesById }) { "Unknown selected file" }
    // The runtime session must admit metadata/layout state before constructing this adapter.
    require(verifier.layout.pieceCount <= 1_000_000) { "Piece index exceeds desktop profile" }
    verified = BooleanArray(verifier.layout.pieceCount.toInt())
  }

  /** Validate session wiring before starting any filesystem or network operation. */
  fun requireBinding(identity: TorrentIdentity, ids: Set<String>, layout: TorrentContentLayout) {
    require(document.identity == identity) { "Store belongs to another torrent" }
    val canonical = verifier.layout
    require(layout.infoHash == canonical.infoHash && layout.pieceLength == canonical.pieceLength &&
      layout.protocolBytes == canonical.protocolBytes &&
      layout.payloadBytes == canonical.payloadBytes &&
      layout.files == canonical.files) { "Session layout differs from storage layout" }
    require(if (ids.isEmpty()) selected.size == mapping.files.size else ids == selected) {
      "Store selection differs from session selection"
    }
  }

  suspend fun initialize() = mutex.withLock {
    check(!closed)
    if (initialized) return@withLock
    storageOperation {
      val destination = root ?: (fileSystem.canonicalize(checkNotNull(output.parent)) / output.name)
      prepareCreationLog(destination)
      if (root == null) {
        fileSystem.createDirectory(destination, mustCreate = true)
        root = destination
        record(destination, directory = true)
      }
      validateOwned(destination, directory = true)
      for (file in mapping.files.filter { it.id in selected }) {
        var parent = destination
        for (component in file.components.dropLast(1)) {
          parent /= component
          if (parent !in owned) {
            fileSystem.createDirectory(parent, mustCreate = true)
            record(parent, directory = true)
          }
          validateOwned(parent, directory = true)
        }
        val path = parent / file.components.last()
        if (path !in owned) {
          fileSystem.openReadWrite(path, mustCreate = true).use { handle ->
            record(path, directory = false)
            handle.flush()
          }
        } else {
          validateOwned(path, directory = false)
          fileSystem.openReadWrite(path, mustExist = true).use { handle ->
            if (document.info.files[file.v2Index].length == 0L) handle.resize(0)
            handle.flush()
          }
        }
        validateOwned(path, directory = false)
      }
    }
    currentCoroutineContext().ensureActive()
    initialized = true
  }

  /** Hash mismatch writes nothing. Verified progress is published only after the write returns. */
  suspend fun commit(index: Int, payload: ByteArray): Boolean = mutex.withLock {
    validateCommit(index, payload.size)
    val lease = checkNotNull(buffers.reserve(payload.size)) { "Payload buffer budget exhausted" }
    try {
      // Ordinary caller buffers remain mutable, so hash and write a privately admitted copy.
      commitBuffer(index, payload.copyOf())
    } finally {
      lease.close()
    }
  }

  /** Only for sealed assemblies: caller retains admission and immutability until this returns. */
  suspend fun commitOwned(index: Int, payload: ByteArray): Boolean = mutex.withLock {
    validateCommit(index, payload.size)
    commitBuffer(index, payload)
  }

  private fun validateCommit(index: Int, size: Int) {
    check(initialized && !closed)
    require(index in verified.indices)
    val extent = verifier.layout.v2Piece(index.toLong())
    require(extent.fileId in selected && size.toLong() == extent.length)
  }

  /** Both ownership paths share integrity, file ownership and the flush/publication barrier. */
  private suspend fun commitBuffer(index: Int, bytes: ByteArray): Boolean {
    val extent = verifier.layout.v2Piece(index.toLong())
    val file = filesById.getValue(checkNotNull(extent.fileId))
    val verification = verifier.piece(index.toLong())
    verification.update(bytes)
    if (!verification.verify()) return false
    if (verified[index]) return true
    storageOperation {
      val path = destination(file)
      validateOwned(path, directory = false)
      fileSystem.openReadWrite(path, mustExist = true).use { handle ->
        handle.write(extent.fileOffset, bytes, 0, bytes.size)
        val expected = document.info.files[file.v2Index].length
        if (progress[file.v2Index] + extent.length == expected) {
          if (handle.size() > expected) handle.resize(expected)
          // Sync once per completed file. Resume rehashes, so partial files need no fsync.
          handle.flush()
        }
      }
    }
    currentCoroutineContext().ensureActive()
    verified[index] = true
    progress[file.v2Index] += extent.length
    return true
  }

  /** The consumer owns this reservation until it finishes using [bytes]. */
  class ReadBuffer internal constructor(
    val bytes: ByteArray,
    private val lease: TorrentBufferBudget.Lease,
  ) {
    fun close() = lease.close()
  }

  /** Only committed pieces are readable; returned bytes are authenticated again from disk. */
  suspend fun read(index: Int): ReadBuffer = mutex.withLock {
    check(initialized && !closed)
    require(index in verified.indices)
    check(verified[index]) { "Piece is not committed" }
    val extent = verifier.layout.v2Piece(index.toLong())
    val file = filesById.getValue(checkNotNull(extent.fileId))
    val lease = checkNotNull(buffers.reserve(extent.length.toInt())) { "Read budget exhausted" }
    try {
      val bytes = ByteArray(extent.length.toInt())
      try {
        val context = currentCoroutineContext()
        storageOperation {
          val path = destination(file)
          validateOwned(path, directory = false)
          fileSystem.openReadOnly(path).use { handle ->
            readFully(handle, extent.fileOffset, bytes, bytes.size, context)
          }
        }
        currentCoroutineContext().ensureActive()
        val verification = verifier.piece(index.toLong())
        verification.update(bytes)
        if (!verification.verify()) throw IOException("Committed torrent payload changed")
      } catch (error: Exception) {
        if (error is IOException || error is IllegalArgumentException) {
          verified[index] = false
          progress[file.v2Index] -= extent.length
        }
        throw error
      }
      ReadBuffer(bytes, lease)
    } catch (error: Throwable) {
      lease.close()
      throw error
    }
  }

  /** Rebuilds committed availability from owned files with at most 64 KiB of payload scratch. */
  suspend fun recheck(): BooleanArray = mutex.withLock {
    check(initialized && !closed)
    val largest = verifier.layout.files.filter { it.id in selected }.maxOfOrNull { it.length } ?: 0
    val scratchSize = minOf(largest, verifier.layout.pieceLength, 65_536L).toInt()
    val lease = if (scratchSize == 0) null else
      checkNotNull(buffers.reserve(scratchSize)) { "Recheck budget exhausted" }
    try {
      val scratch = ByteArray(scratchSize)
      verified.fill(false)
      progress.fill(0)
      val context = currentCoroutineContext()
      for (layoutFile in verifier.layout.files) {
        context.ensureActive()
        if (layoutFile.id !in selected || layoutFile.length == 0L) continue
        val file = filesById.getValue(layoutFile.id)
        val first = (layoutFile.offset / verifier.layout.pieceLength).toInt()
        val count = ((layoutFile.length - 1) / verifier.layout.pieceLength + 1).toInt()
        var published = false
        try {
          val matchedBytes = storageOperation {
            val path = destination(file)
            validateOwned(path, directory = false)
            fileSystem.openReadWrite(path, mustExist = true).use { handle ->
              var matched = 0L
              for (index in first until first + count) {
                context.ensureActive()
                val extent = verifier.layout.v2Piece(index.toLong())
                val verification = verifier.piece(index.toLong())
                val valid = try {
                  var offset = 0L
                  while (offset < extent.length) {
                    val size = minOf(scratch.size.toLong(), extent.length - offset).toInt()
                    readFully(handle, extent.fileOffset + offset, scratch, size, context)
                    verification.update(scratch, 0, size)
                    offset += size
                  }
                  verification.verify()
                } catch (_: IOException) {
                  false
                }
                // Tentative under the mutex; finally clears these if flush/return is interrupted.
                verified[index] = valid
                if (valid) matched += extent.length
              }
              if (matched == layoutFile.length && handle.size() > layoutFile.length) {
                handle.resize(layoutFile.length)
              }
              if (matched > 0) handle.flush()
              validateOwned(path, directory = false)
              matched
            }
          }
          context.ensureActive()
          progress[file.v2Index] = matchedBytes
          published = true
        } catch (_: IOException) {
          // A failed file flush cannot authorize any of this file's tentative pieces.
        } finally {
          if (!published) verified.fill(false, first, first + count)
        }
      }
      verified.copyOf()
    } finally {
      lease?.close()
    }
  }

  private fun readFully(
    handle: FileHandle,
    offset: Long,
    bytes: ByteArray,
    count: Int,
    context: CoroutineContext,
  ) {
    var position = 0
    while (position < count) {
      context.ensureActive()
      val size = handle.read(offset + position, bytes, position, count - position)
      if (size <= 0) throw EOFException("Truncated torrent payload")
      position += size
    }
  }

  /** Stores authenticated catalog content before returning a consistent task snapshot. */
  suspend fun checkpoint(
    catalog: TorrentContentCatalog? = null,
    receivedBytes: Long? = null,
    uploadedBytes: Long? = null,
  ): TorrentV2Checkpoint = mutex.withLock {
    check(initialized && !closed)
    catalog?.put(document)
    snapshot(receivedBytes, uploadedBytes)
  }

  /** Inline source state includes metainfo, so it needs no separate catalog reference. */
  suspend fun resumeData(): ByteArray? = mutex.withLock {
    if (!initialized || closed) null else snapshot(null, null).encode()
  }

  private fun snapshot(receivedBytes: Long?, uploadedBytes: Long?): TorrentV2Checkpoint {
    val received = receivedBytes ?: receivedCounter
    val uploaded = uploadedBytes ?: uploadedCounter
    require(received >= receivedCounter && uploaded >= uploadedCounter)
    val destination = checkNotNull(root)
    val records = owned.map { (path, claim) ->
      val components = if (path == destination) emptyList() else
        path.relativeTo(destination).segments
      TorrentV2Checkpoint.Owned(components, claim.identity, claim.directory)
    }
    val checkpoint = TorrentV2Checkpoint.create(document, taskId, destination.toString(), selected,
      records, pieceBitfield(verified).toByteString(), layoutGeneration, selectionGeneration,
      received, uploaded)
    receivedCounter = received
    uploadedCounter = uploaded
    return checkpoint
  }

  /** Validates all claims before adoption; initialize/recheck follow successful restore. */
  suspend fun restore(checkpoint: TorrentV2Checkpoint) = mutex.withLock {
    check(!closed && !initialized && root == null && owned.isEmpty())
    require(checkpoint.taskId == taskId && checkpoint.selected == selected) {
      "Checkpoint task or selection mismatch"
    }
    checkpoint.validateContent(document)
    val recovered = storageOperation {
      val destination = fileSystem.canonicalize(checkNotNull(output.parent)) / output.name
      require(checkpoint.output == destination.toString()) { "Checkpoint output mismatch" }
      val records = validateRecords(destination, checkpoint.owned)
      destination to records
    }
    currentCoroutineContext().ensureActive()
    root = recovered.first
    owned.putAll(recovered.second)
    layoutGeneration = checkpoint.layoutGeneration
    selectionGeneration = checkpoint.selectionGeneration
    receivedCounter = checkpoint.receivedBytes
    uploadedCounter = checkpoint.uploadedBytes
    // Never adopt verifiedHint: current payloads must pass commit or recheck before publication.
    verified.fill(false)
    progress.fill(0)
  }

  suspend fun progress(): Map<String, Long> = mutex.withLock {
    mapping.files.filter { it.id in selected }.associate { it.id to progress[it.v2Index] }
  }

  suspend fun completed(): Boolean = mutex.withLock {
    initialized && !closed && progress.sum() == totalSelected
  }

  suspend fun recordReceived(bytes: Int) = mutex.withLock {
    require(bytes >= 0 && receivedCounter <= Long.MAX_VALUE - bytes)
    receivedCounter += bytes
  }

  suspend fun receivedBytes(): Long = mutex.withLock { receivedCounter }

  suspend fun verifiedPieces(): BooleanArray = mutex.withLock { verified.copyOf() }

  /** Joining the mutex waits for outstanding provider I/O; no later commits are accepted. */
  suspend fun close() = mutex.withLock { closed = true }

  /** Deletes only paths created by this live store whose identity is unchanged. */
  suspend fun cleanup() = mutex.withLock {
    closed = true
    storageOperation {
      for ((path, claim) in owned.toList().asReversed()) {
        validateParents(path)
        if (torrentFileIdentity(path) != claim.identity) continue
        val metadata = fileSystem.metadataOrNull(path) ?: continue
        if (metadata.isRegularFile || metadata.isDirectory && fileSystem.list(path).isEmpty()) {
          fileSystem.delete(path, mustExist = true)
        }
      }
      owned.clear()
      creationLog?.delete()
    }
  }

  /** Recovers journal claims for deletion without creating new payload files. */
  suspend fun recoverOwnership() = mutex.withLock {
    check(!closed && !initialized)
    storageOperation {
      if (creationLogPath != null && fileSystem.exists(creationLogPath)) {
        val destination = fileSystem.canonicalize(checkNotNull(output.parent)) / output.name
        prepareCreationLog(destination)
      }
    }
  }

  private suspend fun <T> storageOperation(block: () -> T): T =
    storageSlots.withPermit { withContext(Dispatchers.IO) { block() } }

  private fun destination(file: TorrentOutputMapping.File): Path =
    file.components.fold(checkNotNull(root)) { path, component -> path / component }

  private fun validateRecords(
    destination: Path,
    entries: List<TorrentV2Checkpoint.Owned>,
  ): LinkedHashMap<Path, Ownership> {
    val records = linkedMapOf<Path, Ownership>()
    for (entry in entries.sortedBy { it.components.size }) {
      val path = entry.components.fold(destination) { parent, component -> parent / component }
      val metadata = fileSystem.metadataOrNull(path)
      require(metadata != null && metadata.symlinkTarget == null &&
        (if (entry.directory) metadata.isDirectory else metadata.isRegularFile) &&
        torrentFileIdentity(path) == entry.identity) { "Checkpoint ownership changed" }
      records[path] = Ownership(entry.identity, entry.directory)
    }
    return records
  }

  private fun prepareCreationLog(destination: Path) {
    val requested = creationLogPath ?: return
    if (creationLog == null) {
      require(requested.isAbsolute && requested.parent != null)
      TorrentMetadata.validatePathComponent(requested.name)
      val path = fileSystem.canonicalize(checkNotNull(requested.parent)) / requested.name
      require(path.segments.take(destination.segments.size) != destination.segments) {
        "Creation log must be outside payload storage"
      }
      val binding = sha256Digest(Bencode.encode(mapOf(
        "kind" to "ketch-v2-creation", "mapping" to 1L, "task" to taskId,
        "v2" to document.info.hash.toBytes(),
        "v1" to (document.identity.v1?.toBytes() ?: ByteArray(0)),
        "output" to destination.toString(), "selected" to selected.sorted()
      )))
      val candidate = TorrentV2CreationLog(path, binding, fileSystem)
      val entries = linkedMapOf<List<String>, TorrentV2Checkpoint.Owned>()
      for (entry in candidate.open() + owned.map { (path, claim) ->
        ownershipRecord(destination, path, claim)
      }) {
        val previous = entries.put(entry.components, entry)
        require(previous == null || previous == entry) { "Checkpoint and creation log disagree" }
      }
      if (entries.isNotEmpty()) {
        val records = entries.values.toList()
        TorrentV2Checkpoint.create(document, taskId, destination.toString(), selected, records,
          ByteArray((verified.size + 7) / 8).toByteString())
        val recovered = validateRecords(destination, records)
        owned.putAll(recovered)
        root = destination
      }
      creationLog = candidate
    }
    // Retry any failed append from this live instance before creating additional paths.
    for ((path, claim) in owned) {
      checkNotNull(creationLog).append(ownershipRecord(destination, path, claim))
    }
  }

  private fun ownershipRecord(destination: Path, path: Path, claim: Ownership) =
    TorrentV2Checkpoint.Owned(
      if (path == destination) emptyList() else path.relativeTo(destination).segments,
      claim.identity, claim.directory
    )

  private fun record(path: Path, directory: Boolean) {
    val identity = requireNotNull(torrentFileIdentity(path)) { "Filesystem has no safe identity" }
    val claim = Ownership(identity, directory)
    owned[path] = claim
    creationLog?.append(ownershipRecord(checkNotNull(root), path, claim))
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
      owned[path]?.directory == directory && torrentFileIdentity(path) == owned[path]?.identity) {
      "Storage path changed"
    }
    if (!directory) validateParents(path)
  }
}
