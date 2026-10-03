package com.linroid.ketch.app.ui.shell

import com.linroid.ketch.app.RecordingConfigStore
import com.linroid.ketch.app.i18n.load
import com.linroid.ketch.app.instance.InstanceEntry
import com.linroid.ketch.app.instance.InstanceFactory
import com.linroid.ketch.app.instance.InstanceManager
import com.linroid.ketch.app.instance.RemoteInstance
import com.linroid.ketch.app.state.AppController
import com.linroid.ketch.app.state.AppState
import com.linroid.ketch.app.state.DeviceHealth
import com.linroid.ketch.app.state.LOCAL_DEVICE_ID
import com.linroid.ketch.app.state.ListFixtures
import com.linroid.ketch.app.state.RecordingKetchApi
import com.linroid.ketch.app.state.TaskKey
import com.linroid.ketch.app.state.deviceId
import com.linroid.ketch.app.ui.shell.FleetFixtures.NAS_ID
import com.linroid.ketch.app.ui.shell.FleetFixtures.mac
import com.linroid.ketch.app.ui.shell.FleetFixtures.nas
import com.linroid.ketch.config.KetchConfig
import com.linroid.ketch.config.RemoteConfig
import com.linroid.ketch.config.UiPreferences
import com.linroid.ketch.remote.ConnectionState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class DeviceDropsTest {
  private val rows = DeviceDrag.Rows(listOf("a", "b").map { TaskKey(LOCAL_DEVICE_ID, it) })

  /** This Mac and a connected NAS, with quick add on. */
  private class Fleet(scope: TestScope) {
    val mac = RecordingKetchApi("This Mac")
    val nas = RecordingKetchApi("NAS")
    val manager = InstanceManager(
      factory = InstanceFactory(
        deviceName = "This Mac",
        embeddedFactory = { mac },
        remoteFactory = { config ->
          RemoteInstance(nas, config, MutableStateFlow(ConnectionState.Connected))
        },
      ),
      initialRemotes = listOf(RemoteConfig(host = "nas.local", name = "NAS")),
      configStore = RecordingConfigStore(KetchConfig(ui = UiPreferences(quickAdd = true))),
      context = scope.backgroundScope.coroutineContext,
    )
    val controller = AppController(
      instanceManager = manager,
      context = scope.backgroundScope.coroutineContext +
        SupervisorJob(scope.backgroundScope.coroutineContext[Job]),
      clock = ListFixtures.clock(scope),
    )
    val state: AppState get() = controller.state
    val remote: InstanceEntry get() = manager.instances.value.last()
  }

  private fun TestScope.fleet(): Fleet = Fleet(this).also {
    runCurrent()
    advanceTimeBy(1.seconds)
  }

  @Test
  fun dropHint_content_offersTheFreeSpace() = runTest {
    val hint = dropHint(DeviceDrag.Content, nas(), move = false)

    assertEquals("Drop here · 1.8 TB free" to true, hint.loaded())
  }

  @Test
  fun dropHint_rowsFromAnotherDevice_sendsOrMovesThem() = runTest {
    val send = dropHint(rows, nas(), move = false, moveKey = "Alt")

    assertEquals("Send 2 here · Alt moves" to true, send.loaded())
    assertEquals("Move 2 here" to true, dropHint(rows, nas(), move = true).loaded())
  }

  @Test
  fun dropHint_rowsWithoutAMoveKey_leavesTheKeyOut() = runTest {
    assertEquals("Send 2 here" to true, dropHint(rows, nas(), move = false).loaded())
  }

  @Test
  fun dropHint_rowsOnTheirOwnDevice_turnsTheDropDown() {
    assertFalse(dropHint(rows, mac(), move = false).accepts)
  }

  @Test
  fun dropHint_unreachableDevice_turnsTheDropDown() = runTest {
    val offline = nas(health = DeviceHealth.Offline())

    assertEquals(
      "Not reachable now" to false,
      dropHint(DeviceDrag.Content, offline, move = false).loaded(),
    )
    assertFalse(dropHint(rows, nas(connected = false), move = false).accepts)
  }

  @Test
  fun dropTextOn_oneLinkOnTheNas_addsItThereWithoutSwitching() = runTest {
    val fleet = fleet()

    fleet.state.dropTextOn(fleet.remote, "https://example.com/ubuntu.iso")
    runCurrent()

    assertEquals("https://example.com/ubuntu.iso", fleet.nas.requests.single().url)
    assertTrue(fleet.mac.requests.isEmpty())
    assertEquals(LOCAL_DEVICE_ID, fleet.state.activeInstance.value?.deviceId)
    assertFalse(fleet.state.showAddDialog)
  }

  @Test
  fun dropTextOn_magnet_opensTheSheetAimedAtTheNas() = runTest {
    val fleet = fleet()
    val magnet = "magnet:?xt=urn:btih:0123456789abcdef0123456789abcdef01234567&dn=ubuntu"

    fleet.state.dropTextOn(fleet.remote, magnet)
    runCurrent()

    assertTrue(fleet.state.showAddDialog)
    assertEquals(NAS_ID, fleet.state.intakeRequest?.targetDeviceId)
    assertTrue(fleet.nas.requests.isEmpty())
  }

  private suspend fun DropHint.loaded(): Pair<String, Boolean> = text.load() to accepts
}
