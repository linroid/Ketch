package com.linroid.ketch.app.desktop

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LoginItemTest {
  private val root = Files.createTempDirectory("ketch-login-item").toFile()
  private val home = File(root, "home")
  private val macApp = AppCommand(
    listOf("/Applications/Ketch.app/Contents/MacOS/Ketch"),
    File("/Applications/Ketch.app"),
  )

  @AfterTest
  fun cleanUp() {
    root.deleteRecursively()
  }

  private fun loginItem(
    os: DesktopOs,
    app: AppCommand,
    runCommand: (List<String>) -> Int = { 0 },
  ) = LoginItem(os, home, app, File(home, ".config"), runCommand)

  @Test
  fun launchAgentPlist_hidden_opensTheBundleInTheBackground() {
    val doctype = "<!DOCTYPE plist PUBLIC \"-//Apple//DTD PLIST 1.0//EN\" " +
      "\"http://www.apple.com/DTDs/PropertyList-1.0.dtd\">"
    val expected = """
      |<?xml version="1.0" encoding="UTF-8"?>
      |$doctype
      |<plist version="1.0">
      |<dict>
      |  <key>Label</key>
      |  <string>com.linroid.ketch</string>
      |  <key>ProgramArguments</key>
      |  <array>
      |    <string>/usr/bin/open</string>
      |    <string>/Applications/Ketch.app</string>
      |    <string>--args</string>
      |    <string>--background</string>
      |  </array>
      |  <key>RunAtLoad</key>
      |  <true/>
      |  <key>LimitLoadToSessionType</key>
      |  <string>Aqua</string>
      |</dict>
      |</plist>
      |""".trimMargin()

    assertEquals(expected, loginItem(DesktopOs.MAC, macApp).launchAgentPlist(hidden = true))
  }

  @Test
  fun launchAgentPlist_specialCharacters_escapesThemForXml() {
    val app = AppCommand(listOf("/Users/me/A & <B>/Ketch"))

    val plist = loginItem(DesktopOs.MAC, app).launchAgentPlist(hidden = false)

    assertTrue("<string>/Users/me/A &amp; &lt;B&gt;/Ketch</string>" in plist)
    assertFalse("--background" in plist)
  }

  @Test
  fun autostartDesktopEntry_hidden_startsTheLauncherInTheBackground() {
    val app = AppCommand(listOf("/opt/ketch/bin/Ketch"))
    val expected = """
      |[Desktop Entry]
      |Type=Application
      |Name=Ketch
      |Comment=Opens Ketch when you log in
      |Exec=/opt/ketch/bin/Ketch --background
      |Terminal=false
      |X-GNOME-Autostart-enabled=true
      |""".trimMargin()

    assertEquals(expected, loginItem(DesktopOs.LINUX, app).autostartDesktopEntry(hidden = true))
  }

  @Test
  fun runValue_windows_quotesEachPart() {
    val app = AppCommand(listOf("C:\\Program Files\\Ketch\\Ketch.exe"))

    assertEquals(
      "\"C:\\Program Files\\Ketch\\Ketch.exe\" \"--background\"",
      loginItem(DesktopOs.WINDOWS, app).runValue(hidden = true),
    )
  }

  @Test
  fun set_mac_writesThenRemovesTheLaunchAgent() {
    val item = loginItem(DesktopOs.MAC, macApp)
    val agent = File(home, "Library/LaunchAgents/com.linroid.ketch.plist")

    item.set(enabled = true, hidden = false)
    assertTrue(item.isEnabled())
    assertEquals(item.launchAgentPlist(hidden = false), agent.readText())

    item.set(enabled = false, hidden = false)
    assertFalse(agent.exists())
    assertFalse(item.isEnabled())
  }

  @Test
  fun set_linux_writesTheAutostartEntryUnderTheConfigHome() {
    val item = loginItem(DesktopOs.LINUX, AppCommand(listOf("/opt/ketch/bin/Ketch")))

    item.set(enabled = true, hidden = true)

    val entry = File(home, ".config/autostart/ketch.desktop")
    assertEquals(item.autostartDesktopEntry(hidden = true), entry.readText())
  }

  @Test
  fun set_windows_importsTheRunValue() {
    val app = AppCommand(listOf("C:\\Ketch\\Ketch.exe"))
    val imported = mutableListOf<String>()
    val item = loginItem(DesktopOs.WINDOWS, app) { command ->
      assertEquals(listOf("reg", "import"), command.take(2))
      imported += File(command[2]).readBytes().decodeRegFile()
      0
    }

    item.set(enabled = true, hidden = true)
    item.set(enabled = false, hidden = true)

    val key = "[HKEY_CURRENT_USER\\Software\\Microsoft\\Windows\\CurrentVersion\\Run]"
    assertEquals(
      listOf(
        "Windows Registry Editor Version 5.00\r\n\r\n$key\r\n" +
          "\"Ketch\"=\"\\\"C:\\\\Ketch\\\\Ketch.exe\\\" \\\"--background\\\"\"\r\n",
        "Windows Registry Editor Version 5.00\r\n\r\n$key\r\n\"Ketch\"=-\r\n",
      ),
      imported,
    )
  }

  @Test
  fun desktopEntryExec_reservedCharacters_quotesAndEscapesThem() {
    assertEquals(
      "/opt/ketch/bin/Ketch \"/home/me/my \\\\\$HOME\" 100%%",
      desktopEntryExec(listOf("/opt/ketch/bin/Ketch", "/home/me/my \$HOME", "100%")),
    )
  }
}

/** Decodes a `.reg` file written by [importRegFile]: UTF-16LE after a byte order mark. */
internal fun ByteArray.decodeRegFile(): String {
  check(this[0] == 0xFF.toByte() && this[1] == 0xFE.toByte()) { "No byte order mark" }
  return copyOfRange(2, size).toString(Charsets.UTF_16LE)
}
