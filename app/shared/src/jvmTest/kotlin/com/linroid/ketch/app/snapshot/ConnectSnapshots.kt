package com.linroid.ketch.app.snapshot

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import com.linroid.ketch.app.App
import com.linroid.ketch.app.RecordingConfigStore
import com.linroid.ketch.app.instance.DiscoveredServer
import com.linroid.ketch.app.instance.InstanceFactory
import com.linroid.ketch.app.instance.InstanceManager
import com.linroid.ketch.app.instance.MdnsDiscoverer
import com.linroid.ketch.app.instance.PairingAsk
import com.linroid.ketch.app.instance.RemoteInstance
import com.linroid.ketch.app.state.AppController
import com.linroid.ketch.app.state.LinkSource
import com.linroid.ketch.app.ui.connect.AddDeviceDialog
import com.linroid.ketch.app.ui.connect.ConnectForm
import com.linroid.ketch.app.ui.connect.ConnectLandingContent
import com.linroid.ketch.app.ui.connect.DeviceConnector
import com.linroid.ketch.app.ui.connect.NearbySearch
import com.linroid.ketch.app.ui.connect.PairingApprovalDialog
import com.linroid.ketch.app.ui.connect.PairingClient
import com.linroid.ketch.app.ui.connect.PairingDialog
import com.linroid.ketch.app.ui.connect.ProbeResult
import com.linroid.ketch.app.ui.connect.codeForm
import com.linroid.ketch.app.util.PairingLink
import com.linroid.ketch.config.RemoteConfig
import com.linroid.ketch.remote.ConnectionState
import com.linroid.ketch.remote.PairingResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.time.Duration

/**
 * Connecting another device: the web app's connect page, the Add device sheet and the pairing
 * confirmation; see [SnapshotHarness] for how to run it.
 */
class ConnectSnapshots {
  @BeforeTest
  fun enabled() = requireSnapshots()

  @Test
  fun landing_noDevice_showsTheConnectPage() {
    for (size in ConnectSizes) {
      for (theme in SnapshotTheme.entries) webSnapshot("connect-landing", size, theme)
    }
  }

  @Test
  fun landing_savedDevicesNoneShown_listsThemWithTheirState() {
    for (size in listOf(SnapshotSize.Desktop, SnapshotSize.Phone)) {
      for (theme in SnapshotTheme.entries) {
        webSnapshot("connect-landing-devices", size, theme, LandingDevices)
      }
    }
  }

  @Test
  fun landing_deviceAsksForCode_revealsTheCodeField() {
    val form = {
      formAfter("nas.local:8642", ConnectionState.Unauthorized).apply { toggleManual() }
    }
    for ((size, theme) in listOf(
      SnapshotSize.Desktop to SnapshotTheme.Light,
      SnapshotSize.Desktop to SnapshotTheme.Dark,
      SnapshotSize.Phone to SnapshotTheme.Light,
    )) {
      snapshot("connect-landing-code", size, theme) {
        ConnectLandingContent(remember { form() }, emptyList(), onSubmit = {}, onPick = {})
      }
    }
  }

  @Test
  fun landing_nothingAnswers_offersToAddItAnyway() {
    snapshot("connect-landing-unreachable", SnapshotSize.Medium, SnapshotTheme.Light) {
      val form = remember { formAfter("192.168.1.99", ConnectionState.Disconnected()) }
      ConnectLandingContent(form, emptyList(), onSubmit = {}, onPick = {})
    }
  }

  @Test
  fun landing_savedDeviceNeedsCode_asksForItsCurrentCode() {
    val device = sampleRemote(
      RemoteConfig("studio.local", name = "Studio-Mac"),
      ConnectionState.Unauthorized,
    )
    snapshot("connect-landing-renew", SnapshotSize.Medium, SnapshotTheme.Dark) {
      val form = remember { ConnectForm().apply { askForCode(device) } }
      ConnectLandingContent(form, emptyList(), onSubmit = {}, onPick = {})
    }
  }

  @Test
  fun addDevice_devicesOnTheNetwork_listsThem() {
    for (size in ConnectSizes) {
      for (theme in SnapshotTheme.entries) {
        snapshot("add-device", size, theme) { AddDeviceSample(remember { ConnectForm() }) }
      }
    }
  }

  @Test
  fun addDevice_pairingLinkPastedWhileSearching_confirmsTheLink() {
    snapshot("add-device-link", SnapshotSize.Desktop, SnapshotTheme.Light) {
      AddDeviceSample(remember { ConnectForm(PAIRING_LINK) }, searching = true)
    }
  }

  @Test
  fun addDevice_deviceAsksForCode_revealsTheCodeField() {
    for ((size, theme) in listOf(
      SnapshotSize.Desktop to SnapshotTheme.Light,
      SnapshotSize.Desktop to SnapshotTheme.Dark,
      SnapshotSize.Phone to SnapshotTheme.Light,
    )) {
      snapshot("add-device-code", size, theme) {
        val form = remember {
          formAfter("192.168.1.7:8642", ConnectionState.Unauthorized).apply { toggleManual() }
        }
        AddDeviceSample(form)
      }
    }
  }

