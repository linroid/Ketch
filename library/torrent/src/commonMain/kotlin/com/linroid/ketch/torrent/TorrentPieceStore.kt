package com.linroid.ketch.torrent

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import okio.EOFException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okio.FileSystem
import okio.use
import okio.Path
import okio.Path.Companion.toPath

/** Verified piece storage; selected files retain their original torrent offsets. */
internal class TorrentPieceStore(
  val metadata: TorrentMetadata,
  output: Path,
  selected: Set<Int>,
  taskId: String,
  private val fileSystem: FileSystem = torrentFileSystem,
) {
  private val mutex = Mutex()
  private val output = absolute(output)
  private val selected = selected.ifEmpty { metadata.files.indices.toSet() }
  private val offsets = LongArray(metadata.files.size)
  private val verified = BooleanArray(metadata.pieceHashes.size / 20)
  private val wanted = BooleanArray(verified.size)
  private val fileProgress = LongArray(metadata.files.size)
  private var remaining = 0
  private val ownedFiles = linkedSetOf<Path>()
  private val ownedDirectories = linkedSetOf<Path>()
  private val sidecar: Path
  private var initialized = false

  val pieceCount: Int get() = verified.size
  val totalSelectedBytes: Long get() = selected.sumOf { metadata.files[it].size }

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
    for (file in this.selected) {
      if (metadata.files[file].size == 0L) continue
      val first = offsets[file] / metadata.pieceLength
      val last = (offsets[file] + metadata.files[file].size - 1) / metadata.pieceLength
      for (index in first.toInt()..last.toInt()) wanted[index] = true
    }
    remaining = wanted.count { it }
    sidecar = checkNotNull(this.output.parent) / ".ketch-${metadata.infoHash.hex}-$taskId"
  }

  fun pieceSize(index: Int): Int {
    require(index in verified.indices)
    return minOf(metadata.pieceLength, metadata.totalBytes - index * metadata.pieceLength).toInt()
  }

  fun needed(index: Int): Boolean = wanted[index]

  suspend fun initialize() = mutex.withLock {
    withContext(Dispatchers.IO) {
      if (initialized) return@withContext
      if (metadata.isMultiFile) ensureDirectory(output)
      for (index in selected) {
        val path = filePath(index)
        ensureDirectory(checkNotNull(path.parent))
        validateRegularPath(path)
        if (!fileSystem.exists(path)) {
          fileSystem.openReadWrite(path, mustCreate = true).use {
            ownedFiles.add(path)
            it.flush()
          }
        }
      }
      initialized = true
    }
  }

  /** False means a hash mismatch; no bytes or progress are committed in that case. */
  suspend fun commit(index: Int, bytes: ByteArray): Boolean = mutex.withLock {
    check(initialized)
    require(bytes.size == pieceSize(index) && needed(index))
    if (!matches(index, bytes)) return@withLock false
    if (verified[index]) return@withLock true
    withContext(Dispatchers.IO) {
      val start = index * metadata.pieceLength
      val end = start + bytes.size
      val spans = overlappingFiles(index).filter { it in selected }
      val selectedBytes = spans.sumOf { overlap(start, end, it) }
      if (selectedBytes < bytes.size) {
        ensureDirectory(sidecar)
        val path = sidecar / "$index.piece"
        write(path, 0, bytes)
      }
      for (fileIndex in spans) {
        val count = overlap(start, end, fileIndex)
        if (count == 0L) continue
        val overlapStart = maxOf(start, offsets[fileIndex])
        val sourceOffset = (overlapStart - start).toInt()
        write(filePath(fileIndex), overlapStart - offsets[fileIndex],
          bytes.copyOfRange(sourceOffset, sourceOffset + count.toInt()))
      }
      verified[index] = true
      remaining--
      for (file in spans) fileProgress[file] += overlap(start, end, file)
    }
    true
  }

  /** Reads selected spans from the output and skipped boundary spans from owned sidecars. */
  suspend fun read(index: Int): ByteArray = mutex.withLock {
    withContext(Dispatchers.IO) { readPiece(index) }
  }

  suspend fun recheck(): BooleanArray = mutex.withLock {
    check(initialized)
    withContext(Dispatchers.IO) {
      verified.fill(false)
      fileProgress.fill(0)
      remaining = wanted.count { it }
      for (index in verified.indices) {
        currentCoroutineContext().ensureActive()
        if (!needed(index)) continue
        verified[index] = try {
          matches(index, readPiece(index))
        } catch (_: okio.IOException) {
          false
        }
        if (verified[index]) {
          remaining--
          val start = index * metadata.pieceLength
          for (file in overlappingFiles(index)) {
            if (file in selected) {
              fileProgress[file] += overlap(start, start + pieceSize(index), file)
            }
          }
        }
      }
      verified.copyOf()
    }
  }

  suspend fun progress(): LongArray = mutex.withLock { fileProgress.copyOf() }

  suspend fun completed(): Boolean = mutex.withLock { initialized && remaining == 0 }

  suspend fun finish() = mutex.withLock {
    check(initialized && remaining == 0) { "Selected torrent files are incomplete" }
    withContext(Dispatchers.IO) {
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
    }
  }

  suspend fun verifiedPieces(): BooleanArray = mutex.withLock { verified.copyOf() }

  /** Cleanup is deliberately limited to files this instance created. */
  suspend fun cleanup() = mutex.withLock {
    withContext(Dispatchers.IO) {
      for (path in ownedFiles.toList().asReversed()) {
        validateRegularPath(path)
        fileSystem.delete(path, mustExist = false)
      }
      for (path in ownedDirectories.toList().asReversed()) {
        validateParents(path)
        val directory = fileSystem.metadataOrNull(path)?.isDirectory == true
        if (directory && fileSystem.list(path).isEmpty()) {
          fileSystem.delete(path)
        }
      }
      ownedFiles.clear()
      ownedDirectories.clear()
    }
  }

  suspend fun ownedPaths(): Pair<List<String>, List<String>> = mutex.withLock {
    ownedFiles.map { it.toString() } to ownedDirectories.map { it.toString() }
  }

  private fun readPiece(index: Int): ByteArray {
    val bytes = ByteArray(pieceSize(index))
    val start = index * metadata.pieceLength
    val end = start + bytes.size
    for (file in overlappingFiles(index)) {
      val count = overlap(start, end, file).toInt()
      if (count == 0) continue
      val overlapStart = maxOf(start, offsets[file])
      val targetOffset = (overlapStart - start).toInt()
      val path = if (file in selected) filePath(file) else sidecar / "$index.piece"
      val offset = if (file in selected) overlapStart - offsets[file] else targetOffset.toLong()
      validateRegularPath(path)
      fileSystem.openReadOnly(path).use { handle ->
        var read = 0
        while (read < count) {
          val size = handle.read(offset + read, bytes, targetOffset + read, count - read)
          if (size <= 0) throw EOFException("Truncated piece storage")
          read += size
        }
      }
    }
    return bytes
  }

  private fun write(path: Path, offset: Long, bytes: ByteArray) {
    validateRegularPath(path)
    val existed = fileSystem.exists(path)
    fileSystem.openReadWrite(path, mustCreate = !existed).use { handle ->
      if (!existed) ownedFiles.add(path)
      handle.write(offset, bytes, 0, bytes.size)
      handle.flush()
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
        fileSystem.createDirectory(current, mustCreate = true)
        ownedDirectories.add(current)
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
}
