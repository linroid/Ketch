package com.linroid.ketch.app.desktop

import java.io.File
import java.io.IOException
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class MagnetHandlerTest {
  private val root = Files.createTempDirectory("ketch-magnet-handler").toFile()
  private val home = File(root, "home")
  private val windowsApp = AppCommand(listOf("C:\\Program Files\\Ketch\\Ketch.exe"))
  private val linuxApp = AppCommand(listOf("/opt/ketch/bin/Ketch"))
  private val macApp = AppCommand(
    listOf("/Applications/Ketch.app/Contents/MacOS/Ketch"),
    File("/Applications/Ketch.app"),
  )

  @AfterTest
  fun cleanUp() {
    root.deleteRecursively()
  }

  private fun handler(
    os: DesktopOs,
    app: AppCommand,
    runCommand: (List<String>) -> Int = { 0 },
    runScript: (String) -> String = { "0\n" },
  ) = MagnetHandler(os, home, app, File(home, ".local/share"), runCommand, runScript)

  @Test
  fun windowsMagnetRegFile_opensLinksWithTheLauncher() {
    val classes = "[HKEY_CURRENT_USER\\Software\\Classes\\magnet"
    val exe = "\\\"C:\\\\Program Files\\\\Ketch\\\\Ketch.exe\\\""
    val expected = "Windows Registry Editor Version 5.00\r\n" +
      "\r\n$classes]\r\n" +
      "@=\"URL:Magnet link\"\r\n" +
      "\"URL Protocol\"=\"\"\r\n" +
      "\r\n$classes\\DefaultIcon]\r\n" +
      "@=\"$exe,0\"\r\n" +
      "\r\n$classes\\shell\\open\\command]\r\n" +
      "@=\"$exe \\\"%1\\\"\"\r\n"

    assertEquals(expected, handler(DesktopOs.WINDOWS, windowsApp).windowsMagnetRegFile())
  }

  @Test
  fun registerMagnet_windows_importsTheRegFile() {
    val imported = mutableListOf<String>()
    val handler = handler(DesktopOs.WINDOWS, windowsApp, runCommand = { command ->
      imported += File(command.last()).readBytes().decodeRegFile()
      0
    })

    handler.registerMagnet()

    assertEquals(listOf(handler.windowsMagnetRegFile()), imported)
  }

  @Test
  fun registerTorrentFiles_windowsImportFails_throws() {
    val handler = handler(DesktopOs.WINDOWS, windowsApp, runCommand = { 1 })

    assertFailsWith<IOException> { handler.registerTorrentFiles() }
  }

  @Test
  fun linuxDesktopEntry_declaresMagnetLinksAndTorrentFiles() {
    val expected = """
      |[Desktop Entry]
      |Type=Application
      |Name=Ketch
      |Comment=Opens magnet links and .torrent files in Ketch
      |Exec=/opt/ketch/bin/Ketch %U
      |MimeType=x-scheme-handler/magnet;application/x-bittorrent;
      |NoDisplay=true
      |Terminal=false
      |""".trimMargin()

    assertEquals(expected, handler(DesktopOs.LINUX, linuxApp).linuxDesktopEntry())
  }

  @Test
  fun registerMagnet_linux_writesTheEntryAndMakesItTheDefault() {
    val commands = mutableListOf<List<String>>()
    val handler = handler(DesktopOs.LINUX, linuxApp, runCommand = { commands += it; 0 })

    handler.registerMagnet()

    val applications = File(home, ".local/share/applications")
    val entry = File(applications, "ketch-handler.desktop")
    assertEquals(handler.linuxDesktopEntry(), entry.readText())
    assertEquals(
      listOf(
        listOf("update-desktop-database", applications.path),
        listOf("xdg-mime", "default", "ketch-handler.desktop", "x-scheme-handler/magnet"),
      ),
      commands,
    )
  }

  @Test
  fun registerMagnet_linuxWithoutTheDesktopDatabaseTool_stillSetsTheDefault() {
    val commands = mutableListOf<List<String>>()
    val handler = handler(DesktopOs.LINUX, linuxApp, runCommand = { command ->
      if (command.first() == "update-desktop-database") throw IOException("Not installed")
      commands += command
      0
    })

    handler.registerTorrentFiles()

    assertEquals(
      listOf(listOf("xdg-mime", "default", "ketch-handler.desktop", "application/x-bittorrent")),
      commands,
    )
  }

  @Test
  fun registerMagnet_macBundle_setsTheBundleAsTheDefault() {
    val scripts = mutableListOf<String>()
    val handler = handler(DesktopOs.MAC, macApp, runScript = { scripts += it; "0\n" })

    handler.registerMagnet()

    val call = "LSSetDefaultHandlerForURLScheme(\$('magnet'), \$('com.linroid.ketch'))"
    assertTrue(call in scripts.single())
  }

  @Test
  fun registerMagnet_macRefused_throws() {
    val handler = handler(DesktopOs.MAC, macApp, runScript = { "-54\n" })

    assertFailsWith<IOException> { handler.registerMagnet() }
  }

  @Test
  fun registerMagnet_macWithoutABundle_throws() {
    val handler = handler(DesktopOs.MAC, AppCommand(listOf("/usr/bin/java")))

    assertFailsWith<IOException> { handler.registerMagnet() }
  }
}
