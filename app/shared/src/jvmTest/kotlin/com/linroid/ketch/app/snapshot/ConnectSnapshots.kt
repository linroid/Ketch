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
import com.linroid.ketch.app.instance.RemoteInstance
import com.linroid.ketch.app.state.AppController
import com.linroid.ketch.app.state.LinkSource
import com.linroid.ketch.app.theme.KetchDensity
import com.linroid.ketch.app.ui.connect.AddDeviceDialog
import com.linroid.ketch.app.ui.connect.ConnectForm
import com.linroid.ketch.app.ui.connect.ConnectLandingContent
import com.linroid.ketch.app.ui.connect.DeviceConnector
import com.linroid.ketch.app.ui.connect.NearbySearch
import com.linroid.ketch.app.ui.connect.PairingDialog
import com.linroid.ketch.app.ui.connect.ProbeResult
import com.linroid.ketch.app.ui.connect.codeForm
import com.linroid.ketch.app.util.PairingLink
import com.linroid.ketch.config.DensityMode
import com.linroid.ketch.config.RemoteConfig
import com.linroid.ketch.remote.ConnectionState
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
    for (size in Sizes) {
      for (theme in SnapshotTheme.entries) webSnapshot("connect-landing", size, theme)
    }
  }

  @Test
  fun landing_savedDevicesNoneShown_listsThemWithTheirState() {
    for (size in listOf(SnapshotSize.Desktop, SnapshotSize.Phone)) {
      for (theme in SnapshotTheme.entries) {
        webSnapshot("connect-landing-devices", size, theme, SavedDevices)
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
  fun addDevice_devicesOnTheNetwork_listsThem() {
    for (size in Sizes) {
      for (theme in SnapshotTheme.entries) {
        snapshot("add-device", size, theme) { AddDevice(remember { ConnectForm() }) }
      }
    }
  }

  @Test
  fun addDevice_pairingLinkPastedWhileSearching_confirmsTheLink() {
    snapshot("add-device-link", SnapshotSize.Desktop, SnapshotTheme.Light) {
      AddDevice(remember { ConnectForm(PAIRING_LINK) }, searching = true)
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
        AddDevice(form)
      }
    }
  }

  @Test
  fun addDevice_nothingAnswers_offersToAddItAnyway() {
    for (theme in SnapshotTheme.entries) {
      snapshot("add-device-unreachable", SnapshotSize.Desktop, theme) {
        AddDevice(remember { formAfter("192.168.1.99", ConnectionState.Disconnected()) })
      }
    }
  }

  @Test
  fun addDevice_codeRejected_asksForANewOne() {
    val device = remote(RemoteConfig("nas.local", name = "NAS-Basement"))
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
      AddDevice(remember { ConnectForm() }, search = false)
    }
    snapshot("add-device-none-found", SnapshotSize.Desktop, SnapshotTheme.Dark) {
      AddDevice(remember { ConnectForm() }, servers = emptyList())
    }
  }

  @Test
  fun pairing_scannedCode_asksBeforeConnecting() {
    for (size in Sizes) {
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

private val Sizes = listOf(SnapshotSize.Desktop, SnapshotSize.Medium, SnapshotSize.Phone)

private val Nearby = listOf(
  DiscoveredServer("NAS-Basement", "192.168.1.10", 8642, tokenRequired = false),
  DiscoveredServer("Den-PC", "192.168.1.7", 8642, tokenRequired = true),
  DiscoveredServer("Ketch", "192.168.1.31", 8642, tokenRequired = false),
)

private val SavedDevices = listOf(
  RemoteConfig("nas.local", name = "NAS-Basement") to ConnectionState.Connected,
  RemoteConfig("192.168.1.7", name = "Den-PC") to ConnectionState.Disconnected(),
  RemoteConfig("studio.local", name = "Studio-Mac") to ConnectionState.Unauthorized,
  RemoteConfig("192.168.1.42", port = 9000, watch = false) to ConnectionState.Disconnected(),
)

/**
 * The Add device sheet over [form], with the devices of [servers] found on the network; with
 * [searching] the search goes on, and without [search] none has run.
 */
@Composable
private fun AddDevice(
  form: ConnectForm,
  servers: List<DiscoveredServer> = Nearby,
  searching: Boolean = false,
  search: Boolean = true,
) {
  val scope = rememberCoroutineScope()
  val nearby = remember {
    NearbySearch(
      discoverer = RoundsDiscoverer(servers, searching),
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
private class RoundsDiscoverer(
  private val servers: List<DiscoveredServer>,
  private val searching: Boolean,
) : MdnsDiscoverer {
  private var rounds = 0

  override suspend fun discover(serviceType: String, timeoutMs: Long): List<DiscoveredServer> {
    if (rounds++ > 0 && searching) awaitCancellation()
    return servers
  }
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

private fun remote(config: RemoteConfig, state: ConnectionState = ConnectionState.Connected) =
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
  val density = if (size.density == KetchDensity.Compact) {
    DensityMode.Compact
  } else {
    DensityMode.Comfortable
  }
  val (manager, controller) = runBlocking(SnapshotHarness.ui) {
    val manager = InstanceManager(
      factory = InstanceFactory(
        remoteFactory = { config -> remote(config, states.getValue(config.host)) },
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
