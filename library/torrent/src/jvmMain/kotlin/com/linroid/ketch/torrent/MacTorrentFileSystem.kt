package com.linroid.ketch.torrent

import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.NativeLibrary
import okio.FileHandle
import okio.FileSystem
import okio.ForwardingFileSystem
import okio.IOException
import okio.Path
import okio.Path.Companion.toPath
import okio.Sink
import okio.Source

/** Darwin filesystem calls absent from Java NIO; all torrent/storage coordination stays Kotlin. */
internal object MacTorrentFileSystem : ForwardingFileSystem(FileSystem.SYSTEM) {
  private val libc by lazy { NativeLibrary.getInstance("c") }
  private fun call(name: String, vararg args: Any?): Int =
    libc.getFunction(name, when (name) {
      "open" -> 2 shl 7
      "openat" -> 3 shl 7
      else -> 0
    }).invokeInt(args)
  private fun longCall(name: String, vararg args: Any?): Long =
    libc.getFunction(name).invokeLong(args)
  private fun checked(success: Boolean) {
    if (!success) {
      throw IOException("Torrent filesystem operation failed (errno=${Native.getLastError()})")
    }
  }
  private const val DIRECTORY = 0x100000
  private const val NOFOLLOW = 0x100
  private const val NONBLOCK = 0x4

  private fun <T> parent(path: Path, action: (Int, String) -> T): T {
    var absolute = if (path.isAbsolute) path.normalized() else
      (delegate.canonicalize(".".toPath()) / path).normalized()
    // Trusted Darwin system aliases are not torrent-supplied directory symlinks.
    if (absolute.segments.firstOrNull() in listOf("tmp", "var", "etc")) {
      absolute = "/private".toPath() / absolute.toString().removePrefix("/")
    }
    var descriptor = call("open", "/", DIRECTORY or NOFOLLOW)
    checked(descriptor >= 0)
    try {
      for (part in absolute.segments.dropLast(1)) {
        val next = call("openat", descriptor, part, DIRECTORY or NOFOLLOW)
        checked(next >= 0)
        call("close", descriptor)
        descriptor = next
      }
      return action(descriptor, absolute.name)
    } finally { call("close", descriptor) }
  }

  override fun openReadOnly(file: Path): FileHandle = openFile(file, false, false, true)
  override fun openReadWrite(file: Path, mustCreate: Boolean, mustExist: Boolean): FileHandle =
    openFile(file, true, mustCreate, mustExist)

  private fun openFile(file: Path, writable: Boolean, mustCreate: Boolean,
    mustExist: Boolean): FileHandle = parent(file) { directory, name ->
    var flags = (if (writable) 2 else 0) or NOFOLLOW or NONBLOCK
    if (writable && !mustExist) flags = flags or 0x200
    if (mustCreate) flags = flags or 0x200 or 0x800
    val fd = call("openat", directory, name, flags, 438)
    checked(fd >= 0)
    try {
      Memory(144).use { attributes ->
        checked(call("fstat", fd, attributes) == 0)
        checked(attributes.getShort(4).toInt() and 0xf000 == 0x8000)
      }
      DescriptorHandle(fd, writable)
    } catch (e: Throwable) { call("close", fd); throw e }
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
    if (call("mkdirat", directory, name, 511) != 0) {
      if (mustCreate || Native.getLastError() != 17) checked(false)
      val fd = call("openat", directory, name, DIRECTORY or NOFOLLOW)
      checked(fd >= 0)
      call("close", fd)
    }
    Unit
  }
  override fun atomicMove(source: Path, target: Path) = parent(source) { from, name ->
    parent(target) { to, destination ->
      checked(call("renameat", from, name, to, destination) == 0)
    }
  }
  override fun delete(path: Path, mustExist: Boolean) = parent(path) { directory, name ->
    // unlinkat does not follow the final symlink. Try a file first, then an empty directory.
    if (call("unlinkat", directory, name, 0) != 0) {
      if (!mustExist && Native.getLastError() == 2) return@parent
      checked(call("unlinkat", directory, name, 0x80) == 0)
    }
  }

  private class DescriptorHandle(private val fd: Int, writable: Boolean) : FileHandle(writable) {
    override fun protectedRead(fileOffset: Long, array: ByteArray, arrayOffset: Int,
      byteCount: Int): Int {
      if (byteCount == 0) return 0
      Memory(byteCount.toLong()).use { memory ->
        val count = longCall("pread", fd, memory, byteCount.toLong(), fileOffset)
        checked(count >= 0)
        if (count == 0L) return -1
        memory.read(0, array, arrayOffset, count.toInt())
        return count.toInt()
      }
    }
    override fun protectedWrite(fileOffset: Long, array: ByteArray, arrayOffset: Int,
      byteCount: Int) {
      if (byteCount == 0) return
      Memory(byteCount.toLong()).use { memory ->
        memory.write(0, array, arrayOffset, byteCount)
        var written = 0L
        while (written < byteCount) {
          val count = longCall("pwrite", fd, memory.share(written), byteCount - written,
            fileOffset + written)
          checked(count > 0)
          written += count
        }
      }
    }
    override fun protectedSize(): Long = longCall("lseek", fd, 0L, 2).also { checked(it >= 0) }
    override fun protectedResize(size: Long) = checked(call("ftruncate", fd, size) == 0)
    override fun protectedFlush() = checked(call("fsync", fd) == 0)
    override fun protectedClose() { call("close", fd) }
  }
}
