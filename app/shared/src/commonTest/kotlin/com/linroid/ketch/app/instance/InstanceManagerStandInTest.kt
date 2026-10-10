package com.linroid.ketch.app.instance

import com.linroid.ketch.app.FakeInstanceFactory
import com.linroid.ketch.app.RecordingConfigStore
import com.linroid.ketch.app.state.ListFixtures
import com.linroid.ketch.app.state.deviceId
import com.linroid.ketch.config.KetchConfig
import com.linroid.ketch.config.RemoteConfig
import com.linroid.ketch.config.UiPreferences
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

@OptIn(ExperimentalCoroutinesApi::class)
class InstanceManagerStandInTest {
  private val server = RemoteConfig(host = "127.0.0.1", apiToken = "secret", name = "ketch server")
  private val nas = RemoteConfig(host = "nas.local", name = "NAS")

  private fun TestScope.manager(
    store: RecordingConfigStore,
    fakes: FakeInstanceFactory = FakeInstanceFactory(embeddedFactory = null),
  ): InstanceManager {
    val manager = InstanceManager(
      factory = fakes.factory,
      initialRemotes = store.config.remotes,
      configStore = store,
      context = backgroundScope.coroutineContext,
      clock = ListFixtures.clock(this),
      standIn = server,
    )
    runCurrent()
    return manager
  }

  @Test
  fun init_standIn_isListedFirstAndShown() = runTest {
    val manager = manager(RecordingConfigStore(KetchConfig(remotes = listOf(nas))))

    assertEquals(listOf("ketch server", "NAS"), manager.instances.value.map { it.label })
    assertEquals("127.0.0.1:8642", manager.activeInstance.value?.deviceId)
    assertEquals(DeviceScope.Single("127.0.0.1:8642"), manager.deviceScope.value)
    manager.close()
  }

  @Test
  fun init_anotherDeviceShownLast_showsItAgain() = runTest {
    val store = RecordingConfigStore(
      KetchConfig(remotes = listOf(nas), ui = UiPreferences(lastDeviceId = "nas.local:8642")),
    )

    val manager = manager(store)

    assertEquals("NAS", manager.activeInstance.value?.label)
    manager.close()
  }

  @Test
  fun standIn_isNeverSaved() = runTest {
    val store = RecordingConfigStore(KetchConfig(remotes = listOf(nas)))
    val manager = manager(store)

    // The stand-in's announced system and a rename both save the devices.
    manager.rename(manager.instances.value.first() as RemoteInstance, "Terminal")
    manager.addRemote(RemoteConfig("den-pc"))

    assertEquals(listOf("nas.local", "den-pc"), store.config.remotes.map { it.host })
    manager.close()
  }

  @Test
  fun standIn_atASavedDevicesAddress_isThatDeviceAndStaysSaved() = runTest {
    val own = server.copy(name = "My server", apiToken = "old")
    val store = RecordingConfigStore(KetchConfig(remotes = listOf(nas, own)))
    val manager = manager(store)

    manager.addRemote(RemoteConfig("den-pc"))

    val labels = manager.instances.value.map { it.label }
    assertEquals(listOf("NAS", "My server", "den-pc:8642"), labels)
    assertEquals("My server", manager.activeInstance.value?.label)
    assertEquals(listOf("nas.local", "127.0.0.1", "den-pc"), store.config.remotes.map { it.host })
    manager.close()
  }

  @Test
  fun removeInstance_standIn_letsADeviceAddedAtItsAddressBeSaved() = runTest {
    val store = RecordingConfigStore()
    val manager = manager(store)

    manager.removeInstance(manager.instances.value.single())
    manager.addRemote(RemoteConfig(host = "127.0.0.1", name = "Mine"))

    assertEquals(listOf("Mine"), store.config.remotes.map { it.name })
    assertEquals("127.0.0.1:8642", manager.instances.value.single().deviceId)
    manager.close()
  }

  @Test
  fun init_standInBesideAnEmbeddedDevice_isRefused() = runTest {
    assertFailsWith<IllegalArgumentException> {
      manager(RecordingConfigStore(), FakeInstanceFactory())
    }
  }
}
