package com.linroid.ketch.app.desktop

import com.linroid.ketch.app.platform.DesktopHooks
import com.linroid.ketch.app.platform.DetectedBrowser
import kotlinx.coroutines.test.runTest
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DesktopIntegrationStatusTest {
  private val dir = Files.createTempDirectory("integration-status").toFile()
  private val file = File(dir, "browser-extension.properties")
  private var installed = listOf("Chrome", "Firefox")
  private val outputs = mutableMapOf<String, String>()
  private val handlers = DefaultHandlers(DesktopOs.LINUX, App) { command ->
    outputs[command.last()]
  }

  @AfterTest
  fun cleanUp() {
    dir.deleteRecursively()
  }

  private fun integration() = DesktopIntegrationStatus(file, { installed }, handlers)

  @Test
  fun refresh_listsInstalledBrowsersAndDefaultApps() {
    outputs["x-scheme-handler/magnet"] = "ketch-handler.desktop\n"
    outputs["application/x-bittorrent"] = "org.qbittorrent.desktop\n"
    val integration = integration()

    integration.refresh()

    val status = integration.status
    assertEquals(listOf(DetectedBrowser("Chrome"), DetectedBrowser("Firefox")), status.browsers)
    assertFalse(status.extensionConnected)
    assertTrue(status.magnetHandler)
    assertFalse(status.torrentFileHandler)
  }

  @Test
  fun extensionConnected_marksThatBrowserAtOnce() {
    val integration = integration()
    integration.refresh()

    integration.extensionConnected("Firefox")

    val status = integration.status
    assertTrue(status.extensionConnected)
    assertEquals(
      listOf(DetectedBrowser("Chrome"), DetectedBrowser("Firefox", extensionConnected = true)),
      status.browsers,
    )
  }

  @Test
  fun extensionConnected_isRememberedAcrossLaunches() {
    integration().extensionConnected("Chrome")

    val next = integration()

    assertTrue(next.status.extensionConnected)
    assertEquals(listOf(DetectedBrowser("Chrome", extensionConnected = true)), next.status.browsers)
  }

  @Test
  fun extensionConnected_fromABrowserNotFound_listsItAfterTheOthers() {
    val integration = integration()
    integration.refresh()

    integration.extensionConnected("Vivaldi")

    assertEquals(
      listOf("Chrome", "Firefox", "Vivaldi"),
      integration.status.browsers.map { it.name },
    )
  }

  @Test
  fun extensionConnected_unknownBrowser_countsWithoutAddingARow() {
    val integration = integration()
    integration.refresh()

    integration.extensionConnected(null)

    val status = integration.status
    assertTrue(status.extensionConnected)
    assertTrue(status.browsers.none { it.extensionConnected })
    assertEquals(listOf("Chrome", "Firefox"), status.browsers.map { it.name })
  }

  @Test
  fun refreshingDefaults_afterRegistering_asksForTheDefaultAppsAgain() = runTest {
    val integration = integration()
    val hooks = object : DesktopHooks {
      override suspend fun registerMagnetHandler(): Boolean {
        outputs["x-scheme-handler/magnet"] = "ketch-handler.desktop"
        return true
      }
    }.refreshingDefaults(integration)
    assertFalse(integration.status.magnetHandler)

    assertTrue(hooks.registerMagnetHandler())

    assertTrue(integration.status.magnetHandler)
  }

  @Test
  fun defaultHandlers_mac_comparesTheBundleOfTheDefaultApp() {
    var bundle = "com.linroid.ketch.app.desktop\n"
    val mac = DefaultHandlers(DesktopOs.MAC, App) { command ->
      assertEquals("osascript", command.first())
      bundle
    }
    assertTrue(mac.opensMagnetLinks())

    bundle = "com.linroid.ketch\n"
    assertFalse(mac.opensMagnetLinks())
  }

  @Test
  fun defaultHandlers_windows_findsTheAppInTheRegisteredCommand() {
    val output = "    (Default)    REG_SZ    \"C:\\Program Files\\Ketch\\Ketch.exe\" \"%1\"\r\n"
    val windows = DefaultHandlers(DesktopOs.WINDOWS, WindowsApp) { command ->
      output.takeIf { command[2].endsWith("magnet\\shell\\open\\command") }
    }

    assertTrue(windows.opensMagnetLinks())
    assertFalse(windows.opensTorrentFiles())
  }

  @Test
  fun defaultHandlers_windowsUserChoiceOfAnotherApp_answersNo() {
    val command = "    (Default)    REG_SZ    \"C:\\Program Files\\Ketch\\Ketch.exe\" \"%1\"\r\n"
    val windows = DefaultHandlers(DesktopOs.WINDOWS, WindowsApp) { args ->
      when {
        args[2].endsWith("magnet\\shell\\open\\command") -> command
        args[2].endsWith("magnet\\UserChoice") -> "    ProgId    REG_SZ    OtherApp.Url.magnet\r\n"
        args[2].endsWith("Classes\\.torrent") -> "    (Default)    REG_SZ    Ketch.torrent\r\n"
        args[2].endsWith(".torrent\\UserChoice") -> "    ProgId    REG_SZ    Ketch.torrent\r\n"
        else -> null
      }
    }

    assertFalse(windows.opensMagnetLinks())
    assertTrue(windows.opensTorrentFiles())
  }

  @Test
  fun refresh_whileAnEarlierRefreshIsAsking_keepsTheLaterAnswer() {
    val asking = CountDownLatch(1)
    val answer = CountDownLatch(1)
    var first = true
    val slow = DefaultHandlers(DesktopOs.LINUX, App) { command ->
      // The first refresh read the magnet default before Ketch became it, then waits.
      if (command.last() == "application/x-bittorrent" && first) {
        first = false
        asking.countDown()
        answer.await()
      }
      outputs[command.last()]
    }
    val integration = DesktopIntegrationStatus(file, { installed }, slow)
    val earlier = thread { integration.refresh() }
    asking.await()
    outputs["x-scheme-handler/magnet"] = "ketch-handler.desktop"

    val later = thread { integration.refresh() }
    while (later.isAlive && later.state != Thread.State.BLOCKED) Thread.onSpinWait()
    answer.countDown()
    earlier.join()
    later.join()

    assertTrue(integration.status.magnetHandler)
  }

  @Test
  fun defaultHandlers_commandFails_answersNo() {
    val failing = DefaultHandlers(DesktopOs.MAC, App) { null }

    assertFalse(failing.opensMagnetLinks())
    assertFalse(failing.opensTorrentFiles())
  }

  @Test
  fun connectingBrowser_hostStartedByABrowser_namesTheBrowser() {
    val processes = listOf(
      Self,
      process(10, parent = 1, "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome"),
      process(20, parent = 10, App.command.first(), listOf(NativeMessagingHost.FLAG, "chrome-x")),
    )

    assertEquals("Chrome", connectingBrowser(processes, Self))
  }

  @Test
  fun connectingBrowser_hostStartedThroughAShell_namesTheBrowser() {
    val self = process(5, parent = 1, "C:\\Program Files\\Ketch\\Ketch.exe")
    val processes = listOf(
      self,
      process(10, parent = 1, "C:\\Program Files\\Microsoft\\Edge\\Application\\msedge.exe"),
      process(11, parent = 10, "C:\\Windows\\System32\\cmd.exe"),
      process(12, parent = 11, "C:\\Program Files\\Ketch\\Ketch.exe"),
    )

    assertEquals("Edge", connectingBrowser(processes, self))
  }

  @Test
  fun connectingBrowser_noHostRunning_isUnknown() {
    val processes = listOf(Self, process(10, parent = 1, "/usr/lib/firefox/firefox"))

    assertNull(connectingBrowser(processes, Self))
  }

  @Test
  fun browserNamed_executables_matchTheBrowserNames() {
    assertEquals("Chrome", browserNamed("/opt/google/chrome/chrome"))
    assertEquals("Edge", browserNamed("C:\\Program Files\\Microsoft\\Edge\\msedge.exe"))
    assertEquals("Edge", browserNamed("/Applications/Edge.app/Contents/MacOS/Microsoft Edge Beta"))
    assertEquals("Brave", browserNamed("C:\\Program Files\\BraveSoftware\\brave.exe"))
    assertEquals("Firefox", browserNamed("/Applications/Firefox.app/Contents/MacOS/firefox"))
    assertEquals("Arc", browserNamed("/Applications/Arc.app/Contents/MacOS/Arc"))
    assertEquals("Chromium", browserNamed("/usr/lib/chromium/chromium-browser"))
    assertNull(browserNamed("/System/Library/CoreServices/launchd"))
    assertNull(browserNamed(null))
  }

  private fun process(
    pid: Long,
    parent: Long?,
    command: String,
    arguments: List<String> = emptyList(),
  ) = ProcessEntry(pid, parent, command, arguments)

  private companion object {
    val App = AppCommand(listOf("/Applications/Ketch.app/Contents/MacOS/Ketch"))
    val WindowsApp = AppCommand(listOf("C:\\Program Files\\Ketch\\Ketch.exe"))
    val Self = ProcessEntry(5, 1, App.command.first(), listOf("--background"))
  }
}
