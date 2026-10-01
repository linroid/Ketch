package com.linroid.ketch.app.instance

import com.linroid.ketch.app.FakeInstanceFactory
import com.linroid.ketch.app.FakeRemote
import com.linroid.ketch.app.state.LOCAL_DEVICE_ID
import com.linroid.ketch.app.state.ListFixtures
import com.linroid.ketch.config.ConfigStore
import com.linroid.ketch.config.KetchConfig
import com.linroid.ketch.config.RemoteConfig
import com.linroid.ketch.config.UiPreferences
import com.linroid.ketch.remote.ConnectionState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes

@OptIn(ExperimentalCoroutinesApi::class)
class InstanceManagerKeepAliveTest {

  private class Store(var config: KetchConfig = KetchConfig()) : ConfigStore {
    override fun load(): KetchConfig = config
    override fun save(config: KetchConfig) {
      this.config = config
    }
  }

  private val nas = RemoteConfig(host = "nas.local")
  private val den = RemoteConfig(host = "den-pc")

  private fun TestScope.manager(
    fakes: FakeInstanceFactory,
    remotes: List<RemoteConfig> = listOf(nas, den),
    store: ConfigStore? = null,
    keepAlive: KeepAlivePolicy = KeepAlivePolicy(),
  ): InstanceManager {
    val manager = InstanceManager(
      factory = fakes.factory,
      initialRemotes = remotes,
      configStore = store,
      keepAlive = keepAlive,
      context = StandardTestDispatcher(testScheduler),
      clock = ListFixtures.clock(this),
    )
    runCurrent()
    return manager
  }

  private fun InstanceManager.remote(host: String): RemoteInstance =
    instances.value.filterIsInstance<RemoteInstance>().single { it.host == host }

  private fun FakeInstanceFactory.started(deviceId: String): List<FakeRemote> =
    clientsOf(deviceId).filter { it.startCount > 0 }

  private fun assertNoClosedClientListed(manager: InstanceManager) {
    val listed = manager.instances.value.filterIsInstance<RemoteInstance>()
    assertTrue(listed.none { (it.instance as FakeRemote).closed })
  }

  @Test
  fun switchTo_watchedDevicesBackAndForth_neverReconnects() = runTest {
    val fakes = FakeInstanceFactory()
    val manager = manager(fakes)

    manager.switchTo(manager.remote("nas.local"))
    manager.switchTo(manager.remote("den-pc"))
    manager.switchTo(manager.remote("nas.local"))
    runCurrent()

    for (deviceId in listOf("nas.local:8642", "den-pc:8642")) {
      val client = fakes.clientsOf(deviceId).single()
      assertEquals(1, client.startCount)
      assertFalse(client.closed)
    }
    assertSame(manager.remote("nas.local").instance, manager.activeApi.value)
    manager.close()
  }

  @Test
  fun switchTo_unwatchedDevicesBackAndForth_reconnectsOnceWithFreshClient() = runTest {
    val fakes = FakeInstanceFactory()
    val manager = manager(fakes, listOf(nas.copy(watch = false), den.copy(watch = false)))

    manager.switchTo(manager.remote("nas.local"))
    manager.switchTo(manager.remote("den-pc"))
    manager.switchTo(manager.remote("nas.local"))
    runCurrent()

    val nasClients = fakes.started("nas.local:8642")
    assertEquals(2, nasClients.size)
    assertTrue(nasClients.first().closed)
    assertTrue(fakes.remotes.none { it.startedAfterClose || it.startCount > 1 })
    assertTrue(fakes.started("den-pc:8642").single().closed)
    assertNoClosedClientListed(manager)
    manager.close()
  }

  @Test
  fun switchTo_awayFromWatchedDevice_keepsItConnected() = runTest {
    val fakes = FakeInstanceFactory()
    val manager = manager(fakes)
    manager.switchTo(manager.remote("nas.local"))

    manager.switchTo(manager.instances.value.first())
    runCurrent()

    val nas = manager.remote("nas.local")
    assertEquals(ConnectionState.Connected, nas.connectionState.value)
    assertFalse((nas.instance as FakeRemote).closed)
    manager.close()
  }

