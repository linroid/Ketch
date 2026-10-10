package com.linroid.ketch.cli

import java.io.File
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class CliInstanceTest {
  private val dir: File = createTempDirectory("ketch-instance").toFile()

  @AfterTest
  fun cleanUp() {
    dir.deleteRecursively()
  }

  @Test
  fun `only one process runs the downloads at a time`() {
    val first = assertNotNull(CliInstance.acquire(dir))
    first.use {
      assertNull(CliInstance.acquire(dir))
    }

    // Closing lets the next one run them.
    CliInstance.acquire(dir)?.close() ?: error("Not released")
  }

  @Test
  fun `publish describes the process until it closes`() {
    val info = CliInstance.Info("ketch server", pid = 7, url = "http://127.0.0.1:8642", token = "t")
    val instance = assertNotNull(CliInstance.acquire(dir))

    instance.publish(info.copy(url = null))
    instance.publish(info)

    assertEquals(info, CliInstance.read(dir))
    instance.close()
    assertNull(CliInstance.read(dir))
    assertFalse(File(dir, CliInstance.INFO_FILE).exists())
  }

  @Test
  fun `the info file is readable by its owner only, as it holds the token`() {
    if ("posix" !in FileSystems.getDefault().supportedFileAttributeViews()) return
    CliInstance.acquire(dir)!!.use { instance ->
      instance.publish(CliInstance.Info("ketch server", pid = 7, token = "t"))

      val permissions = Files.getPosixFilePermissions(File(dir, CliInstance.INFO_FILE).toPath())
      assertEquals(PosixFilePermissions.fromString("rw-------"), permissions)
    }
  }

  @Test
  fun `read ignores a file it cannot understand`() {
    File(dir, CliInstance.INFO_FILE).writeText("{\"command\":")
    assertNull(CliInstance.read(dir))

    File(dir, CliInstance.INFO_FILE).writeText("{\"command\":\"ketch server\"}")
    assertNull(CliInstance.read(dir))
  }
}
