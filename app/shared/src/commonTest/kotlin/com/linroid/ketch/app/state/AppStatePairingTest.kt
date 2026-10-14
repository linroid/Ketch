package com.linroid.ketch.app.state

import com.linroid.ketch.app.FakeKetchApi
import com.linroid.ketch.app.RecordingConfigStore
import com.linroid.ketch.app.feedback.AppMessage
import com.linroid.ketch.app.feedback.ToastMode
import com.linroid.ketch.app.i18n.load
import com.linroid.ketch.app.i18n.warmStrings
import com.linroid.ketch.app.instance.InstanceFactory
import com.linroid.ketch.app.instance.InstanceManager
import com.linroid.ketch.app.settleSnapshots
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AppStatePairingTest {
  @BeforeTest
  fun loadStrings() = runTest { warmStrings() }

  private fun TestScope.appState(): AppState {
    val store = RecordingConfigStore()
    val manager = InstanceManager(
      factory = InstanceFactory(deviceName = "This Mac", embeddedFactory = { FakeKetchApi() }),
      configStore = store,
    )
    return AppState(
      instanceManager = manager,
      scope = backgroundScope,
      appSettings = AppSettingsController(store),
    )
  }

  private fun TestScope.ask(state: AppState, name: String) {
    backgroundScope.launch {
      state.instanceManager.pairingRequests.ask(name, "4821", null, "192.168.1.30")
    }
  }

  private suspend fun AppState.pairingToast(name: String): AppMessage? =
    messages.active.value.firstOrNull { name in it.title.load() }

  @Test
  fun requestWhileSharingIsNotShown_postsAToastThatOpensSharing() = runTest {
    val state = appState()
    ask(state, "Pixel 9")
    settleSnapshots()

    val toast = assertNotNull(state.pairingToast("Pixel 9"))
    assertEquals(ToastMode.Sticky, toast.toast)
    // The hosts notify about pairing requests themselves.
    assertFalse(toast.notify)
    toast.actions.single { it.label.load() == "Review" }.onClick()

    assertEquals(
      SettingsTarget(SettingsTarget.Page.Sharing, LOCAL_DEVICE_ID),
      state.settingsRequest,
    )
  }

  @Test
  fun requestWhileSharingIsShown_postsNoToast() = runTest {
    val state = appState()
    state.pairingRequestsShown = true
    ask(state, "Pixel 9")
    settleSnapshots()

    assertTrue(state.messages.active.value.isEmpty())
  }

  @Test
  fun sharingComesOnScreen_withdrawsTheToast() = runTest {
    val state = appState()
    ask(state, "Pixel 9")
    settleSnapshots()
    assertNotNull(state.pairingToast("Pixel 9"))

    state.pairingRequestsShown = true
    settleSnapshots()

    assertNull(state.pairingToast("Pixel 9"))
  }

  @Test
  fun answeredRequest_withdrawsTheToast() = runTest {
    val state = appState()
    ask(state, "Pixel 9")
    settleSnapshots()
    val requests = state.instanceManager.pairingRequests

    requests.answer(requests.pending.value.single().id, allow = false)
    settleSnapshots()

    assertNull(state.pairingToast("Pixel 9"))
  }
}
