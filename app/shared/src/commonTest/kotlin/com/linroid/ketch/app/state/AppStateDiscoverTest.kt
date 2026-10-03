package com.linroid.ketch.app.state

import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.text.input.TextFieldValue
import com.linroid.ketch.app.FakeAiProvider
import com.linroid.ketch.app.FakeKetchApi
import com.linroid.ketch.app.RecordingConfigStore
import com.linroid.ketch.app.feedback.AppMessage
import com.linroid.ketch.app.feedback.ToastMode
import com.linroid.ketch.app.i18n.load
import com.linroid.ketch.app.i18n.warmStrings
import com.linroid.ketch.app.input.KetchCommands
import com.linroid.ketch.app.instance.InstanceFactory
import com.linroid.ketch.app.instance.InstanceManager
import com.linroid.ketch.config.AiSettings
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AppStateDiscoverTest {
  // Toasts are read while the undo windows run on virtual time, which a string's first load on
  // a thread of its own would let pass.
  @BeforeTest
  fun loadStrings() = runTest { warmStrings() }

  private val blender = AiCandidate(
    url = "https://download.blender.org/a.dmg",
    title = "Blender",
    confidence = 0.9f,
    description = "",
  )

  private fun TestScope.appState(provider: AiDiscoveryProvider): AppState {
    val store = RecordingConfigStore()
    val manager = InstanceManager(
      factory = InstanceFactory(deviceName = "This Mac", embeddedFactory = { FakeKetchApi() }),
      configStore = store,
    )
    return AppState(
      instanceManager = manager,
      scope = backgroundScope,
      appSettings = AppSettingsController(store),
      aiSettings = AiSettingsController(factory = { provider }).apply {
        save(AiSettings(enabled = true))
      },
    )
  }

  private fun AppState.say(message: String) {
    aiDiscover.draft.text = TextFieldValue(message)
    aiDiscover.send()
  }

  /** Shows the Discover page, in a window in front, as the page reports it. */
  private fun AppState.showDiscoverPage() {
    aiDiscover.shown = true
    aiDiscover.inFront = true
  }

  /** Shows another page in place of Discover. */
  private fun AppState.leaveDiscoverPage() {
    aiDiscover.shown = false
    aiDiscover.inFront = false
  }

  /** Lets the app's state collectors see what changed, as a frame would. */
  private fun TestScope.settle() {
    runCurrent()
    Snapshot.sendApplyNotifications()
    runCurrent()
  }

  private suspend fun AppState.toast(title: String): AppMessage? =
    messages.active.value.firstOrNull { it.title.load() == title }

  private suspend fun AppMessage.click(label: String) {
    actions.first { it.label.load() == label }.onClick()
  }

  private fun asksToOpen(host: String) = FakeAiProvider(
    pages = listOf(AiPageRequest(url = "https://$host/", host = host, kind = AiPageKind.Page)),
  )

  @Test
  fun approvalWaiting_sessionNotOnScreen_postsAToastThatReviewsIt() = runTest {
    val state = appState(asksToOpen("download.blender.org"))
    state.say("blender")
    settle()
    val sessionId = assertNotNull(state.aiDiscover.currentId)
    state.aiDiscover.newSession()

    val toast = assertNotNull(state.toast("Discover needs your OK to open download.blender.org"))
    assertEquals(ToastMode.Sticky, toast.toast)
    assertTrue(toast.notify)
    assertEquals(1, state.discoverWaitingCount)
    toast.click("Review")

    assertEquals(sessionId, state.aiDiscover.currentId)
    assertEquals(KetchCommands.Discover, state.shellCommand)
    state.aiDiscover.answer(state.aiDiscover.approvals.single().id, PageAccessChoice.AllowOnce)
    settle()
    assertNull(state.toast("Discover needs your OK to open download.blender.org"))
    assertEquals(0, state.discoverWaitingCount)
  }

  @Test
  fun approvalWaiting_searchStopped_withdrawsTheToast() = runTest {
    val state = appState(asksToOpen("download.blender.org"))
    state.say("blender")
    settle()
    val title = "Discover needs your OK to open download.blender.org"
    assertNotNull(state.toast(title))

    state.aiDiscover.stop()
    settle()

    assertNull(state.toast(title))
    assertTrue(state.messages.history.value.none { it.title.load() == title })
    assertEquals(0, state.discoverWaitingCount)
  }

  @Test
  fun approvalWaiting_sessionOnScreen_postsNoToastUntilAnotherPageShows() = runTest {
    val state = appState(asksToOpen("download.blender.org"))
    state.showDiscoverPage()
    state.say("blender")
    settle()
    assertTrue(state.messages.active.value.isEmpty())

    // Looking away from the card the user read, at another app or the Settings window.
    state.aiDiscover.inFront = false
    settle()
    assertTrue(state.messages.active.value.isEmpty())
    assertTrue(state.messages.history.value.isEmpty())

    state.leaveDiscoverPage()
    settle()
    assertNotNull(state.toast("Discover needs your OK to open download.blender.org"))
  }

  @Test
  fun approvalWaiting_sessionShownInAWindowAway_postsAToastThatNotifies() = runTest {
    val state = appState(asksToOpen("download.blender.org"))
    state.showDiscoverPage()
    state.say("blender")
    // The window goes to the tray before the search asks.
    state.aiDiscover.inFront = false
    settle()

    val title = "Discover needs your OK to open download.blender.org"
    assertTrue(assertNotNull(state.toast(title)).notify)
    state.aiDiscover.inFront = true
    settle()
    assertNull(state.toast(title))
  }

  @Test
  fun approvalWaiting_shownAndLeftAgain_asksForOneNotificationOnly() = runTest {
    val state = appState(asksToOpen("download.blender.org"))
    state.say("blender")
    settle()
    val title = "Discover needs your OK to open download.blender.org"
    assertTrue(assertNotNull(state.toast(title)).notify)

    state.showDiscoverPage()
    settle()
    assertNull(state.toast(title))
    state.leaveDiscoverPage()
    settle()

    val again = assertNotNull(state.toast(title), "Another page shows, so the toast is back")
    assertFalse(again.notify, "The request raised its notification already")
  }

  @Test
  fun approvalWaiting_sessionComesOnScreen_withdrawsTheToast() = runTest {
    val state = appState(asksToOpen("download.blender.org"))
    state.say("blender")
    settle()
    val title = "Discover needs your OK to open download.blender.org"
    assertNotNull(state.toast(title))

    // The user opens Discover on the session without Review, so its card shows.
    state.showDiscoverPage()
    settle()

    assertNull(state.toast(title))
    assertEquals(1, state.discoverWaitingCount)
  }

  @Test
  fun discardDiscovered_undo_showsTheResultsAgain() = runTest {
    val state = appState(FakeAiProvider(candidates = listOf(blender)))
    state.say("blender")
    settle()

    state.discardDiscovered(listOf(blender.url))
    val session = assertNotNull(state.aiDiscover.current)
    assertTrue(session.visible(session.turns.single()).isEmpty())
    assertNotNull(state.toast("Discarded 1 result"))
    assertEquals("Undo discard results", state.pendingOps.ops.value.single().undoTitle.load())
    assertTrue(state.pendingOps.undoLast())
    runCurrent()

    val restored = assertNotNull(state.aiDiscover.current)
    assertEquals(listOf(blender), restored.visible(restored.turns.single()))
  }

  @Test
  fun deleteDiscoverSession_undo_putsItBackWithoutShowingIt() = runTest {
    val state = appState(FakeAiProvider())
    state.say("blender")
    settle()
    val id = assertNotNull(state.aiDiscover.currentId)

    state.deleteDiscoverSession(id)
    assertTrue(state.aiDiscover.sessions.isEmpty())
    assertNotNull(state.toast("Deleted “blender”")).click("Undo")
    runCurrent()

    assertEquals(listOf(id), state.aiDiscover.sessions.map { it.id })
    assertNull(state.aiDiscover.currentId)
  }

  @Test
  fun clearDiscoverHistory_undo_putsEverySessionBack() = runTest {
    val state = appState(FakeAiProvider())
    state.say("blender")
    state.aiDiscover.newSession()
    state.say("ubuntu")
    settle()

    state.clearDiscoverHistory()
    assertTrue(state.aiDiscover.sessions.isEmpty())
    assertNotNull(state.toast("Cleared 2 searches from the history")).click("Undo")
    runCurrent()

    assertEquals(setOf("blender", "ubuntu"), state.aiDiscover.sessions.map { it.title }.toSet())
    assertFalse(state.pendingOps.undoLast())
  }
}
