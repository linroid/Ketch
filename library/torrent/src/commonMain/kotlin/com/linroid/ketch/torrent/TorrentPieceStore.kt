package com.linroid.ketch.torrent

import com.linroid.ketch.api.log.KetchLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import okio.EOFException
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.concurrent.Volatile
import okio.FileHandle
import okio.FileSystem
import okio.use
import okio.Path
import okio.Path.Companion.toPath

/** How [TorrentPieceStore.commit] ended. */
internal enum class CommitOutcome {
  /** The piece matched its hash and is stored, or was already. */
  VERIFIED,

  /** The piece failed its hash check; nothing was stored. */
  CORRUPT,

  /** The selection no longer wants the piece; nothing was stored and the sender did no wrong. */
  NOT_WANTED,
}

/**
 * A verified piece that is no longer wanted, or that spans a file outside the selection, could
 * not be read back: the store dropped it, and peers asking for it are refused.
 */
internal class PieceRevokedException(val index: Int) :
  IllegalStateException("Torrent piece is no longer stored")

/** The selection as a swarm applies it; [recheck] lists pieces whose stored bytes may verify. */
internal class SelectionView(
  val version: Long,
  val wanted: BooleanArray,
  val verified: BooleanArray,
  val recheck: IntArray,
)

/**
 * Verified piece storage; files retain their original torrent offsets. A file is stored in place
 * once it is *materialized*: selected, or owned in place from an earlier selection. Spans of other
 * files live in owned `<piece>.piece` sidecars. Shrinking the selection keeps what is verified;
 * expanding it copies verified sidecar spans into the new files and rehashes those pieces.
 */
