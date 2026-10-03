package com.linroid.ketch.cli

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class CliInstallationTest {
  private val dir = Files.createTempDirectory("ketch-install-test").toFile()
  private val bin = File(dir, "bin").apply { mkdirs() }
  private val extracted = File(dir, "extracted").apply { mkdirs() }

  @AfterTest
  fun cleanUp() {
    dir.deleteRecursively()
  }

  @Test
  fun install_unix_replacesTheBinaryAndKeepsItsPermissions() {
    val executable = File(bin, "ketch").apply { writeText("old") }
    Files.setPosixFilePermissions(executable.toPath(), PosixFilePermissions.fromString("rwxr-x---"))
    File(extracted, "ketch").writeText("new")

    CliInstallation(executable, windows = false).install(extracted)

    assertEquals("new", executable.readText())
    val permissions = Files.getPosixFilePermissions(executable.toPath())
    assertEquals("rwxr-x---", PosixFilePermissions.toString(permissions))
    assertEquals(listOf("ketch"), bin.list()?.toList())
  }

  @Test
  fun install_windows_setsTheRunningBinaryAside() {
    val executable = File(bin, "ketch.exe").apply { writeText("old") }
    File(bin, "ketch.exe.old").writeText("older")
    File(extracted, "ketch.exe").writeText("new")
    val installation = CliInstallation(executable, windows = true)

    installation.install(extracted)

    assertEquals("new", executable.readText())
    assertEquals("old", File(bin, "ketch.exe.old").readText())
    installation.removeReplaced()
    assertFalse(File(bin, "ketch.exe.old").exists())
  }

  @Test
  fun install_licenses_goWhereTheInstallerPutThem() {
    val executable = File(bin, "ketch").apply { writeText("old") }
    File(bin, "ketch-licenses").mkdirs()
    File(bin, "ketch-licenses/OLD.txt").writeText("kept")
    File(extracted, "ketch").writeText("new")
    File(extracted, "licenses").mkdirs()
    File(extracted, "licenses/LICENSE.txt").writeText("MIT")

    CliInstallation(executable, windows = false).install(extracted)

    assertEquals("MIT", File(bin, "ketch-licenses/LICENSE.txt").readText())
    assertEquals("kept", File(bin, "ketch-licenses/OLD.txt").readText())
    assertFalse(File(bin, "licenses").exists())
  }

  @Test
  fun install_unpackedArchive_updatesItsLicensesFolder() {
    val executable = File(bin, "ketch").apply { writeText("old") }
    File(bin, "licenses").mkdirs()
    File(extracted, "ketch").writeText("new")
    File(extracted, "licenses").mkdirs()
    File(extracted, "licenses/LICENSE.txt").writeText("MIT")

    CliInstallation(executable, windows = false).install(extracted)

    assertEquals("MIT", File(bin, "licenses/LICENSE.txt").readText())
    assertFalse(File(bin, "ketch-licenses").exists())
  }

  @Test
  fun install_archiveWithoutBinary_leavesTheOldOne() {
    val executable = File(bin, "ketch").apply { writeText("old") }
    File(extracted, "ketch.exe").writeText("other platform")

    assertFailsWith<IOException> { CliInstallation(executable, windows = false).install(extracted) }

    assertEquals("old", executable.readText())
    assertEquals(listOf("ketch"), bin.list()?.toList())
  }
}
