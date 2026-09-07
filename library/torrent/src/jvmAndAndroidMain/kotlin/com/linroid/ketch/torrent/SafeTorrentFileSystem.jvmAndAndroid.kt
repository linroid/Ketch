package com.linroid.ketch.torrent

import okio.FileHandle
import okio.FileSystem
import okio.ForwardingFileSystem
import okio.Path
import okio.Path.Companion.toPath
import okio.Sink
import okio.Source
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.channels.SeekableByteChannel
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.NoSuchFileException
import java.nio.file.OpenOption
import java.nio.file.Paths
import java.nio.file.SecureDirectoryStream
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.READ
import java.nio.file.StandardOpenOption.WRITE
import java.nio.file.attribute.BasicFileAttributeView
import java.io.IOException

/** File operations traverse pinned directories, refusing symlinks at every untrusted component. */
internal object SafeTorrentFileSystem : ForwardingFileSystem(FileSystem.SYSTEM) {
  private fun <T> parent(path: Path, block: (SecureDirectoryStream<java.nio.file.Path>,
    java.nio.file.Path) -> T): T {
    val absolute = Paths.get(path.toString()).toAbsolutePath().normalize()
    val opened = mutableListOf<SecureDirectoryStream<java.nio.file.Path>>()
    try {
      val root = Files.newDirectoryStream(absolute.root)
      if (root !is SecureDirectoryStream<java.nio.file.Path>) {
        root.close()
        throw IOException("Torrent storage requires secure directory operations on this filesystem")
      }
      opened += root
      for (part in absolute.parent) {
        opened += opened.last().newDirectoryStream(part, NOFOLLOW_LINKS)
      }
      return block(opened.last(), absolute.fileName)
    } finally { opened.asReversed().forEach { it.close() } }
  }

  override fun openReadOnly(file: Path): FileHandle = open(file, writable = false,
    mustCreate = false, mustExist = true)

  override fun openReadWrite(file: Path, mustCreate: Boolean, mustExist: Boolean): FileHandle =
    open(file, true, mustCreate, mustExist)

  private fun open(file: Path, writable: Boolean, mustCreate: Boolean,
    mustExist: Boolean): FileHandle = parent(file) { directory, name ->
    val options = mutableSetOf<OpenOption>(READ, NOFOLLOW_LINKS)
    if (writable) options += WRITE
    if (mustCreate) options += CREATE_NEW else if (writable && !mustExist) options += CREATE
    val channel = directory.newByteChannel(name, options)
    try {
      val attributes = directory.getFileAttributeView(name, BasicFileAttributeView::class.java,
        NOFOLLOW_LINKS).readAttributes()
      if (!attributes.isRegularFile) throw IOException("Torrent output is not a regular file")
      ChannelHandle(channel, writable)
    } catch (e: Throwable) { channel.close(); throw e }
  }

  override fun source(file: Path): Source {
    val handle = openReadOnly(file)
    return handle.source().also { handle.close() }
  }

  override fun sink(file: Path, mustCreate: Boolean): Sink {
    val handle = openReadWrite(file, mustCreate, mustExist = false)
    try {
      handle.resize(0)
      return handle.sink().also { handle.close() }
    } catch (e: Throwable) { handle.close(); throw e }
  }

  override fun appendingSink(file: Path, mustExist: Boolean): Sink {
    val handle = openReadWrite(file, mustCreate = false, mustExist = mustExist)
    return handle.appendingSink().also { handle.close() }
  }

  override fun atomicMove(source: Path, target: Path) = parent(source) { from, name ->
    parent(target) { to, destination -> from.move(name, to, destination) }
  }

  override fun delete(path: Path, mustExist: Boolean) {
    try {
      parent(path) { directory, name ->
        val attributes = directory.getFileAttributeView(name, BasicFileAttributeView::class.java,
          NOFOLLOW_LINKS).readAttributes()
        if (attributes.isDirectory) directory.deleteDirectory(name) else directory.deleteFile(name)
      }
    } catch (e: NoSuchFileException) { if (mustExist) throw e }
  }

  override fun createDirectory(dir: Path, mustCreate: Boolean) =
    createSecureTorrentDirectory(dir, mustCreate)

  private class ChannelHandle(private val channel: SeekableByteChannel, writable: Boolean) :
    FileHandle(writable) {
    @Synchronized
    override fun protectedRead(fileOffset: Long, array: ByteArray, arrayOffset: Int,
      byteCount: Int): Int {
      channel.position(fileOffset)
      return channel.read(ByteBuffer.wrap(array, arrayOffset, byteCount))
    }
    @Synchronized
    override fun protectedWrite(fileOffset: Long, array: ByteArray, arrayOffset: Int,
      byteCount: Int) {
      channel.position(fileOffset)
      val buffer = ByteBuffer.wrap(array, arrayOffset, byteCount)
      while (buffer.hasRemaining()) channel.write(buffer)
    }
    override fun protectedSize(): Long = channel.size()
    @Synchronized
    override fun protectedResize(size: Long) {
      if (size <= channel.size()) channel.truncate(size) else {
        channel.position(size - 1)
        channel.write(ByteBuffer.wrap(byteArrayOf(0)))
      }
    }
    override fun protectedFlush() {
      (channel as? FileChannel ?: throw IOException("Filesystem cannot flush torrent data"))
        .force(true)
    }
    override fun protectedClose() = channel.close()
  }
}

internal expect fun createSecureTorrentDirectory(path: Path, mustCreate: Boolean)
