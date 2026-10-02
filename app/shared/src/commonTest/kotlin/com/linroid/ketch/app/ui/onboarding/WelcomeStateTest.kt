package com.linroid.ketch.app.ui.onboarding

import com.linroid.ketch.config.UiPreferences
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class WelcomeStateTest {

  @Test
  fun needsWelcome_newUserOnPhone_isTrue() {
    assertTrue(needsWelcome(WelcomePlatform.Android, UiPreferences()))
    assertTrue(needsWelcome(WelcomePlatform.Ios, UiPreferences()))
  }

  @Test
  fun needsWelcome_flowSeen_isFalse() {
    val seen = UiPreferences(onboardingVersion = WELCOME_VERSION)

    assertFalse(needsWelcome(WelcomePlatform.Android, seen))
  }

  @Test
  fun needsWelcome_desktopOrWeb_isFalse() {
    assertFalse(needsWelcome(platform = null, ui = UiPreferences()))
  }

  @Test
  fun next_throughEveryStep_endsOnTheLast() {
    val welcome = WelcomeState()

    assertTrue(welcome.next())
    assertEquals(WelcomeStep.Use, welcome.step)
    assertTrue(welcome.next())
    assertEquals(WelcomeStep.Intake, welcome.step)
    assertFalse(welcome.next())
    assertEquals(WelcomeStep.Intake, welcome.step)
  }

  @Test
  fun back_onFirstStep_staysThere() {
    val welcome = WelcomeState()

    assertFalse(welcome.canGoBack)
    assertFalse(welcome.back())
    assertEquals(WelcomeStep.Folder, welcome.step)
  }

  @Test
  fun back_fromUse_returnsToFolderWithItsChoice() {
    val welcome = WelcomeState(WelcomeStep.Use, folder = DOWNLOAD)

    assertTrue(welcome.back())

    assertEquals(WelcomeStep.Folder, welcome.step)
    assertEquals(DOWNLOAD, welcome.folder)
  }

  @Test
  fun choose_download_goesOnToIntake() {
    val welcome = WelcomeState(WelcomeStep.Use)

    assertTrue(welcome.choose(WelcomeUse.Download))

    assertEquals(WelcomeStep.Intake, welcome.step)
  }

  @Test
  fun choose_control_endsTheFlow() {
    val welcome = WelcomeState(WelcomeStep.Use)

    assertFalse(welcome.choose(WelcomeUse.Control))

    assertEquals(WelcomeStep.Use, welcome.step)
  }

  @Test
  fun chooseFolder_applied_keepsTheFolder() = runTest {
    val welcome = WelcomeState()
    val applied = mutableListOf<String>()

    welcome.chooseFolder(pick = { DOWNLOAD }, apply = { applied += it })

    assertEquals(listOf(DOWNLOAD), applied)
    assertEquals(DOWNLOAD, welcome.folder)
    assertNull(welcome.folderError)
    assertFalse(welcome.choosingFolder)
  }

  @Test
  fun chooseFolder_pickerClosed_changesNothing() = runTest {
    val welcome = WelcomeState(folder = DOWNLOAD)

    welcome.chooseFolder(pick = { null }, apply = { error("Not applied") })

    assertEquals(DOWNLOAD, welcome.folder)
    assertNull(welcome.folderError)
    assertFalse(welcome.choosingFolder)
  }

  @Test
  fun chooseFolder_applyFails_saysSoWithoutTheFolder() = runTest {
    val welcome = WelcomeState()

    welcome.chooseFolder(pick = { DOWNLOAD }, apply = { throw IllegalStateException("No grant") })

    assertNull(welcome.folder)
    assertNotNull(welcome.folderError)
    assertFalse(welcome.folderError.orEmpty().contains("No grant"))
    assertFalse(welcome.choosingFolder)
  }

  @Test
  fun chooseFolder_whilePicking_showsItIsBusy() = runTest {
    val welcome = WelcomeState()
    val picked = CompletableDeferred<String?>()

    val choosing = launch { welcome.chooseFolder(pick = { picked.await() }, apply = {}) }
    runCurrent()

    assertTrue(welcome.choosingFolder)
    picked.complete(null)
    choosing.join()
    assertFalse(welcome.choosingFolder)
  }

  private companion object {
    const val DOWNLOAD = "content://com.android.externalstorage.documents/tree/primary%3ADownload"
  }
}
