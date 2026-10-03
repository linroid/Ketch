package com.linroid.ketch.updater

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.zip.GZIPOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ArchivesTest {
  private val dir = Files.createTempDirectory("ketch-archives-test").toFile()
  private val out = File(dir, "out")

  @AfterTest
  fun cleanUp() {
    dir.deleteRecursively()
  }

  @Test
  fun extractArchive_tarGz_writesFilesAndKeepsTheExecutableBit() {
    val binary = ByteArray(1500) { it.toByte() }
    val archive = tarGz(
      entry("ketch", binary, mode = "755"),
      entry("licenses/", ByteArray(0), type = '5', mode = "755"),
      entry("licenses/LICENSE.txt", "MIT".toByteArray()),
    )
    extractArchive(archive, out)

    assertContentEquals(binary, File(out, "ketch").readBytes())
    assertTrue(File(out, "ketch").canExecute())
    assertEquals("MIT", File(out, "licenses/LICENSE.txt").readText())
    assertFalse(File(out, "licenses/LICENSE.txt").canExecute())
  }

  @Test
  fun extractArchive_tarGzLongNames_usesGnuAndPaxNames() {
    val long = "licenses/" + "n".repeat(120) + ".txt"
    val archive = tarGz(
      entry("././@LongLink", (long + "\u0000").toByteArray(), type = 'L'),
      entry("truncated", "gnu".toByteArray()),
      entry("PaxHeaders/ketch", paxRecord("path", "licenses/pax.txt"), type = 'x'),
      entry("ketch", "pax".toByteArray()),
      entry("link", ByteArray(0), type = '2'),
    )
    extractArchive(archive, out)

    assertEquals("gnu", File(out, long).readText())
    assertEquals("pax", File(out, "licenses/pax.txt").readText())
    assertFalse(File(out, "truncated").exists())
    assertFalse(File(out, "link").exists())
  }

  @Test
  fun extractArchive_entryOutsideTheFolder_fails() {
    val archive = tarGz(entry("../escape", "x".toByteArray()))
    assertFailsWith<IOException> { extractArchive(archive, out) }
    assertFalse(File(dir, "escape").exists())
  }

  @Test
  fun extractArchive_truncatedTar_fails() {
    val whole = tarBytes(entry("ketch", ByteArray(2000)))
    val archive = File(dir, "cut.tar.gz").apply { writeBytes(gzip(whole.copyOf(1024))) }
    assertFailsWith<IOException> { extractArchive(archive, out) }
  }

  @Test
  fun extractArchive_zip_writesFiles() {
    val archive = File(dir, "ketch.zip")
    ZipOutputStream(archive.outputStream()).use { zip ->
      zip.putNextEntry(ZipEntry("ketch.exe"))
      zip.write("exe".toByteArray())
      zip.putNextEntry(ZipEntry("licenses/"))
      zip.putNextEntry(ZipEntry("licenses/LICENSE.txt"))
      zip.write("MIT".toByteArray())
    }
    extractArchive(archive, out)

    assertEquals("exe", File(out, "ketch.exe").readText())
    assertEquals("MIT", File(out, "licenses/LICENSE.txt").readText())
  }

  @Test
  fun extractArchive_zipSlip_fails() {
    val archive = File(dir, "evil.zip")
    ZipOutputStream(archive.outputStream()).use { zip ->
      zip.putNextEntry(ZipEntry("../../evil.txt"))
      zip.write("x".toByteArray())
    }
    assertFailsWith<IOException> { extractArchive(archive, out) }
  }

  private fun tarGz(vararg entries: ByteArray): File =
    File(dir, "ketch.tar.gz").apply { writeBytes(gzip(tarBytes(*entries))) }

  /** The tar stream of [entries] and its end marker, not yet compressed. */
  private fun tarBytes(vararg entries: ByteArray): ByteArray =
    entries.fold(ByteArray(0)) { all, entry -> all + entry } + ByteArray(BLOCK * 2)

  private fun gzip(bytes: ByteArray): ByteArray {
    val buffer = ByteArrayOutputStream()
    GZIPOutputStream(buffer).use { it.write(bytes) }
    return buffer.toByteArray()
  }

  /** A ustar header for [name] followed by [data], padded to whole blocks. */
  private fun entry(
    name: String,
    data: ByteArray,
    type: Char = '0',
    mode: String = "644",
  ): ByteArray {
    val header = ByteArray(BLOCK)
    name.toByteArray().copyInto(header, 0)
    "0000$mode\u0000".toByteArray().copyInto(header, 100)
    "%011o\u0000".format(data.size).toByteArray().copyInto(header, 124)
    header[156] = type.code.toByte()
    "ustar\u000000".toByteArray().copyInto(header, 257)
    val padding = (BLOCK - data.size % BLOCK) % BLOCK
    return header + data + ByteArray(padding)
  }

  private fun paxRecord(key: String, value: String): ByteArray {
    val body = " $key=$value\n"
    // The length counts itself; two digits suffice for these records.
    return "${body.length + 2}$body".toByteArray()
  }

  private companion object {
    const val BLOCK = 512
  }
}
