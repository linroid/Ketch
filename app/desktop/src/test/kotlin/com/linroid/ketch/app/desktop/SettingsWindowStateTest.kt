package com.linroid.ketch.app.desktop

import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import com.linroid.ketch.app.state.SettingsTarget
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import java.awt.Rectangle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SettingsWindowStateTest {
  private val request = mutableStateOf<SettingsTarget?>(null)
  private val settings = SettingsWindowState(
    takeRequest = { request.value?.also { request.value = null } },
  )
  private val speed = SettingsTarget(SettingsTarget.Page.Speed)

  @Test
  fun claimRequests_requestMade_opensTheWindowAndTakesIt() {
    val claim = settings.claimRequests()
    try {
      request.value = speed
      Snapshot.sendApplyNotifications()

      assertEquals(speed, settings.target)
      assertTrue(settings.isOpen)
      assertNull(request.value)
    } finally {
      claim.dispose()
    }
  }

  @Test
  fun claimRequests_requestAlreadyPending_opensAtOnce() {
    request.value = speed
    Snapshot.sendApplyNotifications()

    val claim = settings.claimRequests()
    claim.dispose()

    assertEquals(speed, settings.target)
    assertNull(request.value)
  }

  @Test
  fun claimRequests_afterDispose_leavesRequestsAlone() {
    settings.claimRequests().dispose()

    request.value = speed
    Snapshot.sendApplyNotifications()

    assertFalse(settings.isOpen)
    assertEquals(speed, request.value)
    request.value = null
  }

  @Test
  fun open_whileOpen_movesToThePageAndAsksToComeForward() = runTest {
    settings.open(SettingsTarget(SettingsTarget.Page.General))
    val front = async(start = CoroutineStart.UNDISPATCHED) { settings.frontRequests.first() }

    settings.open(speed)

    front.await()
    assertEquals(speed, settings.target)
  }

  @Test
  fun show_whileOpen_staysOnThePage() {
    settings.open(speed)

    settings.show()

    assertEquals(speed, settings.target)
  }

  @Test
  fun show_whileClosed_opensAtGeneral() {
    settings.show()

    assertEquals(SettingsTarget(SettingsTarget.Page.General), settings.target)
  }

  @Test
  fun close_focusedWindow_closesAndDropsFocus() {
    settings.open(speed)
    settings.focused = true

    settings.close()

    assertFalse(settings.isOpen)
    assertFalse(settings.focused)
  }

  @Test
  fun initialWindowState_settingsSizes_opensAtTheSettingsSizeAndKeepsItsMinimum() {
    val screens = listOf(Rectangle(0, 25, 1440, 875))

    val fresh = initialWindowState(null, screens, SettingsWindowSize, MinSettingsWindowSize)
    val small = WindowBounds(x = 100, y = 100, width = 500, height = 300, maximized = false)
    val restored = initialWindowState(small, screens, SettingsWindowSize, MinSettingsWindowSize)

    assertEquals(DpSize(720.dp, 580.dp), fresh.size)
    assertEquals(DpSize(640.dp, 480.dp), restored.size)
  }
}
