package com.linroid.ketch.app.log

import okio.FileSystem
import okio.Path.Companion.toOkioPath
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class RotatingLogFileTest {
  private val fileSystem = FileSystem.SYSTEM
  private val directory = Files.createTempDirectory("ketch-log").toOkioPath()

  @AfterTest
  fun deleteDirectory() {
    fileSystem.deleteRecursively(directory)
  }

  private fun logFile(maxFileBytes: Long, maxFiles: Int = 3) =
    RotatingLogFile(fileSystem, directory, maxFileBytes, maxFiles)

  private fun read(name: String): String = fileSystem.read(directory / name) { readUtf8() }

  private fun fileNames(): List<String> = fileSystem.list(directory).map { it.name }.sorted()

  @Test
  fun append_pastMaxSize_movesFileAside() {
    val log = logFile(maxFileBytes = 16)
    log.append("first 1") // 8 bytes with the line break
    log.append("second") // 15 bytes
    log.append("third") // would be 21
    log.close()

    assertEquals("first 1\nsecond\n", read("ketch.1.log"))
    assertEquals("third\n", read("ketch.log"))
  }

  @Test
  fun append_beyondMaxFiles_deletesOldest() {
    // Every record fills a file of its own.
    val log = logFile(maxFileBytes = 1, maxFiles = 3)
    listOf("a", "b", "c", "d").forEach(log::append)
    log.close()

    assertEquals(listOf("ketch.1.log", "ketch.2.log", "ketch.log"), fileNames())
    assertEquals("b\n", read("ketch.2.log"))
    assertEquals("c\n", read("ketch.1.log"))
    assertEquals("d\n", read("ketch.log"))
  }

  @Test
  fun append_recordLargerThanMax_isNotSplit() {
    val log = logFile(maxFileBytes = 4)
    log.append("0123456789")
    log.close()

    assertEquals(listOf("ketch.log"), fileNames())
    assertEquals("0123456789\n", read("ketch.log"))
  }

  @Test
  fun append_afterRestart_continuesExistingFile() {
    logFile(maxFileBytes = 16).apply {
      append("before")
      close()
    }
    logFile(maxFileBytes = 16).apply {
      append("after")
      close()
    }

    assertEquals("before\nafter\n", read("ketch.log"))
  }

  @Test
  fun append_afterRestart_countsExistingSizeTowardsRotation() {
    logFile(maxFileBytes = 16).apply {
      append("earlier run") // 12 bytes
      close()
    }
    logFile(maxFileBytes = 16).apply {
      append("this run") // would be 21
      close()
    }

    assertEquals("earlier run\n", read("ketch.1.log"))
    assertEquals("this run\n", read("ketch.log"))
  }

  @Test
  fun append_singleFileKept_startsOverWhenFull() {
    val log = logFile(maxFileBytes = 8, maxFiles = 1)
    log.append("old one")
    log.append("new one")
    log.close()

    assertEquals(listOf("ketch.log"), fileNames())
    assertEquals("new one\n", read("ketch.log"))
  }

  @Test
  fun copyTo_joinsFilesOldestFirstIncludingUnflushedRecords() {
    val log = logFile(maxFileBytes = 1)
    listOf("a", "b", "c").forEach(log::append)
    val target = directory / "shared" / "ketch-logs.txt"

    log.copyTo(target)

    assertEquals("a\nb\nc\n", fileSystem.read(target) { readUtf8() })
    log.close()
  }

  @Test
  fun constructor_noFilesKept_isRejected() {
    assertFailsWith<IllegalArgumentException> { logFile(maxFileBytes = 16, maxFiles = 0) }
  }
}