internal class TorrentPieceStore(
  val metadata: TorrentMetadata,
  output: Path,
  selected: Set<Int>,
  val taskId: String,
  private val fileSystem: FileSystem = torrentFileSystem,
  private val storageSlots: Semaphore = Semaphore(32),
) {
  private val log = KetchLogger("TorrentSession")
  private val mutex = Mutex()
  private val output = absolute(output)
  // Replaced whole, never mutated, so readers outside the mutex see a consistent selection.
  @Volatile private var selected: Set<Int> = selected.ifEmpty { metadata.files.indices }.toSet()
  private val offsets = LongArray(metadata.files.size)
  private val verified = BooleanArray(metadata.pieceHashes.size / 20)
  @Volatile private var wanted = BooleanArray(verified.size)
  private val fileProgress = LongArray(metadata.files.size)
  private var remaining = 0
  // Under the mutex: files stored in place. It only grows, except for a deselected file that
  // disappeared from disk.
  private val materialized = mutableSetOf<Int>()
  private var selectionVersion = 0L
  // Under the mutex: newly wanted pieces whose stored bytes may already verify.
  private val pendingRecheck = linkedSetOf<Int>()
  private val ownedFiles = linkedMapOf<Path, String>()
  private val ownedDirectories = linkedMapOf<Path, String>()
  private val sidecar: Path
  // Every file of the torrent: ownership never depends on the selection.
  private val allowedOutputFiles by lazy { metadata.files.indices.map(::filePath).toSet() }
  private val allowedDirectories by lazy {
    buildSet {
      for (file in allowedOutputFiles + journalPath) {
        var parent = file.parent
        while (parent != null && parent != parent.root && add(parent)) parent = parent.parent
      }
    }
  }
  private var initialized = false
  private var journalReady = false
  private var ownershipLoaded = false
  private val pendingOwnership = mutableListOf<Pair<Boolean, TorrentOwnedPath>>()
  private val journalPath: Path get() = sidecar / "ownership"
  private val journal by lazy {
    TorrentOwnershipJournal(journalPath, Bencode.encode(mapOf(
      "hash" to metadata.infoHash.toBytes(), "task" to taskId, "output" to output.toString()
    )), fileSystem)
  }
  val outputPath: String get() = output.toString()
  val selectedIndices: Set<Int> get() = selected

  val pieceCount: Int get() = verified.size
  val totalSelectedBytes: Long get() = selected.sumOf { metadata.files[it].size }

  /** Whether every file is selected; trackers only hear `completed` for a whole torrent. */
  fun selectsAllFiles(): Boolean = selected.size == metadata.files.size

  init {
    require(this.selected.all { it in metadata.files.indices }) { "Invalid selected file index" }
    require(taskId.isNotEmpty() && taskId.length <= 128 && taskId.all {
      it.isLetterOrDigit() || it == '-'
    }) { "Invalid torrent task identity" }
    var offset = 0L
    metadata.files.forEachIndexed { index, file ->
      require(file.size >= 0 && file.size <= Long.MAX_VALUE - offset)
      offsets[index] = offset
      offset += file.size
    }
    require(offset == metadata.totalBytes && metadata.pieceLength > 0)
    val expectedPieces = offset / metadata.pieceLength +
      (if (offset % metadata.pieceLength == 0L) 0 else 1)
    require(expectedPieces == pieceCount.toLong())
    wanted = wantedFor(this.selected)
    remaining = wanted.count { it }
    sidecar = checkNotNull(this.output.parent) / ".ketch-${metadata.infoHash.hex}-$taskId"
  }

  private fun wantedFor(files: Set<Int>): BooleanArray {
    val result = BooleanArray(verified.size)
    for (file in files) for (index in piecesOf(file)) result[index] = true
    return result
  }

  /** The pieces holding bytes of [file]; empty for an empty file. */
  private fun piecesOf(file: Int): IntRange {
    if (metadata.files[file].size == 0L) return IntRange.EMPTY
    val first = offsets[file] / metadata.pieceLength
    val last = (offsets[file] + metadata.files[file].size - 1) / metadata.pieceLength
    return first.toInt()..last.toInt()
  }

  fun pieceSize(index: Int): Int {
    require(index in verified.indices)
    return minOf(metadata.pieceLength, metadata.totalBytes - index * metadata.pieceLength).toInt()
  }

  fun needed(index: Int): Boolean = wanted[index]

  suspend fun initialize() = mutex.withLock {
    storageOperation {
      if (initialized) return@storageOperation
      ensureDirectory(sidecar)
      loadOwnership()
      journal.initialize()
      journalReady = true
      for (path in ownedFiles.keys.toList()) {
        if (path.parent == sidecar && Regex("checkpoint-[0-9a-f]{40}\\.tmp").matches(path.name) &&
          torrentFileIdentity(path) == ownedFiles[path]) {
          fileSystem.delete(path, mustExist = false)
          ownedFiles.remove(path)
        }
      }
      for ((directory, owned) in pendingOwnership) journal.append(directory, owned)
      pendingOwnership.clear()
      recordOwned(journalPath, directory = false)
      if (metadata.isMultiFile) ensureDirectory(output)
      for (index in selected) {
        val path = filePath(index)
        ensureDirectory(checkNotNull(path.parent))
        validateRegularPath(path)
        if (!fileSystem.exists(path)) {
          fileSystem.openReadWrite(path, mustCreate = true).use {
            recordOwned(path, directory = false)
            it.flush()
          }
        }
      }
      // Files of an earlier selection stay in place while this task still owns them.
      materialized += selected
      for (file in metadata.files.indices) {
        if (ownedFiles.containsKey(filePath(file))) materialized += file
      }
      initialized = true
    }
  }

  /** Stores [bytes] as piece [index] when it is wanted and matches its hash. */
  suspend fun commit(index: Int, bytes: ByteArray): CommitOutcome {
    require(bytes.size == pieceSize(index))
    if (!needed(index)) return CommitOutcome.NOT_WANTED
    // Hash against immutable metainfo outside the lock so other peers' commits proceed.
    if (!matches(index, bytes)) return CommitOutcome.CORRUPT
    return commitVerified(index, bytes)
  }

  private suspend fun commitVerified(index: Int, bytes: ByteArray): CommitOutcome =
    mutex.withLock {
      check(initialized)
      if (verified[index]) return@withLock CommitOutcome.VERIFIED
      // Checked again under the lock: a selection change may have dropped the piece meanwhile.
      if (!wanted[index]) return@withLock CommitOutcome.NOT_WANTED
      val start = index * metadata.pieceLength
      val end = start + bytes.size
      lateinit var spans: List<Int>
      storageOperation {
        // A deselected file the user deleted is never created again for a neighbor's piece.
        overlappingFiles(index).forEach(::dropIfVanished)
        spans = overlappingFiles(index).filter { it in materialized }
        val inPlace = spans.sumOf { overlap(start, end, it) }
        if (inPlace < bytes.size) {
          ensureDirectory(sidecar)
          val path = sidecar / "$index.piece"
          write(path, 0, bytes)
        }
        for (fileIndex in spans) {
          val count = overlap(start, end, fileIndex)
          if (count == 0L) continue
          val overlapStart = maxOf(start, offsets[fileIndex])
          val sourceOffset = (overlapStart - start).toInt()
          write(filePath(fileIndex), overlapStart - offsets[fileIndex], bytes, sourceOffset,
            count.toInt())
        }
      }
      // Return through withContext's cancellation boundary before publishing availability. A
      // canceled provider may still write bytes; a later operation must reverify before adoption.
      currentCoroutineContext().ensureActive()
      verified[index] = true
      remaining--
      for (file in spans) fileProgress[file] += overlap(start, end, file)
      CommitOutcome.VERIFIED
    }

  /**
   * Reads a piece from the files it is stored in place and from its owned sidecar. A failed read
   * of a piece that is no longer wanted, or that spans a deselected file, revokes it:
   * [PieceRevokedException]. Other failures are storage errors.
   */
  suspend fun read(index: Int): ByteArray = mutex.withLock {
    try {
      storageOperation { readPiece(index) }
    } catch (failure: okio.IOException) {
      if (!revocable(index)) throw failure
      storageOperation { revokeLocked(index) }
      throw PieceRevokedException(index)
    }
  }

  /**
   * Drops piece [index] when it is no longer wanted or spans a deselected file, after its stored
   * bytes stopped matching; false leaves a piece of the selection alone.
   */
  suspend fun revoke(index: Int): Boolean = mutex.withLock {
    if (!revocable(index)) return@withLock false
    storageOperation { revokeLocked(index) }
    true
  }

  private fun revocable(index: Int): Boolean {
    val selection = selected
    return !wanted[index] || overlappingFiles(index).any { it !in selection }
  }

  private fun revokeLocked(index: Int) {
    if (verified[index]) unverify(index)
    overlappingFiles(index).forEach(::dropIfVanished)
  }

  /** Clears a verified piece and the progress its in-place spans counted. */
  private fun unverify(index: Int) {
    verified[index] = false
    if (wanted[index]) remaining++
    val start = index * metadata.pieceLength
    for (file in overlappingFiles(index)) {
      if (file in materialized) fileProgress[file] -= overlap(start, start + pieceSize(index), file)
    }
  }

  /** A deselected in-place file that no longer exists is stored in sidecars again. */
  private fun dropIfVanished(file: Int) {
    if (file in selected || file !in materialized || fileSystem.exists(filePath(file))) return
    materialized -= file
    fileProgress[file] = 0
  }

  suspend fun recheck(): BooleanArray = mutex.withLock {
    check(initialized)
    storageOperation {
      verified.fill(false)
      fileProgress.fill(0)
      pendingRecheck.clear()
      remaining = wanted.count { it }
      healedPieces = 0
      val chunk = ByteArray(CHECK_CHUNK_BYTES)
      for (index in verified.indices) {
        currentCoroutineContext().ensureActive()
        if (!needed(index)) continue
        if (storedOrHealed(index, chunk)) markVerified(index)
      }
      if (healedPieces > 0) {
        log.d { "Healed $healedPieces piece(s) of taskId=$taskId from their sidecars" }
      }
      verified.copyOf()
    }
  }

  /**
   * Hashes newly wanted pieces whose bytes are already stored, such as a reselected file this
   * task still owns. Returns how many verified; the rest download as usual.
   */
  suspend fun verifyExisting(indices: IntArray): Int {
    val chunk = ByteArray(CHECK_CHUNK_BYTES)
    var count = 0
    for (index in indices) {
      currentCoroutineContext().ensureActive()
      mutex.withLock {
        if (!initialized || verified[index] || !wanted[index]) return@withLock
        val matched = storageOperation { storedOrHealed(index, chunk) }
        currentCoroutineContext().ensureActive()
        if (matched) {
          markVerified(index)
          count++
        }
      }
    }
    return count
  }

  private fun markVerified(index: Int) {
    verified[index] = true
    if (wanted[index]) remaining--
    val start = index * metadata.pieceLength
    for (file in overlappingFiles(index)) {
      if (file in materialized) fileProgress[file] += overlap(start, start + pieceSize(index), file)
    }
  }

  /**
   * Whether piece [index] verifies as stored. When it does not and its owned sidecar does, the
   * sidecar's spans are copied into the in-place files first, healing an interrupted selection
   * copy or a change made while no swarm ran.
   */
  private suspend fun storedOrHealed(index: Int, chunk: ByteArray): Boolean {
    if (matchesStored(index, chunk)) return true
    val path = sidecar / "$index.piece"
    if (!ownedSidecar(path) || !sidecarMatches(index, path, chunk)) return false
    overlappingFiles(index).forEach(::dropIfVanished)
    return try {
      copyFromSidecar(index, overlappingFiles(index).filter { it in materialized }, chunk)
      matchesStored(index, chunk).also { if (it) healedPieces++ }
    } catch (_: okio.IOException) {
      false
    }
  }

  // Under the mutex: pieces the current recheck healed from sidecars, for its log line.
  private var healedPieces = 0

  private fun matchesStored(
    index: Int,
    chunk: ByteArray,
    inPlace: (Int) -> Boolean = materialized::contains,
  ): Boolean = try {
    storedPieceMatches(index, chunk, inPlace)
  } catch (_: okio.IOException) {
    false
  }

  private fun ownedSidecar(path: Path): Boolean {
    val identity = ownedFiles[path] ?: return false
    validateRegularPath(path)
    return torrentFileIdentity(path) == identity
  }

  private fun sidecarMatches(index: Int, path: Path, chunk: ByteArray): Boolean = try {
    val size = pieceSize(index)
    val digest = Sha1()
    fileSystem.openReadOnly(path).use { handle ->
      var done = 0
      while (done < size) {
        val count = minOf(chunk.size, size - done)
        readFully(handle, done.toLong(), chunk, 0, count)
        digest.update(chunk, 0, count)
        done += count
      }
    }
    digest.digest().contentEquals(metadata.pieceHashes.copyOfRange(index * 20, index * 20 + 20))
  } catch (_: okio.IOException) {
    false
  }

  /** Copies piece [index]'s spans of [files] from its sidecar, one chunk at a time. */
  private fun copyFromSidecar(index: Int, files: List<Int>, chunk: ByteArray) {
    val path = sidecar / "$index.piece"
    validateRegularPath(path)
    val start = index * metadata.pieceLength
    val end = start + pieceSize(index)
    fileSystem.openReadOnly(path).use { source ->
      for (file in files) {
        val count = overlap(start, end, file)
        if (count == 0L) continue
        val overlapStart = maxOf(start, offsets[file])
        val from = overlapStart - start
        val to = overlapStart - offsets[file]
        val target = filePath(file)
        validateRegularPath(target)
        val existed = fileSystem.exists(target)
        fileSystem.openReadWrite(target, mustCreate = !existed).use { handle ->
          if (!existed) recordOwned(target, directory = false)
          var done = 0L
          while (done < count) {
            val size = minOf(chunk.size.toLong(), count - done).toInt()
            readFully(source, from + done, chunk, 0, size)
            handle.write(to + done, chunk, 0, size)
            done += size
          }
        }
      }
    }
  }

  /**
   * Replaces the selection with [newSelected], a non-empty set of file indices, without
   * downloading anything. Verified pieces stay verified. Each added file is created unless it
   * exists; verified pieces it overlaps are copied from their sidecars and rehashed, and lose
   * their verification when that fails. Newly wanted pieces whose bytes are already stored are
   * listed for [verifyExisting]. Before [initialize] it only replaces the selection.
   */
  suspend fun changeSelection(newSelected: Set<Int>): SelectionView = mutex.withLock {
    require(newSelected.isNotEmpty() && newSelected.all { it in metadata.files.indices }) {
      "Invalid selected file index"
    }
    val next = newSelected.toSet()
    if (next == selected) return@withLock viewLocked(IntArray(0))
    val nextWanted = wantedFor(next)
    if (!initialized) {
      publishSelection(next, nextWanted)
      return@withLock viewLocked(IntArray(0))
    }
    val previousWanted = wanted
    val added = next.filter { it !in selected }.sorted()
    val recheck = storageOperation {
      val moved = mutableSetOf<Int>()
      for (file in added) {
        val path = filePath(file)
        ensureDirectory(checkNotNull(path.parent))
        validateRegularPath(path)
        if (!fileSystem.exists(path)) {
          fileSystem.openReadWrite(path, mustCreate = true).use {
            recordOwned(path, directory = false)
            it.flush()
          }
          moved += file
        } else if (file !in materialized) {
          // A file already in place, owned or not, is adopted only where its bytes verify.
          moved += file
        }
      }
      val layout = materialized + moved
      val chunk = ByteArray(CHECK_CHUNK_BYTES)
      val pieces = moved.flatMap { piecesOf(it) }.distinct().sorted().filter { verified[it] }
      var copied = 0
      for (piece in pieces) {
        currentCoroutineContext().ensureActive()
        val kept = matchesStored(piece, chunk) { it in layout } ||
          ownedSidecar(sidecar / "$piece.piece") && try {
            copyFromSidecar(piece, overlappingFiles(piece).filter { it in moved }, chunk)
            copied++
            matchesStored(piece, chunk) { it in layout }
          } catch (_: okio.IOException) {
            false
          }
        if (!kept) unverify(piece)
      }
      materialized += moved
      if (pieces.isNotEmpty()) {
        log.d { "Selection of taskId=$taskId copied $copied of ${pieces.size} verified piece(s)" }
      }
      nextWanted.indices.filter { index ->
        nextWanted[index] && !previousWanted[index] && !verified[index] && storedSpansExist(index)
      }
    }
    currentCoroutineContext().ensureActive()
    for (file in added) {
      fileProgress[file] = piecesOf(file).sumOf { index ->
        val start = index * metadata.pieceLength
        if (verified[index]) overlap(start, start + pieceSize(index), file) else 0L
      }
    }
    pendingRecheck += recheck
    publishSelection(next, nextWanted)
    viewLocked(recheck.toIntArray())
  }

  private fun publishSelection(next: Set<Int>, nextWanted: BooleanArray) {
    selected = next
    wanted = nextWanted
    remaining = nextWanted.indices.count { nextWanted[it] && !verified[it] }
    selectionVersion++
  }

  /** Whether every span of piece [index] has somewhere it may already be stored. */
  private fun storedSpansExist(index: Int): Boolean {
    val start = index * metadata.pieceLength
    val end = start + pieceSize(index)
    return overlappingFiles(index).all { file ->
      overlap(start, end, file) == 0L || if (file in materialized) {
        fileSystem.exists(filePath(file))
      } else ownedSidecar(sidecar / "$index.piece")
    }
  }

  private fun viewLocked(recheck: IntArray) =
    SelectionView(selectionVersion, wanted.copyOf(), verified.copyOf(), recheck)

  /** The current selection for a swarm; hands over the pieces waiting for [verifyExisting]. */
  suspend fun selectionView(): SelectionView = mutex.withLock {
    val recheck = pendingRecheck.filter { wanted[it] && !verified[it] }.toIntArray()
    pendingRecheck.clear()
    viewLocked(recheck)
  }

  /** Verified bytes per file; files outside the selection count nothing. */
  suspend fun progress(): LongArray = mutex.withLock {
    val selection = selected
    LongArray(fileProgress.size) { if (it in selection) fileProgress[it] else 0 }
  }

  suspend fun isInitialized(): Boolean = mutex.withLock { initialized }

  suspend fun completed(): Boolean = mutex.withLock { initialized && remaining == 0 }

  suspend fun finish() {
    check(finishIfComplete()) { "Selected torrent files are incomplete" }
  }

  /**
   * Truncates and flushes the selected files when the selection is complete; false when it is
   * not, as after a selection change that raced the completion.
   */
  suspend fun finishIfComplete(): Boolean = mutex.withLock {
    if (!initialized || remaining != 0) return@withLock false
    storageOperation {
      for (file in selected) {
        val path = filePath(file)
        validateRegularPath(path)
        fileSystem.openReadWrite(path, mustExist = true).use { handle ->
          val expected = metadata.files[file].size
          check(handle.size() >= expected) { "Completed torrent file was truncated" }
          if (handle.size() > expected) handle.resize(expected)
          handle.flush()
        }
      }
      deleteObsoleteSidecars()
    }
    true
  }

  /** Deletes owned sidecars of verified pieces stored wholly in place, which nothing reads. */
  private fun deleteObsoleteSidecars() {
    var deleted = 0
    for ((path, identity) in ownedFiles.toList()) {
      if (path.parent != sidecar) continue
      val index = path.name.removeSuffix(".piece").toIntOrNull() ?: continue
      if (path.name != "$index.piece" || index !in 0 until pieceCount || !verified[index]) continue
      val start = index * metadata.pieceLength
      val end = start + pieceSize(index)
      val inPlace = overlappingFiles(index).all { file ->
        overlap(start, end, file) == 0L ||
          file in materialized && fileSystem.exists(filePath(file))
      }
      if (!inPlace) continue
      validateRegularPath(path)
      if (torrentFileIdentity(path) == identity) {
        fileSystem.delete(path, mustExist = false)
        deleted++
      }
      ownedFiles.remove(path)
    }
    if (deleted > 0) log.d { "Deleted $deleted obsolete sidecar piece(s) of taskId=$taskId" }
  }

  suspend fun verifiedPieces(): BooleanArray = mutex.withLock { verified.copyOf() }

  /** Deletes only recorded paths whose OS identity still matches the task-owned file. */
  suspend fun cleanup() = mutex.withLock {
    storageOperation {
      val files = ownedFiles.toList().asReversed().sortedBy { it.first == journalPath }
      for ((path, identity) in files) {
        validateRegularPath(path)
        if (torrentFileIdentity(path) == identity) fileSystem.delete(path, mustExist = false)
      }
      for ((path, identity) in ownedDirectories.toList().sortedByDescending { it.first.segments.size }) {
        validateParents(path)
        val directory = fileSystem.metadataOrNull(path)?.isDirectory == true
        if (directory && torrentFileIdentity(path) == identity && fileSystem.list(path).isEmpty()) {
          fileSystem.delete(path)
        }
      }
      ownedFiles.clear()
      ownedDirectories.clear()
    }
  }

  suspend fun ownedPaths(): Pair<List<String>, List<String>> = mutex.withLock {
    ownedFiles.keys.map { it.toString() } to ownedDirectories.keys.map { it.toString() }
  }

  suspend fun recoverOwnership() = mutex.withLock {
    storageOperation { loadOwnership() }
  }

  private fun loadOwnership() {
    if (ownershipLoaded || !fileSystem.exists(sidecar)) return
    validateParents(journalPath)
    for ((directory, owned) in journal.load()) restoreOwned(directory, owned)
    ownershipLoaded = true
  }

  private var trackerConfiguration: TrackerConfiguration? = null
  private var trackerRevision = 0L

  suspend fun checkpoint(): TorrentCheckpoint = mutex.withLock { snapshot() }

  suspend fun restore(checkpoint: TorrentCheckpoint) = mutex.withLock {
    validateCheckpoint(checkpoint)
    for (owned in checkpoint.files) restoreOwned(false, owned)
    for (owned in checkpoint.directories) restoreOwned(true, owned)
    trackerConfiguration = checkpoint.trackerConfiguration
    trackerRevision = checkpoint.trackerRevision
    // Do not trust checkpoint.verified. initialize()/recheck() prove the files before progress.
  }

  /** Decode only a journal-owned checkpoint; the callback borrows its admitted state. */
  suspend fun withPersistedCheckpoint(
    budget: TorrentBufferBudget,
    block: suspend (TorrentCheckpoint) -> Unit,
  ) {
    var lease: TorrentBufferBudget.Lease? = null
    var decoded: TorrentCheckpoint? = null
    try {
      mutex.withLock {
        check(initialized)
        storageOperation {
          val path = sidecar / "checkpoint"
          validateRegularPath(path)
          if (!fileSystem.exists(path)) return@storageOperation
          val identity = requireNotNull(ownedFiles[path]) { "Checkpoint is not owned by this task" }
          require(torrentFileIdentity(path) == identity) { "Checkpoint identity changed" }
          fileSystem.openReadOnly(path).use { handle ->
            val size = handle.size()
            require(size in 1..TorrentCheckpoint.MAX_BYTES.toLong())
            // Retain raw bytes, the parsed tree, and the decoded metainfo copies through the
            // callback. The metainfo dominates, so this stays linear in the file size.
            val weight = size * 4 + 64 * 1024
            check(weight <= budget.capacity) { "Checkpoint recovery exceeds admission capacity" }
            lease = checkNotNull(budget.reserve(weight.toInt())) {
              "Checkpoint recovery budget exhausted"
            }
            val bytes = ByteArray(size.toInt())
            var offset = 0
            while (offset < bytes.size) {
              val count = handle.read(offset.toLong(), bytes, offset, bytes.size - offset)
              if (count <= 0) throw EOFException("Truncated torrent checkpoint")
              offset += count
            }
            require(handle.size() == size && torrentFileIdentity(path) == identity) {
              "Checkpoint changed during recovery"
            }
            val checkpoint = requireNotNull(TorrentCheckpoint.decode(bytes))
            validateCheckpoint(checkpoint)
            decoded = checkpoint
          }
        }
      }
      decoded?.let { block(it) }
    } finally {
      decoded = null
      lease?.close()
    }
  }

  /** Installs admitted tracker state; progress and ownership never come from this hint. */
  suspend fun adoptTrackerCheckpoint(
    checkpoint: TorrentCheckpoint,
    configuration: TrackerConfiguration,
  ) = mutex.withLock {
    validateCheckpoint(checkpoint)
    require(checkpoint.trackerRevision > trackerRevision)
    require(configuration.tiers == checkpoint.trackerConfiguration?.tiers)
    currentCoroutineContext().ensureActive()
    trackerConfiguration = configuration
    trackerRevision = checkpoint.trackerRevision
  }

  private fun validateCheckpoint(checkpoint: TorrentCheckpoint) {
    // Any selection: the record, not the checkpoint, decides what this run downloads.
    require(checkpoint.taskId == taskId && checkpoint.metadata.infoHash == metadata.infoHash)
    require(checkpoint.output == output.toString())
  }

  /** Owner shutdown only, after joining all operations; the persisted checkpoint is untouched. */
  fun discardTrackerConfiguration() {
    trackerConfiguration = null
    trackerRevision = 0
  }

  /** Borrowed committed configuration; a canceled caller can inspect the commit outcome. */
  suspend fun currentTrackerConfiguration(): TrackerConfiguration? = mutex.withLock {
    trackerConfiguration
  }

  suspend fun trackerConfigurationSnapshot(): TrackerConfigurationSnapshot = mutex.withLock {
    TrackerConfigurationSnapshot(trackerConfiguration?.tiers ?: metadata.trackerTiers,
      trackerRevision)
  }

  /** Flushes a snapshot file before returning bytes for publication through TaskStore. */
  suspend fun persistCheckpoint(receivedBytes: Long = 0, uploadedBytes: Long = 0): ByteArray =
    mutex.withLock {
      persistSnapshot(receivedBytes, uploadedBytes, trackerConfiguration, trackerRevision)
    }

  /** The caller retains admission for the configuration while the store uses it. */
  suspend fun replaceTrackerConfiguration(
    configuration: TrackerConfiguration,
    receivedBytes: Long = 0,
    uploadedBytes: Long = 0,
    expectedRevision: Long? = null,
  ): ByteArray = mutex.withLock {
    require(expectedRevision == null || expectedRevision >= 0)
    if (expectedRevision != null && expectedRevision != trackerRevision) {
      throw TrackerRevisionConflict(expectedRevision, trackerRevision)
    }
    check(trackerRevision < Long.MAX_VALUE) { "Tracker configuration revision exhausted" }
    persistSnapshot(receivedBytes, uploadedBytes, configuration, trackerRevision + 1)
  }

  private suspend fun persistSnapshot(
    receivedBytes: Long,
    uploadedBytes: Long,
    configuration: TrackerConfiguration?,
    revision: Long,
  ): ByteArray {
    require(receivedBytes >= 0 && uploadedBytes >= 0)
    check(initialized)
    return storageOperation {
      if (journal.needsCompaction) {
        require(torrentFileIdentity(journalPath) == ownedFiles[journalPath])
        val records = ownedDirectories.map { true to TorrentOwnedPath(it.key.toString(), it.value) } +
          ownedFiles.map { false to TorrentOwnedPath(it.key.toString(), it.value) }
        ownedFiles[journalPath] = journal.compact(records)
      }
      val checkpointPath = sidecar / "checkpoint"
      validateRegularPath(checkpointPath)
      if (fileSystem.exists(checkpointPath)) {
        require(ownedFiles[checkpointPath] == torrentFileIdentity(checkpointPath)) {
          "Checkpoint path is not owned by this task"
        }
      }
      val temp = sidecar / ("checkpoint-" + InfoHash.fromBytes(torrentRandomBytes(20)).hex + ".tmp")
      val data = snapshot().copy(receivedBytes = receivedBytes, uploadedBytes = uploadedBytes,
        trackerConfiguration = configuration, trackerRevision = revision).encode()
      try {
        fileSystem.openReadWrite(temp, mustCreate = true).use { handle ->
          recordOwned(temp, directory = false)
          handle.write(0, data, 0, data.size)
          handle.flush()
        }
        val identity = checkNotNull(ownedFiles[temp])
        journal.append(false, TorrentOwnedPath(checkpointPath.toString(), identity))
        currentCoroutineContext().ensureActive()
        fileSystem.atomicMove(temp, checkpointPath)
        ownedFiles.remove(temp)
        ownedFiles[checkpointPath] = identity
        trackerConfiguration = configuration
        trackerRevision = revision
        snapshot().copy(receivedBytes = receivedBytes, uploadedBytes = uploadedBytes).encode()
      } catch (failure: Throwable) {
        // Blocking cleanup stays inside the admitted I/O operation, even on cancellation.
        // After a successful rename the temporary path is no longer in ownedFiles.
        try {
          val identity = ownedFiles[temp]
          if (identity != null) {
            validateRegularPath(temp)
            if (torrentFileIdentity(temp) == identity) {
              fileSystem.delete(temp, mustExist = false)
            }
            ownedFiles.remove(temp)
          }
        } catch (cleanupFailure: Throwable) {
          failure.addSuppressed(cleanupFailure)
        }
        throw failure
      }
    }
  }

  // A store opens at most one payload handle at a time. Acquire before dispatching blocking I/O;
  // retain the slot until that I/O actually returns, including cancellation and provider failures.
  // Journals/checkpoints may open extra non-payload handles and are outside this payload ceiling.
  private suspend fun <T> storageOperation(block: suspend CoroutineScope.() -> T): T =
    storageSlots.withPermit { withContext(Dispatchers.IO, block) }

  private fun snapshot(): TorrentCheckpoint = TorrentCheckpoint(taskId, metadata,
    output.toString(), selected, verified.copyOf(),
    ownedFiles.map { TorrentOwnedPath(it.key.toString(), it.value) },
    ownedDirectories.map { TorrentOwnedPath(it.key.toString(), it.value) },
    trackerConfiguration = trackerConfiguration, trackerRevision = trackerRevision)

  private fun recordOwned(path: Path, directory: Boolean) {
    val identity = requireNotNull(torrentFileIdentity(path)) { "Filesystem has no safe file identity" }
    val paths = if (directory) ownedDirectories else ownedFiles
    if (paths[path] == identity) return
    paths[path] = identity
    val owned = TorrentOwnedPath(path.toString(), identity)
    if (journalReady) journal.append(directory, owned) else pendingOwnership += directory to owned
  }

  private fun restoreOwned(directory: Boolean, owned: TorrentOwnedPath) {
    val path = owned.path.toPath()
    require(path.isAbsolute && path == path.toString().toPath(normalize = true))
    val allowed = if (directory) {
      path in allowedDirectories
    } else {
      path in allowedOutputFiles || path.parent == sidecar && (
        path.name == "ownership" || path.name == "checkpoint" ||
          Regex("checkpoint-[0-9a-f]{40}\\.tmp").matches(path.name) ||
          path.name.removeSuffix(".piece").toIntOrNull()?.let {
            path.name == "$it.piece" && it in 0 until pieceCount
          } == true)
    }
    require(allowed) { "Ownership path escapes torrent output" }
    validateParents(path)
    if (torrentFileIdentity(path) == owned.identity) {
      (if (directory) ownedDirectories else ownedFiles)[path] = owned.identity
    }
  }

  private fun readPiece(index: Int): ByteArray {
    val bytes = ByteArray(pieceSize(index))
    forEachStoredSpan(index) { handle, offset, targetOffset, count ->
      readFully(handle, offset, bytes, targetOffset, count)
    }
    return bytes
  }

  /**
   * Hashes stored spans through a fixed chunk, so checking never holds a whole piece. [inPlace]
   * names the files stored in place; other spans come from the piece's sidecar.
   */
  private fun storedPieceMatches(
    index: Int,
    chunk: ByteArray,
    inPlace: (Int) -> Boolean = materialized::contains,
  ): Boolean {
    val digest = Sha1()
    var hashed = 0
    forEachStoredSpan(index, inPlace) { handle, offset, targetOffset, count ->
      check(targetOffset == hashed) { "Piece spans are not contiguous" }
      var done = 0
      while (done < count) {
        val size = minOf(chunk.size, count - done)
        readFully(handle, offset + done, chunk, 0, size)
        digest.update(chunk, 0, size)
        done += size
      }
      hashed += count
    }
    return hashed == pieceSize(index) &&
      digest.digest().contentEquals(metadata.pieceHashes.copyOfRange(index * 20, index * 20 + 20))
  }

  private inline fun forEachStoredSpan(
    index: Int,
    inPlace: (Int) -> Boolean = materialized::contains,
    visit: (handle: FileHandle, offset: Long, targetOffset: Int, count: Int) -> Unit,
  ) {
    val start = index * metadata.pieceLength
    val end = start + pieceSize(index)
    for (file in overlappingFiles(index)) {
      val count = overlap(start, end, file).toInt()
      if (count == 0) continue
      val overlapStart = maxOf(start, offsets[file])
      val targetOffset = (overlapStart - start).toInt()
      val stored = inPlace(file)
      val path = if (stored) filePath(file) else sidecar / "$index.piece"
      val offset = if (stored) overlapStart - offsets[file] else targetOffset.toLong()
      validateRegularPath(path)
      fileSystem.openReadOnly(path).use { handle -> visit(handle, offset, targetOffset, count) }
    }
  }

  private fun readFully(handle: FileHandle, offset: Long, bytes: ByteArray, at: Int, count: Int) {
    var read = 0
    while (read < count) {
      val size = handle.read(offset + read, bytes, at + read, count - read)
      if (size <= 0) throw EOFException("Truncated piece storage")
      read += size
    }
  }

  private fun write(
    path: Path,
    offset: Long,
    bytes: ByteArray,
    from: Int = 0,
    count: Int = bytes.size - from,
  ) {
    validateRegularPath(path)
    val existed = fileSystem.exists(path)
    fileSystem.openReadWrite(path, mustCreate = !existed).use { handle ->
      if (!existed) recordOwned(path, directory = false)
      // No fsync per piece: resume always rehashes, and finish() syncs the completed files.
      handle.write(offset, bytes, from, count)
    }
  }

  private fun matches(index: Int, bytes: ByteArray): Boolean =
    sha1Digest(bytes).contentEquals(metadata.pieceHashes.copyOfRange(index * 20, index * 20 + 20))

  private fun filePath(index: Int): Path = if (metadata.isMultiFile) {
    val relative = metadata.files[index].path.substringAfter('/', "")
    require(relative.isNotEmpty())
    relative.split('/').forEach(TorrentMetadata::validatePathComponent)
    output / relative
  } else output

  private fun overlap(start: Long, end: Long, file: Int): Long = maxOf(
    0, minOf(end, offsets[file] + metadata.files[file].size) - maxOf(start, offsets[file])
  )

  private fun ensureDirectory(path: Path) {
    var current = checkNotNull(path.root)
    for (part in path.segments) {
      current /= part
      val info = fileSystem.metadataOrNull(current)
      require(info?.symlinkTarget == null) { "Symlink destination is not supported" }
      if (info == null) {
        val created = try {
          fileSystem.createDirectory(current, mustCreate = true)
          true
        } catch (e: okio.IOException) {
          val concurrent = fileSystem.metadataOrNull(current)
          if (concurrent?.isDirectory != true || concurrent.symlinkTarget != null) throw e
          false
        }
        if (created) recordOwned(current, directory = true)
      } else require(info.isDirectory) { "Destination parent is not a directory" }
    }
  }

  private fun validateParents(path: Path) {
    var current = checkNotNull(path.root)
    for (part in path.segments.dropLast(1)) {
      current /= part
      val info = fileSystem.metadataOrNull(current)
      require(info?.symlinkTarget == null && (info == null || info.isDirectory)) {
        "Unsafe destination parent"
      }
    }
  }

  private fun validateRegularPath(path: Path) {
    validateParents(path)
    val info = fileSystem.metadataOrNull(path)
    require(info?.symlinkTarget == null && (info == null || info.isRegularFile)) {
      "Destination is not a regular file"
    }
  }

  private fun overlappingFiles(index: Int): IntRange {
    val start = index * metadata.pieceLength
    val end = start + pieceSize(index)
    var low = 0
    var high = offsets.size
    while (low < high) {
      val middle = low + (high - low) / 2
      if (offsets[middle] + metadata.files[middle].size <= start) low = middle + 1
      else high = middle
    }
    var last = low
    while (last < offsets.size && offsets[last] < end) last++
    return low until last
  }

  private fun absolute(path: Path): Path {
    val normalized = if (path.isAbsolute) path.toString().toPath(normalize = true)
    else (fileSystem.canonicalize(".".toPath()) / path).toString().toPath(normalize = true)
    // Resolve the caller's trusted parent (including OS aliases such as /tmp on macOS).
    // Torrent-supplied child components are validated separately without following symlinks.
    var parent = checkNotNull(normalized.parent) { "A filesystem root cannot be a torrent output" }
    val suffix = mutableListOf(normalized.name)
    while (!fileSystem.exists(parent)) {
      suffix.add(parent.name)
      parent = checkNotNull(parent.parent)
    }
    return suffix.asReversed().fold(fileSystem.canonicalize(parent)) { result, name ->
      result / name
    }
  }

  internal companion object {
    /** Recheck hashes stored pieces through one buffer of this size, whatever the piece length. */
    const val CHECK_CHUNK_BYTES: Int = 64 * 1024
  }
}
