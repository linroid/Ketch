package com.linroid.ketch.app.instance

import com.linroid.ketch.api.DownloadProgress
import com.linroid.ketch.api.DownloadSchedule
import com.linroid.ketch.api.DownloadState
import com.linroid.ketch.api.DownloadTask
import com.linroid.ketch.api.KetchApi
import com.linroid.ketch.api.KetchError
import com.linroid.ketch.app.FakeInstanceFactory
import com.linroid.ketch.app.FakeKetchApi
import com.linroid.ketch.app.FakeRemote
import com.linroid.ketch.app.state.DeviceHealth
import com.linroid.ketch.app.state.ListFixtures
import com.linroid.ketch.app.state.ListTestTask
import com.linroid.ketch.app.state.PulseModel
import com.linroid.ketch.app.state.SpeedMode
import com.linroid.ketch.app.state.StatusFilter
import com.linroid.ketch.config.RemoteConfig
import com.linroid.ketch.remote.ConnectionState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class DevicePresenceTest {

  private class EmbeddedApi : KetchApi by FakeKetchApi("Core") {
    override val tasks = MutableStateFlow<List<DownloadTask>>(emptyList())
  }

  private val nas = RemoteConfig(host = "nas.local", name = "NAS-Basement")

  private val everyState: List<DownloadState> = listOf(
    DownloadState.Scheduled(DownloadSchedule.AfterDelay(30.minutes)),
    DownloadState.Queued,
    ListFixtures.downloading(100, speed = 50),
    ListFixtures.downloading(300, speed = 25),
    DownloadState.Paused(DownloadProgress(100, 1000)),
    DownloadState.Completed("/downloads/file", totalBytes = 1000),
    DownloadState.Failed(KetchError.Network()),
    DownloadState.Canceled,
  )

  private fun tasks(states: List<DownloadState>): List<DownloadTask> =
    states.mapIndexed { index, state -> ListTestTask("t$index", state) }

  private fun TestScope.manager(
    fakes: FakeInstanceFactory,
    remotes: List<RemoteConfig> = listOf(nas),
  ): InstanceManager {
    val manager = InstanceManager(
      factory = fakes.factory,
      initialRemotes = remotes,
      context = backgroundScope.coroutineContext,
      clock = ListFixtures.clock(this),
    )
    manager.presence
    runCurrent()
    return manager
  }

  private fun InstanceManager.presenceOf(deviceId: String): DevicePresence =
    presence.value.single { it.deviceId == deviceId }

  // Past the task totals' throttle, so presence shows the latest task states.
  private fun TestScope.settle() {
    advanceTimeBy(PulseModel.UPDATE_INTERVAL + 1.seconds)
    runCurrent()
  }

  @Test
  fun presence_tasksInEveryState_countsMatchStatusFilter() = runTest {
    val fakes = FakeInstanceFactory()
    val manager = manager(fakes)
    val remote = fakes.remotes.single()
    remote.tasks.value = tasks(everyState)
    settle()

    val presence = manager.presenceOf("nas.local:8642")

    for (filter in StatusFilter.entries) {
      assertEquals(everyState.count(filter::matches), presence.counts.count(filter), "$filter")
    }
    assertEquals(1, presence.failures)
    assertEquals(75, presence.speed)
    manager.close()
  }

  @Test
  fun presence_devices_namesEmbeddedByNounAndRemotesByName() = runTest {
    val manager = manager(FakeInstanceFactory())

    val (embedded, remote) = manager.presence.value

    assertEquals("MacBook Pro", embedded.detail)
    assertEquals("NAS-Basement", remote.name)
    assertEquals("nas.local:8642", remote.detail)
    assertEquals(DeviceHealth.Live, remote.health)
    assertTrue(remote.connected)
    manager.close()
  }

  @Test
  fun presence_onlineDevice_readsVersionUptimeAndDisk() = runTest {
    val fakes = FakeInstanceFactory()
    val manager = manager(fakes)
    settle()

    val presence = manager.presenceOf("nas.local:8642")

    assertEquals("0.0.1", presence.version)
    assertEquals("/volume1/downloads", presence.disk?.directory)
    val readAt = presence.statusAt ?: error("No status read")
    assertEquals(1.hours + 5.seconds, presence.uptimeAt(readAt + 5.seconds))
    manager.close()
  }

  @Test
  fun presence_failureWhileAnotherDeviceShows_staysUnseenUntilShown() = runTest {
    val fakes = FakeInstanceFactory()
    val manager = manager(fakes)
    val remote = fakes.remotes.single()
    val failing = ListTestTask("t1", ListFixtures.downloading(10))
    remote.tasks.value = listOf(failing)
    settle()
    assertEquals(0, manager.presenceOf("nas.local:8642").unseenFailures)

    failing.state.value = DownloadState.Failed(KetchError.Network())
    settle()
    assertEquals(1, manager.presenceOf("nas.local:8642").unseenFailures)

    manager.switchTo(manager.instances.value.last())
    settle()
    assertEquals(0, manager.presenceOf("nas.local:8642").unseenFailures)
    assertEquals(1, manager.presenceOf("nas.local:8642").failures)
    manager.close()
  }

  @Test
  fun presence_failuresWhileAppInBackground_countAsUnseen() = runTest {
    val embedded = EmbeddedApi()
    val fakes = FakeInstanceFactory(embeddedFactory = { embedded })
    val manager = manager(fakes, emptyList())
    val failing = ListTestTask("t1", ListFixtures.downloading(10))
    embedded.tasks.value = listOf(failing)
    settle()

    manager.setInForeground(false)
    failing.state.value = DownloadState.Failed(KetchError.Network())
    settle()
    val inBackground = manager.presence.value.single().unseenFailures
    manager.setInForeground(true)
    settle()

    assertEquals(1, inBackground)
    assertEquals(0, manager.presence.value.single().unseenFailures)
    manager.close()
  }

  @Test
  fun presence_freshClientLoadsSeenFailures_keepsThemSeen() = runTest {
    val fakes = FakeInstanceFactory()
    val manager = manager(fakes)
    val failed = tasks(List(2) { DownloadState.Failed(KetchError.Network()) })
    fakes.remotes.single().tasks.value = failed
    manager.switchTo(manager.instances.value.last())
    settle()
    manager.switchTo(manager.instances.value.first())
    settle()
    assertEquals(0, manager.presenceOf("nas.local:8642").unseenFailures)

    fakes.stateOnStart = ConnectionState.Connecting
    manager.reconnect(manager.instances.value.last() as RemoteInstance)
    runCurrent()
    // Like RemoteKetch, the fresh client lists the tasks just before it reports Connected.
    val client = fakes.remotes.last()
    client.tasks.value = tasks(List(2) { DownloadState.Failed(KetchError.Network()) })
    client.connection.value = ConnectionState.Connected
    settle()

    val presence = manager.presenceOf("nas.local:8642")
    assertEquals(2, presence.failures)
    assertEquals(0, presence.unseenFailures)
    manager.close()
  }

  @Test
  fun presence_freshClientNotConnected_keepsLastKnownCounts() = runTest {
    val fakes = FakeInstanceFactory()
    val manager = manager(fakes)
    fakes.remotes.single().tasks.value = tasks(everyState)
    settle()

    fakes.stateOnStart = ConnectionState.Connecting
    manager.reconnect(manager.instances.value.last() as RemoteInstance)
    settle()

    val presence = manager.presenceOf("nas.local:8642")
    assertEquals(DeviceHealth.Connecting, presence.health)
    val listed = everyState.count(StatusFilter.All::matches)
    assertEquals(listed, presence.counts.count(StatusFilter.All))
    assertEquals(0, presence.speed)
    manager.close()
  }

  @Test
  fun presence_deviceGoesOffline_remembersWhenItWasLastSeen() = runTest {
    val fakes = FakeInstanceFactory()
    val manager = manager(fakes)
    val remote = fakes.remotes.single()
    settle()
    assertNull(manager.presenceOf("nas.local:8642").lastSeen)

    advanceTimeBy(5.minutes)
    val offlineAt = ListFixtures.clock(this).now()
    remote.connection.value = ConnectionState.Disconnected("Connection refused")
    runCurrent()
    advanceTimeBy(1.minutes)
    runCurrent()

    val presence = manager.presenceOf("nas.local:8642")
    assertEquals(offlineAt, presence.lastSeen)
    assertEquals(DeviceHealth.Offline("Connection refused"), presence.health)
    assertEquals(0, presence.speed)
    manager.close()
  }

  @Test
  fun presence_unwatchedInactiveDevice_isNotConnected() = runTest {
    val fakes = FakeInstanceFactory()
    val manager = manager(fakes, listOf(nas.copy(watch = false)))

    val presence = manager.presenceOf("nas.local:8642")

    assertFalse(presence.connected)
    assertFalse(presence.watched)
    assertEquals(0, fakes.remotes.single().startCount)
    manager.close()
  }

  @Test
  fun presence_hostSpeedMode_appliesToEmbeddedDeviceOnly() = runTest {
    val manager = manager(FakeInstanceFactory())
    val mode = MutableStateFlow<SpeedMode>(SpeedMode.Full)
    manager.setLocalSpeedMode(mode)

    mode.value = SpeedMode.SlowLane
    runCurrent()

    val (embedded, remote) = manager.presence.value
    assertEquals(SpeedMode.SlowLane, embedded.speedMode)
    assertEquals(SpeedMode.Full, remote.speedMode)
    manager.close()
  }

  @Test
  fun presence_newClientAfterReconnect_keepsHistoryOfDevice() = runTest {
    val fakes = FakeInstanceFactory()
    val manager = manager(fakes)
    fakes.remotes.single().tasks.value = listOf(ListTestTask("t1", ListFixtures.downloading(10)))
    advanceTimeBy(5.seconds)
    runCurrent()
    val before = manager.presenceOf("nas.local:8642").history.size

    manager.reconnect(manager.instances.value.last() as RemoteInstance)
    runCurrent()

    val after = manager.presenceOf("nas.local:8642")
    assertTrue(before > 0)
    assertTrue(after.history.size >= before)
    assertEquals(fakes.remotes.last(), after.api as FakeRemote)
    manager.close()
  }
}
