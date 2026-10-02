package com.linroid.ketch.app.desktop

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

class OpenedFilesTest {

  @Test
  fun fileArguments_magnetAndWebLinks_routesThemToLinks() {
    val magnet = "magnet:?xt=urn:btih:0123456789abcdef0123456789abcdef01234567"
    val opened = fileArguments(
      listOf(magnet, "https://example.com/a.iso", "HTTP://example.com/b", "ftps://host/c.bin"),
    )

    assertEquals(
      listOf(magnet, "https://example.com/a.iso", "HTTP://example.com/b", "ftps://host/c.bin"),
      opened.links,
    )
    assertEquals(emptyList(), opened.files)
  }

  @Test
  fun fileArguments_pathsAndFileUris_routesThemToAbsoluteFiles() {
    val opened = fileArguments(listOf("a.torrent", "file:///tmp/b.torrent"))

    assertEquals(listOf(File("a.torrent").absoluteFile, File("/tmp/b.torrent")), opened.files)
    assertEquals(emptyList(), opened.links)
  }

  @Test
  fun fileArguments_flagsBlanksAndOtherSchemes_leavesThemOut() {
    val opened = fileArguments(listOf(BACKGROUND_FLAG, "", "ketch://open", "mailto:x@y"))

    assertEquals(OpenedArguments(), opened)
  }

  @Test
  fun fileArguments_pairingLink_routesItToLinks() {
    val opened = fileArguments(listOf("ketch://pair?host=a#token"))

    assertEquals(listOf("ketch://pair?host=a#token"), opened.links)
  }

  @Test
  fun fileArguments_windowsDrivePath_staysAFile() {
    val opened = fileArguments(listOf("C:\\Downloads\\a.torrent"))

    assertEquals(listOf(File("C:\\Downloads\\a.torrent").absoluteFile), opened.files)
  }

  @Test
  fun toArguments_filesAndLinks_roundTripsThroughFileArguments() {
    val opened = OpenedArguments(listOf(File("/tmp/a.torrent")), listOf("magnet:?xt=urn:btih:a"))

    assertEquals(opened, fileArguments(opened.toArguments()))
  }

  @Test
  fun startCommand_macBundle_opensItThroughLaunchServices() {
    val app = AppCommand(
      listOf("/Applications/Ketch.app/Contents/MacOS/Ketch"),
      File("/Applications/Ketch.app"),
    )

    assertEquals(listOf("/usr/bin/open", "/Applications/Ketch.app"), app.startCommand())
  }

  @Test
  fun startCommand_macBundleInTheBackground_leavesTheFrontAppInFront() {
    val app = AppCommand(
      listOf("/Applications/Ketch.app/Contents/MacOS/Ketch"),
      File("/Applications/Ketch.app"),
    )

    assertEquals(
      listOf("/usr/bin/open", "-g", "/Applications/Ketch.app", "--args", BACKGROUND_FLAG),
      app.startCommand(listOf(BACKGROUND_FLAG)),
    )
  }

  @Test
  fun startCommand_launcher_appendsTheArguments() {
    val app = AppCommand(listOf("/opt/ketch/bin/Ketch"))

    assertEquals(
      listOf("/opt/ketch/bin/Ketch", BACKGROUND_FLAG),
      app.startCommand(listOf(BACKGROUND_FLAG)),
    )
  }
}
