package com.linroid.ketch.app.ui.connect

import com.linroid.ketch.app.FakeInstanceFactory
import com.linroid.ketch.app.i18n.load
import com.linroid.ketch.app.instance.DiscoveredServer
import com.linroid.ketch.app.instance.InstanceManager
import com.linroid.ketch.app.instance.RemoteInstance
import com.linroid.ketch.app.util.PairingLink
import com.linroid.ketch.config.RemoteConfig
import com.linroid.ketch.remote.ConnectionState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okio.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ConnectFormTest {

  private val pairingLink =
    "ketch://pair?host=192.168.1.20&port=8642&name=Lins-MacBook-Pro#token=secret"

  private fun TestScope.connector(answer: ConnectionState): DeviceConnector {
    val manager = InstanceManager(
      factory = FakeInstanceFactory().factory,
      context = backgroundScope.coroutineContext,
    )
    return DeviceConnector(manager, {}, { ProbeResult(answer) })
  }

  @Test
  fun target_pairingLink_carriesAddressCodeAndName() = runTest {
    val form = ConnectForm(pairingLink)

    assertEquals(
      PairingLink("192.168.1.20", 8642, token = "secret", name = "Lins-MacBook-Pro"),
      form.target,
    )
    assertEquals(
      "Lins-MacBook-Pro · 192.168.1.20:8642 · includes its access code",
      form.linkSummary.load(),
    )
  }

  @Test
  fun isComplete_onlyForAPairingLinkWithItsCode() {
    assertTrue(ConnectForm(pairingLink).isComplete)
    assertFalse(ConnectForm(pairingLink.substringBefore('#')).isComplete)
    assertFalse(ConnectForm("nas.local:8642").isComplete)
  }

  @Test
  fun target_codeTyped_winsOverTheCodeOfTheLink() {
    val form = ConnectForm(pairingLink)

    form.code = " fresh "

    assertEquals("fresh", form.target?.token)
  }

  @Test
  fun linkSummary_plainAddress_isNull() {
    assertNull(ConnectForm("nas.local:8642").linkSummary)
  }

  @Test
  fun toggleManual_withLink_fillsTheDetailsFromIt() {
    val form = ConnectForm("https://nas.local:9443")

    form.toggleManual()

    assertTrue(form.manual)
    assertEquals("nas.local", form.host)
    assertEquals("9443", form.port)
    assertTrue(form.secure)
  }

  @Test
  fun target_manualHostTyped_usesTheTypedDetails() {
    val form = ConnectForm("nas.local")
    form.toggleManual()

    form.host = "10.0.0.4"
    form.port = "9000"

    assertEquals(PairingLink("10.0.0.4", 9000), form.target)
    assertNull(form.linkSummary)
  }

  @Test
  fun port_typedWithLetters_keepsTheDigits() = runTest {
    val form = ConnectForm()
    form.toggleManual()

    form.port = "86a42999"

    assertEquals("86429", form.port)
    assertEquals("Use a port from 1 to 65535", form.portError.load())
  }

  @Test
  fun linkError_beforeAnAttempt_isNull() {
    val form = ConnectForm("not a link")

    assertNull(form.target)
    assertNull(form.linkError)
  }

  @Test
  fun connect_nothingToTry_pointsOutTheField() = runTest {
    val form = ConnectForm("not a link")

    form.connect(this, connector(ConnectionState.Connected)) {}

    assertFalse(form.connecting)
    assertEquals(
      "Use a pairing link or an address such as nas.local:8642",
      form.linkError.load(),
    )
    form.link = "nas.local"
    assertNull(form.linkError)
  }

  @Test
  fun connect_deviceLetsKetchIn_runsOnConnected() = runTest {
    val form = ConnectForm(pairingLink)
    var connected: RemoteInstance? = null

    form.connect(this, connector(ConnectionState.Connected)) { connected = it }
    runCurrent()

    assertEquals("192.168.1.20", assertNotNull(connected).host)
    assertFalse(form.connecting)
    assertNull(form.problem)
  }

  @Test
  fun connect_deviceAsksForCode_showsTheCodeField() = runTest {
    val form = ConnectForm("nas.local")

    form.connect(this, connector(ConnectionState.Unauthorized)) {}
    runCurrent()

    assertTrue(form.codeShown)
    assertEquals(ConnectProblem.NeedsCode("nas.local:8642", rejected = false), form.problem)
  }

  @Test
  fun code_typedAfterARejectedCode_keepsTheRequestWithoutTheRejection() = runTest {
    val form = ConnectForm(pairingLink)
    form.connect(this, connector(ConnectionState.Unauthorized)) {}
    runCurrent()
    assertEquals(ConnectProblem.NeedsCode("192.168.1.20:8642", rejected = true), form.problem)

    form.code = "c"

    assertEquals(ConnectProblem.NeedsCode("192.168.1.20:8642", rejected = false), form.problem)
    form.link = "nas.local"
    assertNull(form.problem)
    assertTrue(form.codeShown)
  }

  @Test
  fun connect_nothingAnswers_keepsTheFormWithTheProblem() = runTest {
    val form = ConnectForm("nas.local")
    var connected = false

    form.connect(this, connector(ConnectionState.Disconnected())) { connected = true }
    runCurrent()

    assertFalse(connected)
    assertEquals(ConnectProblem.Unreachable("nas.local:8642"), form.problem)
  }

  @Test
  fun pick_serverThatAsksForCode_showsTheCodeFieldAndKeepsItsName() {
    val form = ConnectForm()

    form.pick(DiscoveredServer("Den-PC", "192.168.1.7", 8642, tokenRequired = true))

    assertEquals("192.168.1.7:8642", form.link)
    assertTrue(form.codeShown)
    assertEquals("Den-PC", form.target?.name)
    assertEquals(ConnectProblem.NeedsCode("192.168.1.7:8642", rejected = false), form.problem)
  }

  @Test
  fun pick_genericName_leavesTheDeviceUnnamed() {
    val form = ConnectForm()

    form.pick(DiscoveredServer("Ketch", "192.168.1.7", 8642, tokenRequired = false))

    assertNull(form.target?.name)
    assertFalse(form.codeShown)
  }

  @Test
  fun isEdited_onlyAfterTyping() {
    val form = ConnectForm()
    assertFalse(form.isEdited)

    form.link = "nas"

    assertTrue(form.isEdited)
  }

  @Test
  fun isEdited_detailsFilledFromTheLink_staysFalseUntilTyped() {
    val form = ConnectForm("nas.local:9000")

    form.toggleManual()

    assertFalse(form.isEdited)
    form.port = "9001"
    assertTrue(form.isEdited)
  }

  @Test
  fun pick_anotherDevice_dropsTheCodeTypedForTheLast() {
    val form = ConnectForm()
    form.pick(DiscoveredServer("Den-PC", "192.168.1.7", 8642, tokenRequired = true))
    form.code = "den-code"

    form.pick(DiscoveredServer("NAS", "192.168.1.10", 8642, tokenRequired = true))

    assertEquals("", form.code)
    assertNull(form.target?.token)
  }

  @Test
  fun askForCode_deviceAddedBefore_showsTheCodeFieldForItsAddress() {
    val device = FakeInstanceFactory().factory.createRemote(
      RemoteConfig(host = "studio.local", apiToken = "old", name = "Studio-Mac"),
    )
    val form = ConnectForm("typed")

    form.askForCode(device)

    assertTrue(form.codeShown)
    assertEquals(PairingLink("studio.local", name = "Studio-Mac"), form.target)
    assertEquals(ConnectProblem.NeedsCode("studio.local:8642", rejected = false), form.problem)
  }

  @Test
  fun connect_addingFails_explainsWhyInPlainWords() = runTest {
    val manager = InstanceManager(
      factory = FakeInstanceFactory().factory,
      context = backgroundScope.coroutineContext,
    )
    val form = ConnectForm("nas.local")

    form.connect(this, DeviceConnector(manager, {}, { throw IOException("reset") })) {}
    runCurrent()

    assertEquals(
      "Connection lost. Check the connection and try again.",
      (form.problem as ConnectProblem.Failed).message.load(),
    )
    assertFalse(form.connecting)
  }

  @Test
  fun codeForm_httpsDevice_asksForACodeAndKeepsTheScheme() {
    val device = FakeInstanceFactory().factory.createRemote(
      RemoteConfig(host = "nas.local", port = 9443, secure = true),
    )

    val form = codeForm(device)

    assertTrue(form.codeShown)
    assertFalse(form.isEdited)
    assertEquals(PairingLink("nas.local", 9443, secure = true), form.target)
  }
}