  @Test
  fun init_moreWatchedDevicesThanAllowed_connectsTheFirstOnes() = runTest {
    val fakes = FakeInstanceFactory()
    val remotes = (1..7).map { RemoteConfig(host = "host$it") }

    val manager = manager(fakes, remotes, keepAlive = KeepAlivePolicy(maxWatched = 5))

    val connected = fakes.remotes.filter { it.startCount > 0 }.map { it.config.host }
    assertEquals((1..5).map { "host$it" }, connected)
    manager.close()
  }

  @Test
  fun setInForeground_backgroundPastGrace_closesInactiveRemotes() = runTest {
    val fakes = FakeInstanceFactory()
    val manager = manager(fakes)
    manager.switchTo(manager.remote("nas.local"))
    val den = manager.remote("den-pc").instance as FakeRemote

    manager.setInForeground(false)
    advanceTimeBy(10.minutes - 1.milliseconds)
    assertFalse(den.closed)
    advanceTimeBy(2.milliseconds)

    assertTrue(den.closed)
    assertFalse((manager.remote("nas.local").instance as FakeRemote).closed)
    assertEquals(0, (manager.remote("den-pc").instance as FakeRemote).startCount)
    assertNoClosedClientListed(manager)
    manager.close()
  }

  @Test
  fun setInForeground_returnAfterGrace_reconnectsWithFreshClients() = runTest {
    val fakes = FakeInstanceFactory()
    val manager = manager(fakes)
    manager.setInForeground(false)
    advanceTimeBy(11.minutes)

    manager.setInForeground(true)
    runCurrent()

    for (deviceId in listOf("nas.local:8642", "den-pc:8642")) {
      val clients = fakes.started(deviceId)
      assertEquals(2, clients.size)
      assertSame(clients.last(), manager.instances.value.single { it.label == deviceId }.instance)
    }
    assertTrue(fakes.remotes.none { it.startedAfterClose })
    manager.close()
  }

  @Test
  fun setInForeground_returnWithinGrace_keepsConnections() = runTest {
    val fakes = FakeInstanceFactory()
    val manager = manager(fakes)

    manager.setInForeground(false)
    advanceTimeBy(9.minutes)
    manager.setInForeground(true)
    advanceTimeBy(5.minutes)

    assertEquals(2, fakes.remotes.size)
    assertTrue(fakes.remotes.none { it.closed })
    manager.close()
  }

  @Test
  fun setWatched_offForInactiveDevice_disconnectsIt() = runTest {
    val fakes = FakeInstanceFactory()
    val store = Store(KetchConfig(remotes = listOf(nas, den)))
    val manager = manager(fakes, store = store)
    val client = manager.remote("den-pc").instance as FakeRemote

    manager.setWatched(manager.remote("den-pc"), watch = false)
    runCurrent()

    assertTrue(client.closed)
    assertFalse(manager.remote("den-pc").remoteConfig.watch)
    assertFalse(store.config.remotes.single { it.host == "den-pc" }.watch)
    manager.close()
  }

  @Test
  fun reconnectWithToken_activeDevice_startsFreshClientWithToken() = runTest {
    val fakes = FakeInstanceFactory()
    val store = Store()
    val manager = manager(fakes, store = store)
    manager.switchTo(manager.remote("nas.local"))
    val old = manager.remote("nas.local")

    manager.reconnectWithToken(old, "secret")
    runCurrent()

    val current = manager.remote("nas.local")
    val client = current.instance as FakeRemote
    assertTrue((old.instance as FakeRemote).closed)
    assertEquals("secret", client.config.apiToken)
    assertEquals(1, client.startCount)
    assertSame(current, manager.activeInstance.value)
    assertSame(current.instance, manager.activeApi.value)
    assertEquals("secret", store.config.remotes.single { it.host == "nas.local" }.apiToken)
    manager.close()
  }