  @Test
  fun addDevice_ownerDecides_showsTheCodeTheyCheck() {
    for ((size, theme) in listOf(
      SnapshotSize.Desktop to SnapshotTheme.Light,
      SnapshotSize.Phone to SnapshotTheme.Dark,
    )) {
      snapshot("add-device-approval", size, theme) {
        val scope = rememberCoroutineScope()
        val form = remember {
          val connector = pairingConnector { awaitCancellation() }
          ConnectForm().apply { pair(PairableDevice, scope, connector, "4821") {} }
        }
        AddDeviceSample(form)
      }
    }
  }

  @Test
  fun addDevice_ownerSaysNo_offersToAskAgain() {
    for (theme in SnapshotTheme.entries) {
      snapshot("add-device-approval-denied", SnapshotSize.Desktop, theme) {
        AddDeviceSample(remember { formAfterPairing(PairingResult.Denied) })
      }
    }
  }

  @Test
  fun pairingRequest_asksTheOwnerWithTheCode() {
    val ask = PairingAsk(1, "Pixel 9", "4821", "Android 16", "192.168.1.30")
    for ((size, theme) in listOf(
      SnapshotSize.Desktop to SnapshotTheme.Light,
      SnapshotSize.Desktop to SnapshotTheme.Dark,
      SnapshotSize.Phone to SnapshotTheme.Light,
    )) {
      snapshot("pairing-request", size, theme) { PairingApprovalDialog(ask, onAnswer = {}) }
    }
  }

  @Test
  fun addDevice_nothingAnswers_offersToAddItAnyway() {
    for (theme in SnapshotTheme.entries) {
      snapshot("add-device-unreachable", SnapshotSize.Desktop, theme) {
        AddDeviceSample(remember { formAfter("192.168.1.99", ConnectionState.Disconnected()) })
      }
    }
  }

  @Test
  fun addDevice_codeRejected_asksForANewOne() {
    val device = sampleRemote(RemoteConfig("nas.local", name = "NAS-Basement"))
    for ((size, theme) in listOf(
      SnapshotSize.Desktop to SnapshotTheme.Light,
      SnapshotSize.Phone to SnapshotTheme.Dark,
    )) {
      snapshot("add-device-new-code", size, theme) {
        AddDeviceDialog(
          form = remember { codeForm(device) },
          device = device,
          nearby = null,
          onSubmit = {},
          onPick = {},
          onFind = {},
          onShare = {},
          onDismiss = {},
        )
      }
    }
  }

  @Test
  fun addDevice_beforeASearch_offersFindOnNetwork() {
    snapshot("add-device-find", SnapshotSize.Phone, SnapshotTheme.Light) {
      AddDeviceSample(remember { ConnectForm() }, search = false)
    }
    snapshot("add-device-none-found", SnapshotSize.Desktop, SnapshotTheme.Dark) {
      AddDeviceSample(remember { ConnectForm() }, servers = emptyList())
    }
  }

  @Test
  fun pairing_scannedCode_asksBeforeConnecting() {
    for (size in ConnectSizes) {
      for (theme in SnapshotTheme.entries) {
        appSnapshot("pairing-confirm", size, theme) {
          state.incoming.offerLink(PAIRING_LINK, LinkSource.OpenUrl)
        }
      }
    }
  }

  @Test
  fun pairing_codeRejected_asksForTheCurrentOne() {
    val link = checkNotNull(PairingLink.parse(PAIRING_LINK))
    for ((size, theme) in listOf(
      SnapshotSize.Desktop to SnapshotTheme.Light,
      SnapshotSize.Phone to SnapshotTheme.Dark,
    )) {
      snapshot("pairing-confirm-rejected", size, theme) {
        val form = remember { formAfter(PAIRING_LINK, ConnectionState.Unauthorized) }
        PairingDialog(form, link, known = null, onSubmit = {}, onDismiss = {})
      }
    }
    snapshot("pairing-confirm-unreachable", SnapshotSize.Medium, SnapshotTheme.Light) {
      val form = remember { formAfter(PAIRING_LINK, ConnectionState.Disconnected()) }
      PairingDialog(form, link, known = null, onSubmit = {}, onDismiss = {})
    }
  }
}

private const val PAIRING_LINK =
  "ketch://pair?host=192.168.1.20&port=8642&name=Lins-MacBook-Pro#token=0b5e7c1d9a2f4e86"

private val ConnectSizes = listOf(SnapshotSize.Desktop, SnapshotSize.Medium, SnapshotSize.Phone)

