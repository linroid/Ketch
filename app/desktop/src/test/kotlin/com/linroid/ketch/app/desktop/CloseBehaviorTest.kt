package com.linroid.ketch.app.desktop

import androidx.compose.ui.window.WindowState
import com.linroid.ketch.app.i18n.load
import com.linroid.ketch.config.CloseAction
import kotlinx.coroutines.test.runTest
import java.awt.desktop.QuitResponse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CloseBehaviorTest {
  private var downloads = 0
  private val saved = mutableListOf<CloseAction>()
  private val quits = mutableListOf<QuitResponse?>()
  private var hides = 0
  private val windowState = WindowState()

  private fun behavior(
    action: CloseAction = CloseAction.Ask,
    traySupported: Boolean = true,
    startHidden: Boolean = false,
  ) = CloseBehavior(
    windowState = windowState,
    closeAction = action,
    startHidden = startHidden,
    traySupported = traySupported,
    activeDownloads = { downloads },
    saveCloseAction = { saved += it },
    onFirstHide = { hides++ },
    quit = { quits += it },
  )

  /** A behavior asking whether to keep [running] downloads going once the window closes. */
  private fun asking(running: Int, traySupported: Boolean = true): CloseBehavior {
    downloads = running
    return behavior(traySupported = traySupported).apply { closeWindow() }
  }

  private data class OutcomeCase(
    val action: CloseAction,
    val downloads: Int,
    val traySupported: Boolean = true,
    val answer: CloseAction? = null,
  )

  @Test
  fun closeOutcome_eachSetting_decidesWhatClosingDoes() {
    val cases = listOf(
      OutcomeCase(CloseAction.Ask, 3) to CloseOutcome.Ask,
      OutcomeCase(CloseAction.Ask, 0) to CloseOutcome.Hide,
      // An answer given this run is followed.
      OutcomeCase(CloseAction.Ask, 3, answer = CloseAction.Background) to CloseOutcome.Hide,
      OutcomeCase(CloseAction.Ask, 3, answer = CloseAction.Quit) to CloseOutcome.Quit,
      // Without a tray the window minimizes instead of hiding.
      OutcomeCase(CloseAction.Background, 3, traySupported = false) to CloseOutcome.Minimize,
      OutcomeCase(CloseAction.Ask, 0, traySupported = false) to CloseOutcome.Minimize,
      OutcomeCase(CloseAction.Quit, 0) to CloseOutcome.Quit,
    )
    for ((case, expected) in cases) {
      val outcome = closeOutcome(case.action, case.downloads, case.traySupported, case.answer)
      assertEquals(expected, outcome, "$case")
    }
  }

  @Test
  fun closeWindow_downloadsActive_asksThenKeepsRunningHidden() {
    downloads = 3
    val behavior = behavior()

    behavior.closeWindow()
    assertEquals(LifecycleDialog.KeepRunning(3), behavior.dialog)
    assertTrue(behavior.windowVisible)

    behavior.confirm()
    assertNull(behavior.dialog)
    assertFalse(behavior.windowVisible)
    assertEquals(1, hides)
    assertEquals(emptyList(), saved)
    assertEquals(emptyList(), quits)
  }

  @Test
  fun closeWindow_afterKeepRunningThisRun_hidesWithoutAsking() {
    val behavior = asking(3)
    behavior.confirm()
    behavior.showWindow()

    behavior.closeWindow()

    assertNull(behavior.dialog)
    assertFalse(behavior.windowVisible)
    assertEquals(1, hides)
  }

  @Test
  fun dismiss_dontAskAgain_savesQuitAndQuits() {
    val behavior = asking(2)

    behavior.dismiss(dontAskAgain = true)

    assertEquals(listOf(CloseAction.Quit), saved)
    assertEquals(CloseAction.Quit, behavior.closeAction)
    assertEquals(listOf<QuitResponse?>(null), quits)
  }

  @Test
  fun confirm_dontAskAgain_savesBackground() {
    val behavior = asking(2)

    behavior.confirm(dontAskAgain = true)

    assertEquals(listOf(CloseAction.Background), saved)
    assertEquals(CloseAction.Background, behavior.closeAction)
  }

  @Test
  fun cancel_keepRunningQuestion_leavesTheWindowOpen() {
    val behavior = asking(1)

    behavior.cancel()

    assertNull(behavior.dialog)
    assertTrue(behavior.windowVisible)
    assertEquals(0, hides)
  }

  @Test
  fun closeWindow_noTray_minimizes() {
    val behavior = behavior(action = CloseAction.Background, traySupported = false)

    behavior.closeWindow()

    assertTrue(windowState.isMinimized)
    assertTrue(behavior.windowVisible)
  }

  @Test
  fun confirm_keepRunningWithoutTray_minimizesInsteadOfHiding() {
    val behavior = asking(2, traySupported = false)

    behavior.confirm()

    assertNull(behavior.dialog)
    assertTrue(behavior.windowVisible)
    assertTrue(windowState.isMinimized)
    assertEquals(0, hides)
  }

  @Test
  fun closeAction_setAfterAnAnswer_asksAgain() {
    val behavior = asking(2)
    behavior.confirm()
    behavior.showWindow()

    behavior.closeAction = CloseAction.Ask
    behavior.closeWindow()

    assertEquals(LifecycleDialog.KeepRunning(2), behavior.dialog)
  }

  @Test
  fun init_startHiddenWithoutTray_startsMinimized() {
    val behavior = behavior(startHidden = true, traySupported = false)

    assertTrue(behavior.windowVisible)
    assertTrue(windowState.isMinimized)
  }

  @Test
  fun showWindow_hidden_showsAndRestores() {
    val behavior = behavior(startHidden = true)
    windowState.isMinimized = true

    behavior.showWindow()

    assertTrue(behavior.windowVisible)
    assertFalse(windowState.isMinimized)
  }

  @Test
  fun backgroundLaunch_createsTheWindowOnlyWhileItShows() {
    val behavior = behavior(startHidden = true)
    assertFalse(behavior.windowVisible)

    behavior.showWindow()
    assertTrue(behavior.windowVisible)

    behavior.closeWindow()
    assertFalse(behavior.windowVisible)
  }

  @Test
  fun requestQuit_idle_quitsAtOnce() {
    val behavior = behavior()

    behavior.requestQuit()

    assertEquals(listOf<QuitResponse?>(null), quits)
    assertFalse(behavior.windowVisible)
  }

  @Test
  fun requestQuit_downloadsActive_asksFirst() {
    downloads = 3
    val behavior = behavior()

    behavior.requestQuit()
    assertEquals(LifecycleDialog.ConfirmQuit(3), behavior.dialog)
    assertEquals(emptyList(), quits)

    behavior.confirm()
    assertEquals(listOf<QuitResponse?>(null), quits)
  }

  @Test
  fun requestQuit_askedTwice_quitsAtOnce() {
    downloads = 3
    val behavior = behavior()

    behavior.requestQuit()
    behavior.requestQuit()

    assertEquals(1, quits.size)
  }

  @Test
  fun requestQuit_macCancelled_cancelsTheSystemQuit() {
    downloads = 3
    val response = RecordingQuitResponse()
    val behavior = behavior()

    behavior.requestQuit(response)
    behavior.dismiss()

    assertTrue(response.cancelled)
    assertEquals(emptyList(), quits)
  }

  @Test
  fun requestQuit_macConfirmed_handsTheResponseToQuit() {
    downloads = 3
    val response = RecordingQuitResponse()
    val behavior = behavior()

    behavior.requestQuit(response)
    behavior.confirm()

    assertFalse(response.cancelled)
    assertEquals(listOf<QuitResponse?>(response), quits)
  }

  @Test
  fun closeWindow_whileQuitting_doesNothing() {
    val behavior = behavior()
    behavior.requestQuit()

    behavior.closeWindow()
    behavior.showWindow()

    assertFalse(behavior.windowVisible)
    assertEquals(0, hides)
  }

  @Test
  fun dialogCopy_keepRunningOnMac_namesTheMenuBarAndTheShortcut() = runTest {
    val copy = dialogCopy(LifecycleDialog.KeepRunning(3), DesktopOs.MAC, traySupported = true)

    assertEquals("Keep downloading in the background?", copy.title.load())
    assertEquals(
      "Ketch stays in the menu bar and finishes 3 downloads. " +
        "Quit any time from the menu bar icon or with ⌘Q.",
      copy.body.load(),
    )
    assertEquals("Keep Running", copy.confirm.load())
    assertEquals("Quit Ketch", copy.dismiss.load())
    assertTrue(copy.dontAskAgain)
  }

  @Test
  fun dialogCopy_keepRunningOnWindows_namesTheNotificationArea() = runTest {
    val copy = dialogCopy(LifecycleDialog.KeepRunning(1), DesktopOs.WINDOWS, traySupported = true)

    assertEquals(
      "Ketch stays in the notification area and finishes 1 download. " +
        "Quit any time from the notification area icon or with Ctrl+Q.",
      copy.body.load(),
    )
  }

  @Test
  fun dialogCopy_keepRunningWithoutTray_staysMinimized() = runTest {
    val copy = dialogCopy(LifecycleDialog.KeepRunning(2), DesktopOs.LINUX, traySupported = false)

    assertEquals(
      "Ketch stays minimized and finishes 2 downloads. Quit any time with Ctrl+Q.",
      copy.body.load(),
    )
  }

  @Test
  fun dialogCopy_confirmQuit_saysTheDownloadsPause() = runTest {
    val copy = dialogCopy(LifecycleDialog.ConfirmQuit(3), DesktopOs.MAC, traySupported = true)

    assertEquals("Quit Ketch?", copy.title.load())
    assertEquals("3 downloads will pause and resume next time you open Ketch.", copy.body.load())
    assertEquals("Quit", copy.confirm.load())
    assertEquals("Cancel", copy.dismiss.load())
    assertFalse(copy.dontAskAgain)
  }

  private class RecordingQuitResponse : QuitResponse {
    var cancelled = false

    override fun performQuit() {}

    override fun cancelQuit() {
      cancelled = true
    }
  }
}
