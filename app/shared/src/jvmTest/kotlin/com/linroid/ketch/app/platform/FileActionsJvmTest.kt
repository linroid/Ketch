package com.linroid.ketch.app.platform

import com.linroid.ketch.app.i18n.load
import kotlinx.coroutines.test.runTest
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class FileActionsJvmTest {
  private val dir = createTempDirectory().toFile()
  private val file = File(dir, "ubuntu 24.04.iso").apply { writeText("iso") }

  @AfterTest
  fun cleanUp() {
    dir.deleteRecursively()
  }

  @Test
  fun openCommand_windows_usesExplorerEvenWhenDesktopSupportsOpen() {
    for (name in listOf("VibepolloSetup-v2.0.0.exe", "Ketch Setup.msi", "report.pdf")) {
      val target = File(dir, name)

      for (canOpen in listOf(true, false)) {
        assertEquals(
          OpenCommand.Run(listOf("explorer.exe", "\"${target.absolutePath}\"")),
          openCommand(DesktopOs.Windows, target, canOpen)
        )
      }
    }
  }

  @Test
  fun openCommand_windows_passesAbsolutePathAsOneLiteralArgument() {
    val target = File("Downloads/安装 O'Brien & 100% ! (1), setup.exe")

    assertEquals(
      OpenCommand.Run(listOf("explorer.exe", "\"${target.absolutePath}\"")),
      openCommand(DesktopOs.Windows, target, canOpen = true)
    )
  }

  @Test
  fun openCommand_windows_quotesCommaPathWithoutWhitespace() {
    // Root-relative keeps the path free of whitespace even if the user's home contains spaces.
    val target = File("${File.separator}Downloads${File.separator}release,1.exe")
    assertFalse(target.absolutePath.any { it.isWhitespace() })

    assertEquals(
      OpenCommand.Run(listOf("explorer.exe", "\"${target.absolutePath}\"")),
      openCommand(DesktopOs.Windows, target, canOpen = true)
    )
  }

  @Test
  fun openCommand_windows_opensFoldersThroughExplorer() {
    assertEquals(
      OpenCommand.Run(listOf("explorer.exe", "\"${dir.absolutePath}\"")),
      openCommand(DesktopOs.Windows, dir, canOpen = true)
    )
  }

  @Test
  fun openCommand_otherSystems_useDesktopWhenSupported() {
    for (os in listOf(DesktopOs.MacOs, DesktopOs.Linux)) {
      assertEquals(OpenCommand.Desktop(file), openCommand(os, file, canOpen = true))
    }
  }

  @Test
  fun openCommand_linuxWithoutDesktop_usesXdgOpen() {
    assertEquals(
      OpenCommand.Run(listOf("xdg-open", file.path)),
      openCommand(DesktopOs.Linux, file, canOpen = false)
    )
  }

  @Test
  fun openCommand_macOsWithoutDesktop_reportsUnsupported() {
    assertFailsWith<FileActionException> {
      openCommand(DesktopOs.MacOs, file, canOpen = false)
    }
  }

  @Test
  fun revealCommand_macOs_selectsInFinder() = runTest {
    val command = revealCommand(DesktopOs.MacOs, file, canBrowse = true)

    assertEquals(RevealCommand.Run(listOf("open", "-R", file.path)), command)
    assertEquals("Show in Finder", revealLabel(DesktopOs.MacOs).load())
  }

  @Test
  fun revealCommand_windows_selectsInExplorer() = runTest {
    val command = revealCommand(DesktopOs.Windows, file, canBrowse = false)

    assertEquals(RevealCommand.Run(listOf("explorer.exe", "/select,", file.path)), command)
    assertEquals("Show in Explorer", revealLabel(DesktopOs.Windows).load())
  }

  @Test
  fun revealCommand_linuxWithBrowse_browsesFileDirectory() = runTest {
    val command = revealCommand(DesktopOs.Linux, file, canBrowse = true)

    assertEquals(RevealCommand.Browse(file), command)
    assertEquals("Show in folder", revealLabel(DesktopOs.Linux).load())
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

  @Test
  fun moveToTrash_missingFile_throwsMovedOrDeleted() = runTest {
    val error = assertFailsWith<FileActionException> {
      DesktopFileActions.moveToTrash(File(dir, "gone.iso").path)
    }

    assertEquals("gone.iso was moved or deleted", error.message)
  }
}
