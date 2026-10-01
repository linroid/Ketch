package com.linroid.ketch.app.ui.connect

import com.linroid.ketch.app.FakeInstanceFactory
import com.linroid.ketch.app.instance.InstanceEntry
import com.linroid.ketch.app.instance.InstanceManager
import com.linroid.ketch.app.instance.RemoteInstance
import com.linroid.ketch.app.util.PairingLink
import com.linroid.ketch.config.RemoteConfig
import com.linroid.ketch.remote.ConnectionState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class DeviceConnectorTest {

  private class Probe(
    var result: ProbeResult = ProbeResult(ConnectionState.Connected),
  ) : ConnectionProbe {
    val checked = mutableListOf<RemoteConfig>()

    override suspend fun check(config: RemoteConfig): ProbeResult {
      checked += config
      return result
    }
  }

  private val shown = mutableListOf<InstanceEntry>()

  private fun TestScope.manager(
    fakes: FakeInstanceFactory = FakeInstanceFactory(),
    remotes: List<RemoteConfig> = emptyList(),
  ): InstanceManager {
    val manager = InstanceManager(
      factory = fakes.factory,
      initialRemotes = remotes,
      context = backgroundScope.coroutineContext,
    )
    runCurrent()
    return manager
  }

  private fun connector(manager: InstanceManager, probe: Probe) =
    DeviceConnector(manager, { shown += it }, probe)

  private fun InstanceManager.remotes(): List<RemoteInstance> =
    instances.value.filterIsInstance<RemoteInstance>()

  private val link = PairingLink(
    host = "192.168.1.20",
    port = 8642,
    token = "secret",
    name = "Lins-MacBook-Pro",
  )

  @Test
  fun connect_deviceLetsKetchIn_addsItWithItsCodeAndShowsIt() = runTest {
    val manager = manager()
    val probe = Probe()

    val outcome = connector(manager, probe).connect(link)

    val device = manager.remotes().single()
    assertEquals(ConnectOutcome.Connected(device), outcome)
    assertEquals("secret", device.remoteConfig.apiToken)
    assertEquals("Lins-MacBook-Pro", device.remoteConfig.name)
    assertEquals(listOf<InstanceEntry>(device), shown)
    assertEquals("secret", probe.checked.single().apiToken)
    manager.close()
  }

  @Test
  fun connect_deviceAsksForCode_addsNothing() = runTest {
    val manager = manager()
    val probe = Probe(ProbeResult(ConnectionState.Unauthorized))

    val withoutCode = connector(manager, probe).connect(link.copy(token = null))
    val withCode = connector(manager, probe).connect(link)

    assertEquals(ConnectOutcome.NeedsCode(rejected = false), withoutCode)
    assertEquals(ConnectOutcome.NeedsCode(rejected = true), withCode)
    assertTrue(manager.remotes().isEmpty())
    assertTrue(shown.isEmpty())
    manager.close()
  }

  @Test
  fun connect_nothingAnswers_addsNothing() = runTest {
    val manager = manager()
    val probe = Probe(ProbeResult(ConnectionState.Disconnected("No answer")))

    val outcome = connector(manager, probe).connect(link)

    assertEquals(ConnectOutcome.Unreachable, outcome)
    assertTrue(manager.remotes().isEmpty())
    manager.close()
  }

  @Test
  fun connect_withoutCheck_addsTheDeviceWithoutShowingIt() = runTest {
    val manager = manager()
    val probe = Probe()

    val outcome = connector(manager, probe).connect(link, check = false)

    assertIs<ConnectOutcome.Connected>(outcome)
    assertEquals(1, manager.remotes().size)
    assertTrue(probe.checked.isEmpty())
    assertTrue(shown.isEmpty())
    manager.close()
  }

  @Test
  fun connect_withoutCheckButShown_showsTheDevice() = runTest {
    val manager = manager()

    connector(manager, Probe()).connect(link, check = false, show = true)

    assertEquals(manager.remotes().single(), shown.single())
    manager.close()
  }

  @Test
  fun connect_deviceAddedWithAnotherCode_givesItTheNewCode() = runTest {
    val fakes = FakeInstanceFactory()
    val saved = RemoteConfig(host = "192.168.1.20", apiToken = "old", name = "Office")
    val manager = manager(fakes, listOf(saved))

    connector(manager, Probe()).connect(link)
    runCurrent()

    val device = manager.remotes().single()
    assertEquals("secret", device.remoteConfig.apiToken)
    assertEquals("Office", device.remoteConfig.name)
    assertEquals("secret", fakes.clientsOf("192.168.1.20:8642").last().config.apiToken)
    assertEquals(device, shown.single())
    manager.close()
  }

  @Test
  fun connect_addressOfADeviceAddedWithItsCode_triesThatCode() = runTest {
    val saved = RemoteConfig(host = "192.168.1.20", apiToken = "saved", name = "Office")
    val manager = manager(remotes = listOf(saved))
    val probe = Probe()

    val outcome = connector(manager, probe).connect(PairingLink("192.168.1.20"))

    val device = manager.remotes().single()
    assertEquals(ConnectOutcome.Connected(device), outcome)
    assertEquals("saved", probe.checked.single().apiToken)
    assertEquals("saved", device.remoteConfig.apiToken)
    assertEquals(device, shown.single())
    manager.close()
  }

  @Test
  fun connect_savedCodeTurnedDown_asksForACodeWithoutBlamingALink() = runTest {
    val saved = RemoteConfig(host = "192.168.1.20", apiToken = "stale")
    val manager = manager(remotes = listOf(saved))
    val probe = Probe(ProbeResult(ConnectionState.Unauthorized))

    val outcome = connector(manager, probe).connect(PairingLink("192.168.1.20"))

    assertEquals(ConnectOutcome.NeedsCode(rejected = false), outcome)
    manager.close()
  }

  @Test
  fun connect_unnamedDeviceAddedAlready_takesTheNameOfTheLink() = runTest {
    val saved = RemoteConfig(host = "192.168.1.20", apiToken = "secret", watch = false)
    val manager = manager(remotes = listOf(saved))

    connector(manager, Probe()).connect(link)

    assertEquals("Lins-MacBook-Pro", manager.remotes().single().remoteConfig.name)
    manager.close()
  }

  @Test
  fun connect_addressWithoutName_takesTheNameTheDeviceAnnounces() = runTest {
    val manager = manager()
    val probe = Probe(ProbeResult(ConnectionState.Connected, name = "NAS-Basement"))

    connector(manager, probe).connect(PairingLink("nas.local"))

    assertEquals("NAS-Basement", manager.remotes().single().remoteConfig.name)
    manager.close()
  }

  @Test
  fun connect_deviceAnnouncesTheGenericName_staysUnnamed() = runTest {
    val manager = manager()
    val probe = Probe(ProbeResult(ConnectionState.Connected, name = "Ketch"))

    connector(manager, probe).connect(PairingLink("nas.local"))

    assertNull(manager.remotes().single().remoteConfig.name)
    manager.close()
  }

  @Test
  fun toRemoteConfig_httpsLink_keepsSchemeAndCode() {
    val config = PairingLink("nas.local", 443, token = "t", name = "Ketch", secure = true)
      .toRemoteConfig()

    assertEquals(RemoteConfig("nas.local", 443, apiToken = "t", secure = true), config)
  }
}
