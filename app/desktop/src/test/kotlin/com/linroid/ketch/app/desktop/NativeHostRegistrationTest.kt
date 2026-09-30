package com.linroid.ketch.app.desktop

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NativeHostRegistrationTest {
  private val root = Files.createTempDirectory("ketch-native-host").toFile()
  private val home = File(root, "home")
  private val configDir = File(root, "config")
  private val app = AppCommand(listOf("/Applications/Ketch.app/Contents/MacOS/Ketch"))

  @AfterTest
  fun cleanUp() {
    root.deleteRecursively()
  }

  private fun registration(os: DesktopOs, runCommand: (List<String>) -> Unit = { }) =
    NativeHostRegistration(configDir, home, os, app, File(home, ".config"), runCommand)

  @Test
  fun register_mac_writesManifestsOnlyForBrowsersThatWereUsed() {
    val support = File(home, "Library/Application Support")
    File(support, "Google/Chrome").mkdirs()
    File(support, "Mozilla").mkdirs()

    val registered = registration(DesktopOs.MAC).register()

    val chrome = File(support, "Google/Chrome/NativeMessagingHosts/com.linroid.ketch.json")
    val firefox = File(support, "Mozilla/NativeMessagingHosts/com.linroid.ketch.json")
    assertEquals(listOf(chrome.parent, firefox.parent), registered)
    assertFalse(File(support, "Microsoft Edge").exists())

    val script = File(configDir, "native-messaging/ketch-native-host")
    assertTrue(script.canExecute())
    val chromeManifest = Json.parseToJsonElement(chrome.readText()).jsonObject
    assertEquals("com.linroid.ketch", chromeManifest["name"]?.jsonPrimitive?.content)
    assertEquals(script.absolutePath, chromeManifest["path"]?.jsonPrimitive?.content)
    assertEquals(
      "chrome-extension://kddcjkhnjcjhekohejnehplbnjclbdbl/",
      chromeManifest["allowed_origins"]?.jsonArray?.single()?.jsonPrimitive?.content,
    )
    val firefoxManifest = Json.parseToJsonElement(firefox.readText()).jsonObject
    assertEquals(
      "ketch@linroid.github.io",
      firefoxManifest["allowed_extensions"]?.jsonArray?.single()?.jsonPrimitive?.content,
    )
  }

  @Test
  fun register_linux_usesTheConfigHomeAndFirefoxsOwnDirectory() {
    File(home, ".config/chromium").mkdirs()
    File(home, ".mozilla").mkdirs()

    val registered = registration(DesktopOs.LINUX).register()

    assertEquals(
      listOf(
        File(home, ".config/chromium/NativeMessagingHosts").path,
        File(home, ".mozilla/native-messaging-hosts").path,
      ),
      registered,
    )
  }

  @Test
  fun register_windows_pointsRegistryValuesAtTheManifests() {
    val commands = mutableListOf<List<String>>()

    registration(DesktopOs.WINDOWS) { commands += it }.register()

    val dir = File(configDir, "native-messaging")
    val chrome = commands.first()
    assertEquals(
      listOf(
        "reg", "add", "HKCU\\Software\\Google\\Chrome\\NativeMessagingHosts\\com.linroid.ketch",
        "/ve", "/t", "REG_SZ", "/d", File(dir, "chromium.json").path, "/f",
      ),
      chrome,
    )
    val firefox = commands.last()
    assertEquals("HKCU\\Software\\Mozilla\\NativeMessagingHosts\\com.linroid.ketch", firefox[2])
    assertEquals(File(dir, "firefox.json").path, firefox[7])
    assertTrue(File(dir, "ketch-native-host.bat").isFile)
  }

  @Test
  fun hostScript_quotesTheAppCommandForTheShell() {
    val command = AppCommand(listOf("/opt/it's here/java", "-cp", "/a b/*"))
    val script = NativeHostRegistration(configDir, home, DesktopOs.LINUX, command).hostScript()
    assertTrue(script.startsWith("#!/bin/sh\n"))
    assertTrue(
      script.endsWith(
        "exec '/opt/it'\\''s here/java' '-cp' '/a b/*' '--native-messaging-host' \"\$@\"\n",
      ),
      script,
    )
  }

  @Test
  fun hostScript_quotesTheAppCommandForBatchFiles() {
    val command = AppCommand(listOf("C:\\Program Files\\Ketch 100%\\Ketch.exe"))
    val script = NativeHostRegistration(configDir, home, DesktopOs.WINDOWS, command).hostScript()
    assertEquals(
      "@echo off\r\n" +
        "\"C:\\Program Files\\Ketch 100%%\\Ketch.exe\" \"--native-messaging-host\" %*\r\n",
      script,
    )
  }
}
