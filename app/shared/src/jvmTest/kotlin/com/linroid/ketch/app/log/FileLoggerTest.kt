package com.linroid.ketch.app.log

import com.linroid.ketch.api.log.LogLevel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toOkioPath
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FileLoggerTest {
  private val fileSystem = FileSystem.SYSTEM
  private val directory = Files.createTempDirectory("ketch-log").toOkioPath()

  @AfterTest
  fun deleteDirectory() {
    fileSystem.deleteRecursively(directory)
  }

  private fun TestScope.fileLogger(minLevel: LogLevel = LogLevel.DEBUG) = FileLogger(
    fileSystem = fileSystem,
    directory = directory,
    dispatcher = StandardTestDispatcher(testScheduler),
    minLevel = minLevel,
  )

  private fun read(path: Path): String = fileSystem.read(path) { readUtf8() }

  @Test
  fun flush_writesRecordsInOrderAtOrAboveMinLevel() = runTest {
    val logger = fileLogger(minLevel = LogLevel.INFO)
    logger.d("[Tag] skipped")
    logger.i("[Tag] first")
    logger.w("[Tag] second", IllegalStateException("boom"))

    logger.flush()

    val lines = read(directory / "ketch.log").lines()
    assertTrue(lines[0].endsWith(" [INFO] [Tag] first"), lines[0])
    assertTrue(lines[1].endsWith(" [WARN] [Tag] second"), lines[1])
    assertTrue(lines[2].startsWith("java.lang.IllegalStateException: boom"), lines[2])
    assertFalse(lines.any { "skipped" in it })
    logger.close()
  }

  @Test
  fun exportTo_includesRecordsLoggedBeforeIt() = runTest {
    val logger = fileLogger()
    logger.i("[Tag] one")
    logger.i("[Tag] two")
    val target = directory / "shared" / SHARED_LOG_FILE_NAME

    logger.exportTo(target)

    val lines = read(target).lines().filter { it.isNotEmpty() }
    assertEquals(2, lines.size)
    assertTrue(lines[1].endsWith("[Tag] two"))
    logger.close()
  }

  @Test
  fun log_afterWriteFailure_dropsRecordAndRecovers() = runTest {
    // A file where the log folder should be makes every write fail.
    val folder = directory / "logs"
    fileSystem.write(folder) {}
    val logger = FileLogger(fileSystem, folder, StandardTestDispatcher(testScheduler))
    logger.i("[Tag] lost")
    logger.flush()

    fileSystem.delete(folder)
    logger.i("[Tag] kept")
    logger.flush()

    val text = read(folder / "ketch.log")
    assertTrue("[Tag] kept" in text)
    assertFalse("lost" in text)
    logger.close()
  }

  @Test
  fun close_writesQueuedRecordsAndIgnoresLaterOnes() = runTest {
    val logger = fileLogger()
    logger.i("[Tag] queued")

    logger.close()
    logger.i("[Tag] too late")

    val text = read(directory / "ketch.log")
    assertTrue("[Tag] queued" in text)
    assertFalse("too late" in text)
  }
}
