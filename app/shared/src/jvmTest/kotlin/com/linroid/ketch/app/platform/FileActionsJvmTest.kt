package com.linroid.ketch.app.platform

import kotlinx.coroutines.test.runTest
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class FileActionsJvmTest {
  private val dir = createTempDirectory().toFile()
  private val file = File(dir, "ubuntu 24.04.iso").apply { writeText("iso") }

  @AfterTest
  fun cleanUp() {
    dir.deleteRecursively()
  }

  @Test
  fun revealCommand_macOs_selectsInFinder() {
    val command = revealCommand(DesktopOs.MacOs, file, canBrowse = true)

    assertEquals(RevealCommand.Run(listOf("open", "-R", file.path)), command)
    assertEquals("Show in Finder", revealLabel(DesktopOs.MacOs))
  }

  @Test
  fun revealCommand_windows_selectsInExplorer() {
    val command = revealCommand(DesktopOs.Windows, file, canBrowse = false)

    assertEquals(RevealCommand.Run(listOf("explorer.exe", "/select,", file.path)), command)
    assertEquals("Show in Explorer", revealLabel(DesktopOs.Windows))
  }

  @Test
  fun revealCommand_linuxWithBrowse_browsesFileDirectory() {
    val command = revealCommand(DesktopOs.Linux, file, canBrowse = true)

    assertEquals(RevealCommand.Browse(file), command)
    assertEquals("Show in folder", revealLabel(DesktopOs.Linux))
  }

  @Test
  fun revealCommand_linuxWithoutBrowse_opensParentFolder() {
    val command = revealCommand(DesktopOs.Linux, file, canBrowse = false)

    assertEquals(RevealCommand.OpenFolder(dir.absoluteFile), command)
  }

  @Test
  fun revealCommand_missingFile_opensClosestExistingFolder() {
    val missing = File(dir, "gone/deeper/ubuntu.iso")

    DesktopOs.entries.forEach { os ->
      val command = revealCommand(os, missing, canBrowse = true)

      assertEquals(RevealCommand.OpenFolder(dir.absoluteFile), command, "on $os")
    }
  }

  @Test
  fun of_osNames_mapsOtherUnixToLinux() {
    assertEquals(DesktopOs.MacOs, DesktopOs.of("Mac OS X"))
    assertEquals(DesktopOs.Windows, DesktopOs.of("Windows 11"))
    assertEquals(DesktopOs.Linux, DesktopOs.of("Linux"))
    assertEquals(DesktopOs.Linux, DesktopOs.of("FreeBSD"))
  }

  @Test
  fun open_missingFile_throwsMovedOrDeleted() = runTest {
    val error = assertFailsWith<FileActionException> {
      DesktopFileActions.open(File(dir, "gone.iso").path)
    }

    assertEquals("gone.iso was moved or deleted", error.message)
  }
}