  @Test
  fun removeInstance_activeRemote_closesItAndShowsEmbedded() = runTest {
    val fakes = FakeInstanceFactory()
    val manager = manager(fakes)
    manager.switchTo(manager.remote("nas.local"))
    val client = manager.remote("nas.local").instance as FakeRemote

    manager.removeInstance(manager.remote("nas.local"))
    runCurrent()

    assertTrue(client.closed)
    assertIs<EmbeddedInstance>(manager.activeInstance.value)
    assertEquals(DeviceScope.Single(LOCAL_DEVICE_ID), manager.deviceScope.value)
    val remaining = manager.instances.value.filterIsInstance<RemoteInstance>()
    assertEquals(listOf("den-pc"), remaining.map { it.host })
    manager.close()
  }

  @Test
  fun connect_unnamedDevice_adoptsTheNameItAnnounces() = runTest {
    val fakes = FakeInstanceFactory().apply { announcedName = "NAS-Basement" }
    val store = Store()
    val manager = manager(fakes, listOf(nas), store)

    assertEquals("NAS-Basement", manager.remote("nas.local").label)
    assertEquals("NAS-Basement", store.config.remotes.single().name)
    manager.close()
  }

  @Test
  fun connect_deviceAnnouncingGenericName_keepsItsAddress() = runTest {
    val fakes = FakeInstanceFactory().apply { announcedName = "Ketch" }
    val manager = manager(fakes, listOf(nas))

    assertEquals("nas.local:8642", manager.remote("nas.local").label)
    assertNull(manager.remote("nas.local").remoteConfig.name)
    manager.close()
  }

  @Test
  fun rename_namedDevice_keepsItsClient() = runTest {
    val fakes = FakeInstanceFactory()
    val manager = manager(fakes, listOf(nas.copy(name = "NAS")))
    manager.switchTo(manager.remote("nas.local"))
    val client = manager.remote("nas.local").instance

    manager.rename(manager.remote("nas.local"), "  Basement  ")
    runCurrent()

    assertEquals("Basement", manager.remote("nas.local").label)
    assertEquals("Basement", manager.activeInstance.value?.label)
    assertSame(client, manager.activeApi.value)
    assertEquals(1, fakes.remotes.size)
    manager.close()
  }

  @Test
  fun addRemote_discoveredName_namesTheDevice() = runTest {
    val fakes = FakeInstanceFactory()
    val manager = manager(fakes, emptyList())

    val added = manager.addRemote("10.0.0.5", name = "Den-PC")
    val again = manager.addRemote("10.0.0.5")

    assertEquals("Den-PC", added.label)
    assertSame(added, again)
    assertEquals(2, manager.instances.value.size)
    manager.close()
  }

  @Test
  fun init_lastDeviceSaved_showsItAgain() = runTest {
    val fakes = FakeInstanceFactory()
    val store = Store(KetchConfig(ui = UiPreferences(lastDeviceId = "den-pc:8642")))

    val manager = manager(fakes, store = store)

    assertEquals("den-pc", (manager.activeInstance.value as RemoteInstance).host)
    assertEquals(DeviceScope.Single("den-pc:8642"), manager.deviceScope.value)
    manager.close()
  }

  @Test
  fun showAllDevices_fewerThanTwoDevices_keepsOneDevice() = runTest {
    val alone = manager(FakeInstanceFactory(), emptyList())
    val fleet = manager(FakeInstanceFactory())

    assertFalse(alone.showAllDevices())
    assertTrue(fleet.showAllDevices())
    assertEquals(DeviceScope.Single(LOCAL_DEVICE_ID), alone.deviceScope.value)
    assertEquals(DeviceScope.All, fleet.deviceScope.value)
    fleet.switchTo(fleet.remote("nas.local"))
    assertEquals(DeviceScope.Single("nas.local:8642"), fleet.deviceScope.value)
    alone.close()
    fleet.close()
  }
}
