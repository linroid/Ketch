package com.linroid.ketch.torrent

import com.sun.jna.Function
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.NativeLibrary
import com.sun.jna.Pointer
import com.sun.jna.WString
import okio.FileHandle
import okio.FileSystem
import okio.ForwardingFileSystem
import okio.IOException
import okio.Path
import okio.Sink
import okio.Source
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.NoSuchFileException
import java.nio.file.OpenOption
import java.nio.file.Paths
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.READ
import java.nio.file.StandardOpenOption.WRITE

/** Windows directory handles deny deletion/rename while each child is opened without reparse. */
internal object WindowsTorrentFileSystem : ForwardingFileSystem(FileSystem.SYSTEM) {
  private val kernel by lazy { NativeLibrary.getInstance("kernel32") }
  private fun function(name: String): Function = kernel.getFunction(name, Function.ALT_CONVENTION)

  private fun <T> parent(path: Path, action: (java.nio.file.Path) -> T): T {
    val absolute = Paths.get(path.toString()).toAbsolutePath().normalize()
    val handles = mutableListOf<Pointer>()
    try {
      var directory = absolute.root
      for (part in listOf<java.nio.file.Path?>(null) + absolute.parent.toList()) {
        if (part != null) directory = directory.resolve(part)
        // FILE_READ_ATTRIBUTES, share read/write but never DELETE, OPEN_EXISTING,
        // FILE_FLAG_BACKUP_SEMANTICS | FILE_FLAG_OPEN_REPARSE_POINT.
        val handle = function("CreateFileW").invokePointer(arrayOf(WString(directory.toString()),
          0x80, 3, null, 3, 0x02200000, null))
        if (handle == null || Pointer.nativeValue(handle) == -1L) {
          throw IOException("Cannot protect torrent directory (error=${Native.getLastError()})")
        }
        handles += handle
        Memory(52).use { info ->
          if (function("GetFileInformationByHandle").invokeInt(arrayOf(handle, info)) == 0 ||
            info.getInt(0) and 0x400 != 0 || info.getInt(0) and 0x10 == 0) {
            throw IOException("Torrent directory is a reparse point or is not a directory")
          }
        }
      }
      return action(absolute)
    } finally {
      handles.asReversed().forEach { function("CloseHandle").invokeInt(arrayOf(it)) }
    }
  }

  override fun openReadOnly(file: Path): FileHandle = openFile(file, false, false, true)
  override fun openReadWrite(file: Path, mustCreate: Boolean, mustExist: Boolean): FileHandle =
    openFile(file, true, mustCreate, mustExist)
  private fun openFile(file: Path, writable: Boolean, mustCreate: Boolean,
    mustExist: Boolean): FileHandle = parent(file) { path ->
    val options = mutableSetOf<OpenOption>(READ, NOFOLLOW_LINKS)
    if (writable) options += WRITE
    if (mustCreate) options += CREATE_NEW else if (writable && !mustExist) options += CREATE
    val channel = FileChannel.open(path, options)
    try {
      if (!Files.isRegularFile(path, NOFOLLOW_LINKS)) {
        throw IOException("Not a regular torrent file")
      }
      ChannelHandle(channel, writable)
    } catch (e: Throwable) { channel.close(); throw e }
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
    if (!mustCreate && Files.isDirectory(path, NOFOLLOW_LINKS)) return@parent
    Files.createDirectory(path)
    Unit
  }
  override fun atomicMove(source: Path, target: Path) = parent(source) { from ->
    parent(target) { to -> Files.move(from, to, ATOMIC_MOVE, REPLACE_EXISTING); Unit }
  }
  override fun delete(path: Path, mustExist: Boolean) {
    try {
      parent(path) { Files.delete(it) }
    } catch (e: NoSuchFileException) { if (mustExist) throw e }
  }

  private class ChannelHandle(private val channel: FileChannel, writable: Boolean) :
    FileHandle(writable) {
    override fun protectedRead(fileOffset: Long, array: ByteArray, arrayOffset: Int,
      byteCount: Int): Int =
      channel.read(ByteBuffer.wrap(array, arrayOffset, byteCount), fileOffset)
    override fun protectedWrite(fileOffset: Long, array: ByteArray, arrayOffset: Int,
      byteCount: Int) {
      val buffer = ByteBuffer.wrap(array, arrayOffset, byteCount)
      var offset = fileOffset
      while (buffer.hasRemaining()) offset += channel.write(buffer, offset)
    }
    override fun protectedSize(): Long = channel.size()
    override fun protectedResize(size: Long) {
      if (size <= channel.size()) channel.truncate(size)
      else channel.write(ByteBuffer.wrap(byteArrayOf(0)), size - 1)
    }
    override fun protectedFlush() = channel.force(true)
    override fun protectedClose() = channel.close()
  }
}
