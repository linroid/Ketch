package com.linroid.ketch.torrent

import com.linroid.ketch.api.log.KetchLogger
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
import kotlin.concurrent.Volatile
import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.coroutines.CoroutineContext

/**
 * Owned v2/hybrid storage; existing roots require a bound checkpoint and OS identity checks. The
 * selection can change while the store is open ([changeSelection]): deselected files keep their
 * verified pieces and stay readable, and commits for them are discarded.
 */
@OptIn(ExperimentalAtomicApi::class)
internal class TorrentV2PieceStore(
  private val document: TorrentV2Document,
  private val output: Path,
  selectedIds: Set<String>,
  val taskId: String,
  private val buffers: TorrentBufferBudget,
  private val storageSlots: Semaphore,
  private val fileSystem: FileSystem = torrentFileSystem,
  private val creationLogPath: Path? = null,
  /**
   * Selections an older build may have bound the creation log to, besides this store's own and
   * its checkpoint's, such as the one a task's resume state mirrors.
   */
  private val legacySelections: List<Set<String>> = emptyList(),
) {
  private val log = KetchLogger("TorrentSession")
  private val verifier = TorrentPayloadVerifier(document)
  private val mapping = TorrentOutputMapping.from(document)
  private val mutex = Mutex()
  private val allIds: Set<String> = mapping.files.mapTo(LinkedHashSet()) { it.id }
  private val initialSelection: Set<String> = selectedIds.ifEmpty { allIds }.toSet()
  // Replaced whole by changeSelection under the mutex; readers take one snapshot.
  @Volatile private var selected: Set<String> = initialSelection
  private val filesById = mapping.files.associateBy { it.id }
  private val layoutFiles = verifier.layout.files.associateBy { it.id }
  private val progress = LongArray(mapping.files.size)
  private val verified: BooleanArray
  // Files whose pieces were checked or committed since this store opened, by v2 index.
  private val checkedFiles = BooleanArray(mapping.files.size)
  private data class Ownership(val identity: String, val directory: Boolean)
  private val owned = linkedMapOf<Path, Ownership>()
  private var layoutGeneration = 0L
  private var selectionGeneration = 0L
  // Counted per block without the store lock; checkpoints and restore read and write them.
  private val receivedCounter = AtomicLong(0)
  private val uploadedCounter = AtomicLong(0)
  private var root: Path? = null
  private var creationLog: TorrentV2CreationLog? = null
  // The selection a legacy creation log is bound to; null once it is bound without one.
  private var logSelection: Set<String>? = null
  private var restoredSelection: Set<String>? = null
  private var initialized = false
  private var closed = false
  @Volatile private var totalSelected = totalOf(initialSelection)

  init {
    require(taskId.length in 1..128 && taskId.all { it.isLetterOrDigit() || it == '-' })
    require(output.isAbsolute && output.parent != null)
    TorrentMetadata.validatePathComponent(output.name)
    require(selected.all { it in filesById }) { "Unknown selected file" }
    // The runtime session must admit metadata/layout state before constructing this adapter.
    require(verifier.layout.pieceCount <= 1_000_000) { "Piece index exceeds desktop profile" }
    verified = BooleanArray(verifier.layout.pieceCount.toInt())
  }

  /** IDs of the selected files; changes only through [changeSelection]. */
  fun selectedIds(): Set<String> = selected

  /** Whether every file is selected; trackers hear `completed` only for a whole torrent. */
  fun selectsAll(): Boolean = selected.size == mapping.files.size

  /** Total bytes of the selected files. */
  val totalSelectedBytes: Long get() = totalSelected

  private fun totalOf(ids: Set<String>): Long = mapping.files.sumOf { file ->
    if (file.id in ids) document.info.files[file.v2Index].length else 0L
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
        if (ensureFile(destination, file, replaceMissing = false)) {
          checkedFiles[file.v2Index] = true
        }
      }
    }
    currentCoroutineContext().ensureActive()
    initialized = true
  }

  /**
   * Under the mutex, on the storage dispatcher: creates [file] and its missing parents, or checks
   * the owned ones. True when it created the file, which is then empty. A path in the way that
   * this store does not own fails with [IOException]. With [replaceMissing], an owned path that
   * no longer exists is created again rather than failing.
   */
  private fun ensureFile(
    destination: Path,
    file: TorrentOutputMapping.File,
    replaceMissing: Boolean,
  ): Boolean {
    var parent = destination
    for (component in file.components.dropLast(1)) {
      parent /= component
      if (parent !in owned || replaceMissing && forgetMissing(parent)) {
        if (fileSystem.metadataOrNull(parent) != null) throw IOException(IN_THE_WAY)
        fileSystem.createDirectory(parent, mustCreate = true)
        record(parent, directory = true)
      }
      validateOwned(parent, directory = true)
    }
    val path = parent / file.components.last()
    val created = path !in owned || replaceMissing && forgetMissing(path)
    if (created) {
      if (fileSystem.metadataOrNull(path) != null) throw IOException(IN_THE_WAY)
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
    return created
  }

  /** An owned [path] that no longer exists, with every claim below it, stops being owned. */
  private fun forgetMissing(path: Path): Boolean {
    if (fileSystem.metadataOrNull(path) != null) return false
    val prefix = path.segments
    owned.keys.removeAll { it.segments.size >= prefix.size &&
      it.segments.subList(0, prefix.size) == prefix }
    return true
  }

  /**
   * Selects [ids] instead, without stopping transfers. Newly selected files are created, or,
   * when this store owns them from before and has not checked them since it opened, rechecked;
   * deselected files keep their verified pieces and stay readable. A path in the way that Ketch
   * does not own fails with [IOException] before the selection changes. A legacy creation log is
   * bound again without a selection, so later changes need no rewrite.
   */
  suspend fun changeSelection(ids: Set<String>) = mutex.withLock {
    check(!closed)
    require(ids.isNotEmpty() && ids.all { it in filesById }) { "Unknown selected file" }
    val current = selected
    if (ids == current) return@withLock
    if (initialized) {
      val context = currentCoroutineContext()
      val destination = checkNotNull(root)
      for (file in mapping.files) {
        if (file.id !in ids || file.id in current) continue
        context.ensureActive()
        val created = storageOperation { ensureFile(destination, file, replaceMissing = true) }
        val layoutFile = layoutFiles.getValue(file.id)
        if (created) {
          // A file made again starts empty, whatever its pieces were before.
          pieceRange(layoutFile)?.let { verified.fill(false, it.first, it.last + 1) }
          progress[file.v2Index] = 0
          checkedFiles[file.v2Index] = true
        } else if (!checkedFiles[file.v2Index]) {
          checkFile(layoutFile, context)
        }
      }
      storageOperation { rebindCreationLog(ids) }
      context.ensureActive()
    }
    selected = ids.toSet()
    totalSelected = totalOf(ids)
    selectionGeneration++
  }

  /** The v2 pieces of [file], or null for an empty file, which has none. */
  private fun pieceRange(file: TorrentContentLayout.File): IntRange? {
    if (file.length == 0L) return null
    val first = (file.offset / verifier.layout.pieceLength).toInt()
    val count = ((file.length - 1) / verifier.layout.pieceLength + 1).toInt()
    return first until first + count
  }

  /** Hash mismatch writes nothing. Verified progress is published only after the write returns. */
  suspend fun commit(index: Int, payload: ByteArray): CommitOutcome = mutex.withLock {
    if (!validateCommit(index, payload.size)) return@withLock CommitOutcome.NOT_WANTED
    val lease = checkNotNull(buffers.reserve(payload.size)) { "Payload buffer budget exhausted" }
    try {
      // Ordinary caller buffers remain mutable, so hash and write a privately admitted copy.
      commitBuffer(index, payload.copyOf())
    } finally {
      lease.close()
    }
  }

  /** Only for sealed assemblies: caller retains admission and immutability until this returns. */
  suspend fun commitOwned(index: Int, payload: ByteArray): CommitOutcome = mutex.withLock {
    if (!validateCommit(index, payload.size)) return@withLock CommitOutcome.NOT_WANTED
    commitBuffer(index, payload)
  }

  /** False when the piece's file is not selected, so nothing is written for it. */
  private fun validateCommit(index: Int, size: Int): Boolean {
    check(initialized && !closed)
    require(index in verified.indices)
    val extent = verifier.layout.v2Piece(index.toLong())
    require(size.toLong() == extent.length)
    return extent.fileId in selected
  }

  /** Both ownership paths share integrity, file ownership and the flush/publication barrier. */
  private suspend fun commitBuffer(index: Int, bytes: ByteArray): CommitOutcome {
    val extent = verifier.layout.v2Piece(index.toLong())
    val file = filesById.getValue(checkNotNull(extent.fileId))
    val verification = verifier.piece(index.toLong())
    verification.update(bytes)
    if (!verification.verify()) return CommitOutcome.CORRUPT
    if (verified[index]) return CommitOutcome.VERIFIED
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
    checkedFiles[file.v2Index] = true
    return CommitOutcome.VERIFIED
  }

  /** The consumer owns this reservation until it finishes using [bytes]. */
  class ReadBuffer internal constructor(
    val bytes: ByteArray,
    private val lease: TorrentBufferBudget.Lease,
  ) {
    fun close() = lease.close()
  }

  /** What [tryRead] found: the piece's verified bytes, or why there are none. */
  sealed interface ReadOutcome {
    /** The caller closes [buffer]. */
    class Read(val buffer: ReadBuffer) : ReadOutcome

    /** The piece is not committed, so there is nothing to read. */
    data object NotCommitted : ReadOutcome

    /** No transfer budget for the piece now; the piece stays committed. */
    data object NoBudget : ReadOutcome

    /** The committed piece no longer reads back or verifies, and is no longer committed. */
    class Revoked(val cause: Exception) : ReadOutcome
  }

  /** Only committed pieces are readable; returned bytes are authenticated again from disk. */
  suspend fun read(index: Int): ReadBuffer = when (val outcome = tryRead(index)) {
    is ReadOutcome.Read -> outcome.buffer
    ReadOutcome.NotCommitted -> throw IllegalStateException("Piece is not committed")
    ReadOutcome.NoBudget -> throw IllegalStateException("Read budget exhausted")
    is ReadOutcome.Revoked -> throw outcome.cause
  }

  /**
   * Reads a committed piece and authenticates it again from disk, holding its bytes against
   * [budget] (the store's transfer budget unless a caller has a partition of it). A piece that
   * changed or can no longer be read is revoked: it stops counting as verified progress and must
   * be fetched again.
   */
  suspend fun tryRead(
    index: Int,
    budget: TorrentBufferBudget = buffers,
  ): ReadOutcome = mutex.withLock {
    check(initialized && !closed)
    require(index in verified.indices)
    if (!verified[index]) return@withLock ReadOutcome.NotCommitted
    val extent = verifier.layout.v2Piece(index.toLong())
    val file = filesById.getValue(checkNotNull(extent.fileId))
    val lease = budget.reserve(extent.length.toInt()) ?: return@withLock ReadOutcome.NoBudget
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
        if (error !is IOException && error !is IllegalArgumentException) throw error
        verified[index] = false
        progress[file.v2Index] -= extent.length
        lease.close()
        return@withLock ReadOutcome.Revoked(error)
      }
      ReadOutcome.Read(ReadBuffer(bytes, lease))
    } catch (error: Throwable) {
      lease.close()
      throw error
    }
  }

  /** Rebuilds committed availability from owned files with at most 64 KiB of payload scratch. */
  suspend fun recheck(): BooleanArray = mutex.withLock {
    check(initialized && !closed)
    val current = selected
    verified.fill(false)
    progress.fill(0)
    // Deselected files lost their bits too: selecting one again checks it first.
    checkedFiles.fill(false)
    val context = currentCoroutineContext()
    for (layoutFile in verifier.layout.files) {
      context.ensureActive()
      if (layoutFile.id in current) checkFile(layoutFile, context)
    }
    verified.copyOf()
  }

  /**
   * Under the mutex: hashes [layoutFile]'s pieces as stored, through at most 64 KiB of scratch,
   * and publishes the ones that match. A file that cannot be read keeps none.
   */
  private suspend fun checkFile(layoutFile: TorrentContentLayout.File, context: CoroutineContext) {
    val file = filesById.getValue(layoutFile.id)
    val range = pieceRange(layoutFile)
    progress[file.v2Index] = 0
    if (range == null) {
      checkedFiles[file.v2Index] = true
      return
    }
    verified.fill(false, range.first, range.last + 1)
    val scratchSize = minOf(layoutFile.length, verifier.layout.pieceLength, 65_536L).toInt()
    val lease = checkNotNull(buffers.reserve(scratchSize)) { "Recheck budget exhausted" }
    var published = false
    try {
      val scratch = ByteArray(scratchSize)
      val matchedBytes = storageOperation {
        val path = destination(file)
        validateOwned(path, directory = false)
        fileSystem.openReadWrite(path, mustExist = true).use { handle ->
          var matched = 0L
          for (index in range) {
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
      checkedFiles[file.v2Index] = true
      published = true
    } catch (_: IOException) {
      // A failed file flush cannot authorize any of this file's tentative pieces.
      checkedFiles[file.v2Index] = true
    } finally {
      if (!published) verified.fill(false, range.first, range.last + 1)
      lease.close()
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
    // Explicit totals may only raise the counters; blocks keep counting while this runs.
    require(receivedBytes == null || receivedBytes >= receivedCounter.load())
    require(uploadedBytes == null || uploadedBytes >= uploadedCounter.load())
    val received = receivedBytes ?: receivedCounter.load()
    val uploaded = uploadedBytes ?: uploadedCounter.load()
    val destination = checkNotNull(root)
    val records = owned.map { (path, claim) ->
      val components = if (path == destination) emptyList() else
        path.relativeTo(destination).segments
      TorrentV2Checkpoint.Owned(components, claim.identity, claim.directory)
    }
    val checkpoint = TorrentV2Checkpoint.create(document, taskId, destination.toString(), selected,
      records, pieceBitfield(verified).toByteString(), layoutGeneration, selectionGeneration,
      received, uploaded)
    raise(receivedCounter, received)
    raise(uploadedCounter, uploaded)
    return checkpoint
  }

  /**
   * Validates all claims before adoption; initialize/recheck follow successful restore. The
   * checkpoint may hold another selection: claims of files that are no longer needed and changed
   * or disappeared are dropped, and the selection generation moves past the checkpoint's.
   */
  suspend fun restore(checkpoint: TorrentV2Checkpoint) = mutex.withLock {
    check(!closed && !initialized && root == null && owned.isEmpty())
    require(checkpoint.taskId == taskId) { "Checkpoint task mismatch" }
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
    selectionGeneration = checkpoint.selectionGeneration +
      if (checkpoint.selected == selected) 0 else 1
    restoredSelection = checkpoint.selected
    receivedCounter.store(checkpoint.receivedBytes)
    uploadedCounter.store(checkpoint.uploadedBytes)
    // Never adopt verifiedHint: current payloads must pass commit or recheck before publication.
    verified.fill(false)
    progress.fill(0)
  }

  suspend fun progress(): Map<String, Long> = mutex.withLock {
    val current = selected
    mapping.files.filter { it.id in current }.associate { it.id to progress[it.v2Index] }
  }

  /** Every selected piece is verified; deselected files count for nothing. */
  suspend fun completed(): Boolean = mutex.withLock {
    val current = selected
    initialized && !closed && mapping.files.sumOf { file ->
      if (file.id in current) progress[file.v2Index] else 0L
    } == totalOf(current)
  }

  /** The selection's generation, raised by every change; checkpoints carry it. */
  suspend fun selectionGeneration(): Long = mutex.withLock { selectionGeneration }

  /** Counts payload received from peers, verified or not; never takes the store lock. */
  fun recordReceived(bytes: Int) = add(receivedCounter, bytes)

  /** Counts payload written to peers; never takes the store lock. */
  fun recordUploaded(bytes: Int) = add(uploadedCounter, bytes)

  private fun add(counter: AtomicLong, bytes: Int) {
    require(bytes >= 0)
    while (true) {
      val current = counter.load()
      require(current <= Long.MAX_VALUE - bytes)
      if (counter.compareAndSet(current, current + bytes)) return
    }
  }

  fun receivedBytes(): Long = receivedCounter.load()

  fun uploadedBytes(): Long = uploadedCounter.load()

  /** Raises [counter] to at least [value]; a concurrent increment is never lost. */
  private fun raise(counter: AtomicLong, value: Long) {
    while (true) {
      val current = counter.load()
      if (current >= value || counter.compareAndSet(current, value)) return
    }
  }

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

  /**
   * The claims in [entries] that still hold. A claim that no longer holds fails, unless it is
   * neither the root nor a selected file or one of its parents: such a claim, a file deselected
   * and then deleted or replaced, is dropped with the claims below it.
   */
  private fun validateRecords(
    destination: Path,
    entries: List<TorrentV2Checkpoint.Owned>,
  ): LinkedHashMap<Path, Ownership> {
    val current = selected
    val needed = hashSetOf(emptyList<String>())
    for (file in mapping.files) {
      if (file.id !in current) continue
      for (size in 1..file.components.size) needed += file.components.subList(0, size)
    }
    val records = linkedMapOf<Path, Ownership>()
    val dropped = hashSetOf<List<String>>()
    for (entry in entries.sortedBy { it.components.size }) {
      val path = entry.components.fold(destination) { parent, component -> parent / component }
      val orphan = entry.components.isNotEmpty() && entry.components.dropLast(1) in dropped
      val metadata = if (orphan) null else fileSystem.metadataOrNull(path)
      val holds = metadata != null && metadata.symlinkTarget == null &&
        (if (entry.directory) metadata.isDirectory else metadata.isRegularFile) &&
        torrentFileIdentity(path) == entry.identity
      if (holds) {
        records[path] = Ownership(entry.identity, entry.directory)
      } else {
        require(entry.components !in needed) { "Checkpoint ownership changed" }
        dropped += entry.components
      }
    }
    if (dropped.isNotEmpty()) {
      log.d { "Dropped ${dropped.size} stale ownership claim(s) of taskId=$taskId" }
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
      // The selection-free binding first, then those older builds bound logs to.
      val candidates = (listOf(null, selected, initialSelection, restoredSelection) +
        legacySelections.map { it.ifEmpty { allIds } } + listOf(allIds)).distinct()
      val bindings = candidates.map { creationBinding(destination, it) }
      val candidate = TorrentV2CreationLog(path, fileSystem)
      // A new log is bound to the selection, so older builds can read it until it changes.
      val opened = candidate.open(bindings, initial = 1)
      val entries = linkedMapOf<List<String>, TorrentV2Checkpoint.Owned>()
      for (entry in opened.records + owned.map { (path, claim) ->
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
      logSelection = candidates[opened.matched]
    }
    rebindCreationLog(selected, destination)
    // Retry any failed append from this live instance before creating additional paths.
    for ((path, claim) in owned) {
      checkNotNull(creationLog).append(ownershipRecord(destination, path, claim))
    }
  }

  /**
   * Binds a creation log that is still bound to another selection than [ids] without one, so
   * selection changes never touch it again. A log bound to [ids] is left as it is: older builds
   * read it as long as the selection does not change.
   */
  private fun rebindCreationLog(ids: Set<String>, destination: Path = checkNotNull(root)) {
    val current = creationLog ?: return
    val bound = logSelection ?: return
    if (bound == ids) return
    current.rewrite(creationBinding(destination, null), current.records())
    logSelection = null
    log.d { "Bound the creation log of taskId=$taskId without a selection" }
  }

  /**
   * What a creation log is bound to: this task's content and output, and with [selection] the
   * selection older builds bound it to as well.
   */
  private fun creationBinding(destination: Path, selection: Set<String>?): ByteArray {
    val values = mutableMapOf<String, Any>(
      "kind" to "ketch-v2-creation", "mapping" to 1L, "task" to taskId,
      "v2" to document.info.hash.toBytes(),
      "v1" to (document.identity.v1?.toBytes() ?: ByteArray(0)),
      "output" to destination.toString(),
    )
    if (selection == null) values["binding"] = 2L else values["selected"] = selection.sorted()
    return sha256Digest(Bencode.encode(values))
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

  private companion object {
    const val IN_THE_WAY = "A file Ketch does not own is in the way"
  }
}
