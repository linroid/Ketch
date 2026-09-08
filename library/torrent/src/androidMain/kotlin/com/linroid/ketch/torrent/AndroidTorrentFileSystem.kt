package com.linroid.ketch.torrent

import android.os.ParcelFileDescriptor
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import okio.FileHandle
import okio.FileSystem
import okio.ForwardingFileSystem
import okio.IOException
import okio.Path
import okio.Path.Companion.toPath
import okio.Sink
import okio.Source
import java.io.FileDescriptor

/** O_PATH traversal works in Android's sandbox without permission to enumerate ancestor dirs. */
internal object AndroidTorrentFileSystem : ForwardingFileSystem(FileSystem.SYSTEM) {
  private inline fun <T> io(block: () -> T): T = try { block() } catch (e: ErrnoException) {
    throw IOException("Torrent filesystem operation failed (errno=${e.errno})", e)
  }

  private fun <T> parent(path: Path, action: (String) -> T): T = io {
    val absolute = if (path.isAbsolute) path.normalized() else
      (delegate.canonicalize(".".toPath()) / path).normalized()
    val flags = 0x200000 or OsConstants.O_NOFOLLOW // Linux O_PATH, same on Android ARM/x86.
    var descriptor = Os.open("/", flags, 0)
    try {
      for (part in absolute.segments.dropLast(1)) {
        val child = ParcelFileDescriptor.dup(descriptor).use { pinned ->
          Os.open("/proc/self/fd/${pinned.fd}/$part", flags, 0)
        }
        if (!OsConstants.S_ISDIR(Os.fstat(child).st_mode)) {
          Os.close(child)
          throw IOException("Unsafe torrent directory")
        }
        Os.close(descriptor)
        descriptor = child
      }
      ParcelFileDescriptor.dup(descriptor).use { pinned ->
        action("/proc/self/fd/${pinned.fd}/${absolute.name}")
      }
    } finally { Os.close(descriptor) }
  }

  override fun openReadOnly(file: Path): FileHandle = openFile(file, false, false, true)
  override fun openReadWrite(file: Path, mustCreate: Boolean, mustExist: Boolean): FileHandle =
    openFile(file, true, mustCreate, mustExist)
  private fun openFile(file: Path, writable: Boolean, mustCreate: Boolean,
    mustExist: Boolean): FileHandle = parent(file) { path ->
    var flags = (if (writable) OsConstants.O_RDWR else OsConstants.O_RDONLY) or
      OsConstants.O_NOFOLLOW or OsConstants.O_NONBLOCK
    if (writable && !mustExist) flags = flags or OsConstants.O_CREAT
    if (mustCreate) flags = flags or OsConstants.O_CREAT or OsConstants.O_EXCL
    val descriptor = Os.open(path, flags, 438)
    try {
      if (!OsConstants.S_ISREG(Os.fstat(descriptor).st_mode)) {
        throw IOException("Torrent output is not a regular file")
      }
      DescriptorHandle(descriptor, writable)
    } catch (e: Throwable) { Os.close(descriptor); throw e }
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
  override fun createDirectory(dir: Path, mustCreate: Boolean) = parent(dir) { path ->
    try { Os.mkdir(path, 511) } catch (e: ErrnoException) {
      if (mustCreate || e.errno != OsConstants.EEXIST) throw e
      val fd = Os.open(path, 0x200000 or OsConstants.O_NOFOLLOW, 0)
      try {
        if (!OsConstants.S_ISDIR(Os.fstat(fd).st_mode)) {
          throw IOException("Unsafe torrent directory")
        }
      } finally { Os.close(fd) }
    }
  }
  override fun atomicMove(source: Path, target: Path) = parent(source) { from ->
    parent(target) { to -> Os.rename(from, to) }
  }
  override fun delete(path: Path, mustExist: Boolean) {
    try {
      parent(path) { name ->
        try { Os.remove(name) } catch (e: ErrnoException) {
          if (mustExist || e.errno != OsConstants.ENOENT) throw e
        }
      }
    } catch (e: IOException) {
      if (mustExist || (e.cause as? ErrnoException)?.errno != OsConstants.ENOENT) throw e
    }
  }

  private class DescriptorHandle(private val fd: FileDescriptor, writable: Boolean) :
    FileHandle(writable) {
    override fun protectedRead(fileOffset: Long, array: ByteArray, arrayOffset: Int,
      byteCount: Int): Int = io {
      if (byteCount == 0) 0 else Os.pread(fd, array, arrayOffset, byteCount, fileOffset)
        .let { if (it == 0) -1 else it }
    }
    override fun protectedWrite(fileOffset: Long, array: ByteArray, arrayOffset: Int,
      byteCount: Int) = io {
      var written = 0
      while (written < byteCount) {
        val count = Os.pwrite(fd, array, arrayOffset + written, byteCount - written,
          fileOffset + written)
        if (count <= 0) throw IOException("Torrent write made no progress")
        written += count
      }
    }
    override fun protectedSize(): Long = io { Os.fstat(fd).st_size }
    override fun protectedResize(size: Long) = io { Os.ftruncate(fd, size) }
    override fun protectedFlush() = io { Os.fsync(fd) }
    override fun protectedClose() = io { Os.close(fd) }
  }
}
