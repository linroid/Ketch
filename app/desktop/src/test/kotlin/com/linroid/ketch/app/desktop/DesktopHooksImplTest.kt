package com.linroid.ketch.app.desktop

import androidx.compose.ui.window.WindowState
import com.linroid.ketch.config.CloseAction
import com.linroid.ketch.config.DesktopSettings
import com.linroid.ketch.config.IntegrationSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DesktopHooksImplTest {
  private val root = Files.createTempDirectory("ketch-desktop-hooks").toFile()
  private val home = File(root, "home")
  private val app = AppCommand(listOf("/opt/ketch/bin/Ketch"))
  private val loginItem = LoginItem(DesktopOs.LINUX, home, app, File(home, ".config"))
  private val autostart = File(home, ".config/autostart/ketch.desktop")
  private val commands = mutableListOf<List<String>>()
  private val behavior = CloseBehavior(
    windowState = WindowState(),
    closeAction = CloseAction.Ask,
    startHidden = false,
    traySupported = true,
    activeDownloads = { 0 },
    saveCloseAction = {},
    onFirstHide = {},
    quit = {},
  )

  @AfterTest
  fun cleanUp() {
    root.deleteRecursively()
  }

  private fun hooks(
    settings: DesktopSettings = DesktopSettings(),
    os: DesktopOs = DesktopOs.LINUX,
  ) = DesktopHooksImpl(
    closeBehavior = behavior,
    settings = settings,
    logsDir = File(root, "logs"),
    files = null,
    loginItem = loginItem,
    handlers = MagnetHandler(os, home, app, File(home, ".local/share"), { commands += it; 0 }),
    io = Dispatchers.Unconfined,
    os = os,
  )

  @Test
  fun setCloseAction_quit_closingQuits() {
    hooks().setCloseAction(CloseAction.Quit)

    assertEquals(CloseAction.Quit, behavior.closeAction)
  }

  @Test
  fun setStartHidden_openAtLogin_rewritesTheLoginItem() = runTest {
    val hooks = hooks()
    hooks.setOpenAtLogin(true)
    assertEquals(loginItem.autostartDesktopEntry(hidden = true), autostart.readText())

    hooks.setStartHidden(false)

    assertEquals(loginItem.autostartDesktopEntry(hidden = false), autostart.readText())
  }

  @Test
  fun setStartHidden_notOpenAtLogin_addsNoLoginItem() = runTest {
    hooks().setStartHidden(false)

    assertFalse(autostart.exists())
  }

  @Test
  fun setOpenAtLogin_off_removesTheLoginItem() = runTest {
    val hooks = hooks()
    hooks.setOpenAtLogin(true)

    hooks.setOpenAtLogin(false)

    assertFalse(autostart.exists())
  }

  @Test
  fun refresh_loginItemRemovedOutsideKetch_leavesItRemoved() {
    hooks(DesktopSettings(openAtLogin = true)).refresh(IntegrationSettings())

    assertFalse(autostart.exists())
  }

  @Test
  fun refresh_loginItemPresent_rewritesIt() {
    autostart.parentFile.mkdirs()
    autostart.writeText("[Desktop Entry]\nExec=/old/Ketch\n")

    hooks(DesktopSettings(openAtLogin = true, startHidden = true)).refresh(IntegrationSettings())

    assertEquals(loginItem.autostartDesktopEntry(hidden = true), autostart.readText())
  }

  @Test
  fun refresh_handlersTurnedOn_registersThemAgain() {
    hooks().refresh(IntegrationSettings(magnetHandler = true, torrentFileHandler = true))

    val defaults = commands.filter { it.first() == "xdg-mime" }.map { it.last() }
    assertEquals(listOf("x-scheme-handler/magnet", "application/x-bittorrent"), defaults)
  }

  @Test
  fun refresh_mac_leavesTheHandlersToLaunchServices() {
    hooks(os = DesktopOs.MAC).refresh(IntegrationSettings(magnetHandler = true))

    assertTrue(commands.isEmpty())
  }
}