private val NearbyDevices = listOf(
  DiscoveredServer("NAS-Basement", "192.168.1.10", 8642, tokenRequired = false),
  DiscoveredServer("Den-PC", "192.168.1.7", 8642, tokenRequired = true),
  DiscoveredServer("Ketch", "192.168.1.31", 8642, tokenRequired = false),
)

private val LandingDevices = listOf(
  RemoteConfig("nas.local", name = "NAS-Basement") to ConnectionState.Connected,
  RemoteConfig("192.168.1.7", name = "Den-PC", os = "Windows 11") to
    ConnectionState.Disconnected(),
  RemoteConfig("studio.local", name = "Studio-Mac", os = "Mac OS X") to
    ConnectionState.Unauthorized,
  RemoteConfig("192.168.1.42", port = 9000, watch = false) to ConnectionState.Disconnected(),
)

/**
 * The Add device sheet over [form], with the devices of [servers] found on the network; with
 * [searching] the search goes on, and without [search] none has run.
 */
@Composable
private fun AddDeviceSample(
  form: ConnectForm,
  servers: List<DiscoveredServer> = NearbyDevices,
  searching: Boolean = false,
  search: Boolean = true,
) {
  val scope = rememberCoroutineScope()
  val nearby = remember {
    NearbySearch(
      discoverer = SampleNearbyDiscoverer(servers, searching),
      scope = scope,
      rounds = listOf(Duration.ZERO, Duration.ZERO),
      dispatcher = Dispatchers.Unconfined,
    ).also { if (search) it.search() }
  }
  AddDeviceDialog(
    form = form,
    device = null,
    nearby = nearby,
    onSubmit = {},
    onPick = {},
    onFind = {},
    onShare = {},
    onDismiss = {},
    added = { it.host == "192.168.1.10" },
  )
}

/** Finds [servers] in its first round, then finds them again or, while [searching], waits. */
private class SampleNearbyDiscoverer(
  private val servers: List<DiscoveredServer>,
  private val searching: Boolean,
) : MdnsDiscoverer {
  private var rounds = 0

  override suspend fun discover(serviceType: String, timeoutMs: Long): List<DiscoveredServer> {
    if (rounds++ > 0 && searching) awaitCancellation()
    return servers
  }
}

/** A device on the network whose owner can let this one in. */
private val PairableDevice = DiscoveredServer(
  "Lins-MacBook-Pro", "192.168.1.20", 8642, tokenRequired = true, pairable = true,
)

/** A [DeviceConnector] whose pairing requests [client] answers. */
private fun pairingConnector(client: suspend () -> PairingResult): DeviceConnector =
  DeviceConnector(
    InstanceManager(InstanceFactory()),
    {},
    { ProbeResult(ConnectionState.Unauthorized) },
    PairingClient { _, _ -> client() },
  )

/** A form that asked the owner of [PairableDevice] and got [answer]. */
private fun formAfterPairing(answer: PairingResult): ConnectForm {
  val form = ConnectForm()
  runBlocking {
    coroutineScope { form.pair(PairableDevice, this, pairingConnector { answer }, "4821") {} }
  }
  return form
}

/** A form that tried [link] and got [answer]. */
private fun formAfter(link: String, answer: ConnectionState): ConnectForm {
  val form = ConnectForm(link)
  val manager = InstanceManager(InstanceFactory())
  try {
    runBlocking {
      coroutineScope {
        form.connect(this, DeviceConnector(manager, {}, { ProbeResult(answer) })) {}
      }
    }
  } finally {
    manager.close()
  }
  return form
}

private fun sampleRemote(
  config: RemoteConfig,
  state: ConnectionState = ConnectionState.Connected,
): RemoteInstance =
  RemoteInstance(SampleKetchApi(SampleData.empty()), config, MutableStateFlow(state))

/**
 * Renders the app root as the web app shows it, with no device of its own and [remotes] saved
 * but none shown, to `<name>-<theme>-<width>x<height>.png`.
 */
private fun webSnapshot(
  name: String,
  size: SnapshotSize,
  theme: SnapshotTheme,
  remotes: List<Pair<RemoteConfig, ConnectionState>> = emptyList(),
): File {
  val states = remotes.associate { (config, state) -> config.host to state }
  val data = SampleData(tasks = emptyList(), remotes = remotes.map { it.first })
  val density = size.density.toMode()
  val (manager, controller) = runBlocking(SnapshotHarness.ui) {
    val manager = InstanceManager(
      factory = InstanceFactory(
        remoteFactory = { config -> sampleRemote(config, states.getValue(config.host)) },
      ),
      initialRemotes = data.remotes,
      configStore = RecordingConfigStore(data.config(theme, density)),
    )
    manager to AppController(manager, context = SnapshotHarness.ui, clock = SampleData.CLOCK)
  }
  try {
    return SnapshotHarness.capture("$name-${theme.id}-${size.id}", size) { App(controller) }
  } finally {
    runBlocking(SnapshotHarness.ui) {
      controller.close()
      manager.close()
    }
  }
}
