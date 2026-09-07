package com.linroid.ketch.torrent

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.usePinned
import okio.FileHandle
import okio.FileSystem
import okio.ForwardingFileSystem
import okio.IOException
import okio.Path
import okio.Path.Companion.toPath
import okio.Sink
import okio.Source
import platform.posix.AT_REMOVEDIR
import platform.posix.AT_SYMLINK_NOFOLLOW
import platform.posix.EEXIST
import platform.posix.ENOENT
import platform.posix.O_CREAT
import platform.posix.O_DIRECTORY
import platform.posix.O_EXCL
import platform.posix.O_NOFOLLOW
import platform.posix.O_NONBLOCK
import platform.posix.O_RDONLY
import platform.posix.O_RDWR
import platform.posix.S_IFDIR
import platform.posix.S_IFMT
import platform.posix.S_IFREG
import platform.posix.close
import platform.posix.errno
import platform.posix.fstat
import platform.posix.fstatat
import platform.posix.fsync
import platform.posix.ftruncate
import platform.posix.mkdirat
import platform.posix.open
import platform.posix.openat
import platform.posix.pread
import platform.posix.pwrite
import platform.posix.renameat
import platform.posix.stat
import platform.posix.unlinkat

/** All payload and metadata mutations use no-follow traversal relative to directory descriptors. */
@OptIn(ExperimentalForeignApi::class)
internal object SafeTorrentFileSystem : ForwardingFileSystem(FileSystem.SYSTEM) {
  private fun <T> parent(path: Path, action: (Int, String) -> T): T {
    var absolute = if (path.isAbsolute) path.normalized() else
      (delegate.canonicalize(".".toPath()) / path).normalized()
    if (absolute.segments.firstOrNull() in listOf("tmp", "var", "etc")) {
      absolute = "/private".toPath() / absolute.toString().removePrefix("/")
    }
    var descriptor = open("/", O_RDONLY or O_DIRECTORY or O_NOFOLLOW)
    checked(descriptor >= 0)
    try {
      for (part in absolute.segments.dropLast(1)) {
        val child = openat(descriptor, part, O_RDONLY or O_DIRECTORY or O_NOFOLLOW)
        checked(child >= 0)
        close(descriptor)
        descriptor = child
      }
      return action(descriptor, absolute.name)
    } finally { close(descriptor) }
  }

  override fun openReadOnly(file: Path): FileHandle = openFile(file, false, false, true)
  override fun openReadWrite(file: Path, mustCreate: Boolean, mustExist: Boolean): FileHandle =
    openFile(file, true, mustCreate, mustExist)

  private fun openFile(path: Path, writable: Boolean, mustCreate: Boolean,
    mustExist: Boolean): FileHandle = parent(path) { directory, name ->
    var flags = (if (writable) O_RDWR else O_RDONLY) or O_NOFOLLOW or O_NONBLOCK
    if (writable && !mustExist) flags = flags or O_CREAT
    if (mustCreate) flags = flags or O_EXCL or O_CREAT
    val descriptor = openat(directory, name, flags, 438) // 0666, restricted by the process umask.
    checked(descriptor >= 0)
    try {
      memScoped {
        val attributes = alloc<stat>()
        checked(fstat(descriptor, attributes.ptr) == 0)
        if (attributes.st_mode.toInt() and S_IFMT != S_IFREG) {
          throw IOException("Torrent output is not a regular file")
        }
      }
      DescriptorHandle(descriptor, writable)
    } catch (e: Throwable) { close(descriptor); throw e }
  }

  override fun source(file: Path): Source {
    val handle = openReadOnly(file)
    return handle.source().also { handle.close() }
  }
  override fun sink(file: Path, mustCreate: Boolean): Sink {
    val handle = openReadWrite(file, mustCreate, false)
    try {
      handle.resize(0)
      return handle.sink().also { handle.close() }
    } catch (e: Throwable) { handle.close(); throw e }
  }
  override fun appendingSink(file: Path, mustExist: Boolean): Sink {
    val handle = openReadWrite(file, false, mustExist)
    return handle.appendingSink().also { handle.close() }
  }
  override fun createDirectory(dir: Path, mustCreate: Boolean) = parent(dir) { directory, name ->
    if (mkdirat(directory, name, 511u) != 0) {
      if (mustCreate || errno != EEXIST) checked(false)
      val descriptor = openat(directory, name, O_RDONLY or O_DIRECTORY or O_NOFOLLOW)
      checked(descriptor >= 0)
      close(descriptor)
    }
    Unit
  }
  override fun atomicMove(source: Path, target: Path) = parent(source) { from, name ->
    parent(target) { to, destination -> checked(renameat(from, name, to, destination) == 0) }
  }
  override fun delete(path: Path, mustExist: Boolean) {
    parent(path) { directory, name ->
      memScoped {
        val attributes = alloc<stat>()
        if (fstatat(directory, name, attributes.ptr, AT_SYMLINK_NOFOLLOW) != 0) {
          if (!mustExist && errno == ENOENT) return@memScoped
          checked(false)
        }
        val flags = if (attributes.st_mode.toInt() and S_IFMT == S_IFDIR) AT_REMOVEDIR else 0
        if (unlinkat(directory, name, flags) != 0 && (mustExist || errno != ENOENT)) checked(false)
      }
    }
  }

  private fun checked(success: Boolean) {
    if (!success) throw IOException("Torrent filesystem operation failed (errno=$errno)")
  }

  private class DescriptorHandle(private val descriptor: Int, writable: Boolean) :
    FileHandle(writable) {
    override fun protectedRead(fileOffset: Long, array: ByteArray, arrayOffset: Int,
      byteCount: Int): Int {
      if (byteCount == 0) return 0
      val count = array.usePinned {
        pread(descriptor, it.addressOf(arrayOffset), byteCount.toULong(), fileOffset)
      }
      checked(count >= 0)
      return if (count == 0L) -1 else count.toInt()
    }
    override fun protectedWrite(fileOffset: Long, array: ByteArray, arrayOffset: Int,
      byteCount: Int) {
      var written = 0
      while (written < byteCount) {
        val count = array.usePinned { pwrite(descriptor, it.addressOf(arrayOffset + written),
          (byteCount - written).toULong(), fileOffset + written) }
        checked(count > 0)
        written += count.toInt()
      }
    }
    override fun protectedSize(): Long = memScoped {
      val attributes = alloc<stat>()
      checked(fstat(descriptor, attributes.ptr) == 0)
      attributes.st_size
    }
    override fun protectedResize(size: Long) = checked(ftruncate(descriptor, size) == 0)
    override fun protectedFlush() = checked(fsync(descriptor) == 0)
    override fun protectedClose() { close(descriptor) }
  }
}
