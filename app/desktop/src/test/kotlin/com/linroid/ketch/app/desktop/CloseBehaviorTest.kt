package com.linroid.ketch.app.desktop

import androidx.compose.ui.window.WindowState
import com.linroid.ketch.config.CloseAction
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

  @Test
  fun closeOutcome_askWithDownloads_asks() {
    assertEquals(CloseOutcome.Ask, closeOutcome(CloseAction.Ask, 3, traySupported = true))
  }

  @Test
  fun closeOutcome_askWhileIdle_hides() {
    assertEquals(CloseOutcome.Hide, closeOutcome(CloseAction.Ask, 0, traySupported = true))
  }

  @Test
  fun closeOutcome_askAnsweredThisRun_followsTheAnswer() {
    assertEquals(
      CloseOutcome.Hide,
      closeOutcome(CloseAction.Ask, 3, traySupported = true, answer = CloseAction.Background),
    )
    assertEquals(
      CloseOutcome.Quit,
      closeOutcome(CloseAction.Ask, 3, traySupported = true, answer = CloseAction.Quit),
    )
  }

  @Test
  fun closeOutcome_noTray_minimizesInsteadOfHiding() {
    assertEquals(
      CloseOutcome.Minimize,
      closeOutcome(CloseAction.Background, 3, traySupported = false),
    )
    assertEquals(CloseOutcome.Minimize, closeOutcome(CloseAction.Ask, 0, traySupported = false))
  }

  @Test
  fun closeOutcome_quit_quitsWhateverRuns() {
    assertEquals(CloseOutcome.Quit, closeOutcome(CloseAction.Quit, 0, traySupported = true))
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
    downloads = 3
    val behavior = behavior()
    behavior.closeWindow()
    behavior.confirm()
    behavior.showWindow()

    behavior.closeWindow()

    assertNull(behavior.dialog)
    assertFalse(behavior.windowVisible)
    assertEquals(1, hides)
  }

  @Test
  fun dismiss_dontAskAgain_savesQuitAndQuits() {
    downloads = 2
    val behavior = behavior()
    behavior.closeWindow()

    behavior.dismiss(dontAskAgain = true)

    assertEquals(listOf(CloseAction.Quit), saved)
    assertEquals(CloseAction.Quit, behavior.closeAction)
    assertEquals(listOf<QuitResponse?>(null), quits)
  }

  @Test
  fun confirm_dontAskAgain_savesBackground() {
    downloads = 2
    val behavior = behavior()
    behavior.closeWindow()

    behavior.confirm(dontAskAgain = true)

    assertEquals(listOf(CloseAction.Background), saved)
    assertEquals(CloseAction.Background, behavior.closeAction)
  }

  @Test
  fun cancel_keepRunningQuestion_leavesTheWindowOpen() {
    downloads = 1
    val behavior = behavior()
    behavior.closeWindow()

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
    downloads = 2
    val behavior = behavior(traySupported = false)
    behavior.closeWindow()

    behavior.confirm()

    assertNull(behavior.dialog)
    assertTrue(behavior.windowVisible)
    assertTrue(windowState.isMinimized)
    assertEquals(0, hides)
  }

  @Test
  fun closeAction_setAfterAnAnswer_asksAgain() {
    downloads = 2
    val behavior = behavior()
    behavior.closeWindow()
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
  fun dialogCopy_keepRunningOnMac_namesTheMenuBarAndTheShortcut() {
    val copy = dialogCopy(LifecycleDialog.KeepRunning(3), DesktopOs.MAC, traySupported = true)

    assertEquals("Keep downloading in the background?", copy.title)
    assertEquals(
      "Ketch stays in the menu bar and finishes 3 downloads. " +
        "Quit any time from the menu bar icon or with ⌘Q.",
      copy.body,
    )
    assertEquals("Keep Running", copy.confirm)
    assertEquals("Quit Ketch", copy.dismiss)
    assertTrue(copy.dontAskAgain)
  }

  @Test
  fun dialogCopy_keepRunningOnWindows_namesTheNotificationArea() {
    val copy = dialogCopy(LifecycleDialog.KeepRunning(1), DesktopOs.WINDOWS, traySupported = true)

    assertEquals(
      "Ketch stays in the notification area and finishes 1 download. " +
        "Quit any time from the notification area icon or with Ctrl+Q.",
      copy.body,
    )
  }

  @Test
  fun dialogCopy_keepRunningWithoutTray_staysMinimized() {
    val copy = dialogCopy(LifecycleDialog.KeepRunning(2), DesktopOs.LINUX, traySupported = false)

    assertEquals(
      "Ketch stays minimized and finishes 2 downloads. Quit any time with Ctrl+Q.",
      copy.body,
    )
  }

  @Test
  fun dialogCopy_confirmQuit_saysTheDownloadsPause() {
    val copy = dialogCopy(LifecycleDialog.ConfirmQuit(3), DesktopOs.MAC, traySupported = true)

    assertEquals("Quit Ketch?", copy.title)
    assertEquals("3 downloads will pause and resume next time you open Ketch.", copy.body)
    assertEquals("Quit", copy.confirm)
    assertEquals("Cancel", copy.dismiss)
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
