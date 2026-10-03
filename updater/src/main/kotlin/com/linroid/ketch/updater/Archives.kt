package com.linroid.ketch.updater

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.zip.GZIPInputStream
import java.util.zip.ZipInputStream

/**
 * Extracts a release archive, `.tar.gz` or `.zip` by its name, into [dir]. Only regular files
 * and folders are written; an entry that would land outside [dir] fails the extraction.
 *
 * @throws IOException when the archive is damaged or names a path outside [dir].
 */
fun extractArchive(archive: File, dir: File) {
  dir.mkdirs()
  when {
    archive.name.endsWith(".zip") -> extractZip(archive, dir)
    archive.name.endsWith(".tar.gz") -> extractTarGz(archive, dir)
    else -> throw IOException("Unknown archive type: ${archive.name}")
  }
}

private fun extractZip(archive: File, dir: File) {
  ZipInputStream(archive.inputStream().buffered()).use { zip ->
    while (true) {
      val entry = zip.nextEntry ?: break
      val target = entryFile(dir, entry.name)
      if (entry.isDirectory) {
        target.mkdirs()
      } else {
        writeEntry(zip, target, executable = false)
      }
    }
  }
}

/**
 * Reads a ustar or GNU tar stream: regular files and folders, with names from GNU long-name and
 * pax `path` records; links and other entries are skipped.
 */
private fun extractTarGz(archive: File, dir: File) {
  GZIPInputStream(archive.inputStream().buffered()).use { input ->
    val header = ByteArray(TAR_BLOCK)
    var nextName: String? = null
    while (true) {
      if (input.readNBytes(header, 0, TAR_BLOCK) < TAR_BLOCK) throw IOException("Truncated tar")
      if (header.all { it == ZERO }) break
      val size = octal(header, SIZE_OFFSET, SIZE_LENGTH)
      val name = nextName ?: headerName(header)
      nextName = null
      when (header[TYPE_OFFSET].toInt().toChar()) {
        // GNU long name: the data is the next entry's name.
        'L' -> nextName = String(readData(input, size), Charsets.UTF_8).trimEnd('\u0000')
        // pax extended header: records of "<length> <key>=<value>\n".
        'x' -> nextName = paxPath(readData(input, size))
        '0', '\u0000' -> {
          val mode = octal(header, MODE_OFFSET, MODE_LENGTH)
          writeEntry(BoundedStream(input, size), entryFile(dir, name), mode and EXEC_BITS != 0L)
          skipPadding(input, size)
        }
        '5' -> {
          entryFile(dir, name).mkdirs()
          skip(input, padded(size))
        }
        else -> skip(input, padded(size))
      }
    }
  }
}

/** Where entry [name] goes in [dir]; refuses names that leave [dir]. */
private fun entryFile(dir: File, name: String): File {
  val root = dir.canonicalFile
  val target = File(root, name).canonicalFile
  if (target != root && !target.path.startsWith(root.path + File.separator)) {
    throw IOException("Archive entry $name is outside the archive")
  }
  return target
}

private fun writeEntry(input: InputStream, target: File, executable: Boolean) {
  target.parentFile?.mkdirs()
  target.outputStream().use { input.copyTo(it) }
  if (executable) target.setExecutable(true, false)
}

private fun headerName(header: ByteArray): String {
  val name = text(header, 0, NAME_LENGTH)
  val ustar = text(header, MAGIC_OFFSET, MAGIC_LENGTH).startsWith("ustar")
  val prefix = if (ustar) text(header, PREFIX_OFFSET, PREFIX_LENGTH) else ""
  return if (prefix.isEmpty()) name else "$prefix/$name"
}

private fun paxPath(data: ByteArray): String? = String(data, Charsets.UTF_8).lineSequence()
  .map { it.substringAfter(' ', "") }
  .firstOrNull { it.startsWith("path=") }
  ?.removePrefix("path=")

private fun text(header: ByteArray, offset: Int, length: Int): String {
  val end = (offset until offset + length).firstOrNull { header[it] == ZERO } ?: (offset + length)
  return String(header, offset, end - offset, Charsets.UTF_8)
}

private fun octal(header: ByteArray, offset: Int, length: Int): Long {
  val digits = text(header, offset, length).trim()
  return if (digits.isEmpty()) 0 else digits.toLongOrNull(8)
    ?: throw IOException("Bad number in tar header: $digits")
}

private fun readData(input: InputStream, size: Long): ByteArray {
  if (size > MAX_HEADER_DATA) throw IOException("Tar header record of $size bytes")
  val data = input.readNBytes(size.toInt())
  if (data.size.toLong() != size) throw IOException("Truncated tar")
  skipPadding(input, size)
  return data
}

private fun skipPadding(input: InputStream, size: Long) = skip(input, padded(size) - size)

private fun padded(size: Long): Long = (size + TAR_BLOCK - 1) / TAR_BLOCK * TAR_BLOCK

private fun skip(input: InputStream, count: Long) {
  var left = count
  while (left > 0) {
    val skipped = input.skip(left)
    if (skipped <= 0) {
      if (input.read() < 0) throw IOException("Truncated tar")
      left--
    } else {
      left -= skipped
    }
  }
}

/** The next [size] bytes of [input], which stays open. */
private class BoundedStream(private val input: InputStream, size: Long) : InputStream() {
  private var left = size

  override fun read(): Int {
    if (left <= 0) return -1
    val byte = input.read()
    if (byte < 0) throw IOException("Truncated tar")
    left--
    return byte
  }

  override fun read(b: ByteArray, off: Int, len: Int): Int {
    if (left <= 0) return -1
    val read = input.read(b, off, minOf(len.toLong(), left).toInt())
    if (read < 0) throw IOException("Truncated tar")
    left -= read
    return read
  }

  override fun close() {}
}

private const val TAR_BLOCK = 512
private const val ZERO: Byte = 0
private const val NAME_LENGTH = 100
private const val MODE_OFFSET = 100
private const val MODE_LENGTH = 8
private const val SIZE_OFFSET = 124
private const val SIZE_LENGTH = 12
private const val TYPE_OFFSET = 156
private const val MAGIC_OFFSET = 257
private const val MAGIC_LENGTH = 6
private const val PREFIX_OFFSET = 345
private const val PREFIX_LENGTH = 155
private const val EXEC_BITS = 0b001_001_001L
private const val MAX_HEADER_DATA = 1L shl 20
