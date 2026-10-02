package com.linroid.ketch.app.snapshot

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.linroid.ketch.app.App
import com.linroid.ketch.app.RecordingConfigStore
import com.linroid.ketch.app.instance.InstanceFactory
import com.linroid.ketch.app.instance.InstanceManager
import com.linroid.ketch.app.instance.RemoteInstance
import com.linroid.ketch.app.state.AiCandidate
import com.linroid.ketch.app.state.AiDiscoverRequest
import com.linroid.ketch.app.state.AiDiscoverResponse
import com.linroid.ketch.app.state.AiDiscoveryProvider
import com.linroid.ketch.app.state.AiDiscoveryProviderFactory
import com.linroid.ketch.app.state.AppController
import com.linroid.ketch.app.state.DiscoverRequest
import com.linroid.ketch.app.state.DiscoveryStep
import com.linroid.ketch.app.theme.KetchDensity
import com.linroid.ketch.app.theme.KetchTheme
import com.linroid.ketch.app.ui.discover.DiscoverScreen
import com.linroid.ketch.app.ui.shell.KetchLayout
import com.linroid.ketch.app.ui.shell.LocalKetchLayout
import com.linroid.ketch.config.AiSettings
import com.linroid.ketch.config.DensityMode
import com.linroid.ketch.config.LlmProvider
import com.linroid.ketch.config.LlmSettings
import com.linroid.ketch.remote.ConnectionState
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.BeforeTest
import kotlin.test.Test

/**
 * The Discover destination: the setup page before a model is chosen, the search before it runs,
 * the agent's steps while it runs, the results with the add bar, and what a failed or empty
 * search says; see [SnapshotHarness] for how to run it.
 */
class DiscoverSnapshots {
  @BeforeTest
  fun enabled() = requireSnapshots()

  @Test
  fun setup_unconfigured_offersTheProviders() {
    for (size in PageSizes) {
      for (theme in SnapshotTheme.entries) setupSnapshot("discover-setup", size, theme)
    }
  }

  @Test
  fun setup_pendingSearch_saysItRunsAfterSetup() {
    setupSnapshot("discover-setup-pending", CardPhone, SnapshotTheme.Light) {
      it.controller.state.openDiscover(DiscoverRequest("ubuntu 24.04 server iso"))
    }
  }

  @Test
  fun setup_providerChosenWithoutAKey_saysToFinishIt() {
    val cases = listOf(CardDesktop to SnapshotTheme.Light, CardPhone to SnapshotTheme.Dark)
    for ((size, theme) in cases) {
      setupSnapshot("discover-setup-chosen", size, theme) {
        it.controller.state.aiSettings.chooseProvider(LlmProvider.Anthropic)
      }
    }
  }

  @Test
  fun search_beforeTheFirstSearch_showsExamples() {
    for (size in AppSizes) {
      for (theme in SnapshotTheme.entries) {
        discoverSnapshot("discover-idle", size, theme, DiscoverScript.Results) {
          state.openDiscover(DiscoverRequest(QUERY))
          state.aiDiscover.reset()
          state.aiDiscover.draft.query = ""
        }
      }
    }
  }

  @Test
  fun search_running_showsTheAgentsSteps() {
    for (size in listOf(SnapshotSize.Desktop, SnapshotSize.Phone)) {
      for (theme in SnapshotTheme.entries) {
        discoverSnapshot("discover-running", size, theme, DiscoverScript.Running) {
          state.openDiscover(DiscoverRequest(QUERY))
        }
      }
    }
  }

  @Test
  fun search_results_showsThemWithTheAddBar() {
    for (size in AppSizes) {
      for (theme in SnapshotTheme.entries) {
        discoverSnapshot("discover-results", size, theme, DiscoverScript.Results) {
          state.openDiscover(DiscoverRequest(QUERY))
          state.aiDiscover.draft.selected = setOf(Candidates.first().url)
        }
      }
    }
  }

  @Test
  fun search_resultsOnAnotherDevice_showsTheTargetChip() {
    discoverSnapshot(
      name = "discover-results-nas",
      size = SnapshotSize.Desktop,
      theme = SnapshotTheme.Light,
      script = DiscoverScript.Results,
      twoDevices = true,
    ) {
      state.openDiscover(DiscoverRequest(QUERY, sites = listOf("blender.org")))
      state.aiDiscover.draft.selected = Candidates.take(2).map { it.url }.toSet()
      state.aiDiscover.draft.target = NAS_ID
    }
  }

  @Test
  fun search_failed_explainsAndOffersARetry() {
    for (size in listOf(SnapshotSize.Desktop, SnapshotSize.Phone)) {
      for (theme in SnapshotTheme.entries) {
        discoverSnapshot("discover-error", size, theme, DiscoverScript.Failure) {
          state.openDiscover(DiscoverRequest(QUERY))
        }
      }
    }
  }

  @Test
  fun search_nothingFound_offersTheWholeWeb() {
    for (theme in SnapshotTheme.entries) {
      discoverSnapshot("discover-empty", SnapshotSize.Medium, theme, DiscoverScript.Nothing) {
        state.openDiscover(DiscoverRequest(QUERY, sites = listOf("blender.org", "github.com")))
      }
    }
  }

  /** Renders the app at [size], with Discover running [script], after [open]. */
  private fun discoverSnapshot(
    name: String,
    size: SnapshotSize,
    theme: SnapshotTheme,
    script: DiscoverScript,
    twoDevices: Boolean = false,
    open: suspend AppScenario.() -> Unit,
  ): File = withEnvironment(theme, size.density, script, configured = true, twoDevices) { env ->
    SnapshotHarness.capture(
      name = "$name-${theme.id}-${size.id}",
      size = size,
      interact = { AppScenario(env.controller, env.data, this).open() },
    ) {
      App(env.controller)
    }
  }

  /**
   * Renders Discover before it is set up in a content card of [size], in a window [window]
   * wide; the shell offers Discover before it is set up only once the fleet shell lands.
   */
  private fun setupSnapshot(
    name: String,
    size: SnapshotSize,
    theme: SnapshotTheme,
    open: (DiscoverEnvironment) -> Unit = {},
  ): File = withEnvironment(
    theme = theme,
    density = size.density,
    script = DiscoverScript.Results,
    configured = false,
  ) { env ->
    runBlocking(SnapshotHarness.ui) { open(env) }
    snapshot(name, size, theme) {
      CardFrame(windowWidth = WindowWidths.getValue(size)) { DiscoverScreen(env.controller.state) }
    }
  }

  private fun <T> withEnvironment(
    theme: SnapshotTheme,
    density: KetchDensity,
    script: DiscoverScript,
    configured: Boolean,
    twoDevices: Boolean = false,
    block: (DiscoverEnvironment) -> T,
  ): T {
    val environment = runBlocking(SnapshotHarness.ui) {
      DiscoverEnvironment(theme, density.toMode(), script, configured, twoDevices)
    }
    try {
      return block(environment)
    } finally {
      runBlocking(SnapshotHarness.ui) { environment.close() }
    }
  }

  private companion object {
    const val QUERY = "Blender 4.2 for Apple silicon"
    const val NAS_ID = "nas.local:8642"
    val AppSizes = listOf(SnapshotSize.Desktop, SnapshotSize.Medium, SnapshotSize.Phone)

    // The content card of the default window, of a medium window beside the rail, and the
    // page between a phone's top and bottom bars.
    val CardDesktop = SnapshotSize(1052.dp, 784.dp, KetchDensity.Compact)
    val CardMedium = SnapshotSize(688.dp, 700.dp, KetchDensity.Compact)
    val CardPhone = SnapshotSize(390.dp, 700.dp, KetchDensity.Comfortable)
    val PageSizes = listOf(CardDesktop, CardMedium, CardPhone)
    val WindowWidths = mapOf(
      CardDesktop to SnapshotSize.Desktop.width,
      CardMedium to SnapshotSize.Medium.width,
      CardPhone to SnapshotSize.Phone.width,
    )
  }
}

/** What the sample discovery does when asked. */
internal enum class DiscoverScript {
  /** Reports its steps and finds [Candidates]. */
  Results,

  /** Reports its steps and keeps working. */
  Running,

  /** Reports its steps and finds nothing it trusts. */
  Nothing,

  /** Fails as a provider that rejects the key does. */
  Failure,
}

/**
 * The sample's devices with discovery: set up with Anthropic when [configured], else not set up
 * on a platform that can run it. With [twoDevices] the NAS is connected, so results can go to it.
 */
internal class DiscoverEnvironment(
  theme: SnapshotTheme,
  density: DensityMode,
  script: DiscoverScript,
  configured: Boolean,
  twoDevices: Boolean,
) {
  private val nas = SampleData.NAS.copy(name = "NAS-Basement", watch = twoDevices)
  val data = SampleData(tasks = SampleData.downloads().tasks, remotes = listOf(nas))
  private val instanceManager = InstanceManager(
    factory = InstanceFactory(
      deviceName = data.deviceName,
      embeddedFactory = { SampleKetchApi(data) },
      remoteFactory = { config ->
        RemoteInstance(
          instance = SampleKetchApi(data),
          remoteConfig = config,
          connectionState = MutableStateFlow(ConnectionState.Connected),
        )
      },
    ),
    initialRemotes = data.remotes,
    configStore = RecordingConfigStore(
      data.config(theme, density).copy(
        ai = if (configured) {
          AiSettings(
            enabled = true,
            llm = LlmSettings(provider = LlmProvider.Anthropic, apiKey = "sk-ant-sample"),
          )
        } else {
          AiSettings()
        },
      ),
    ),
  )

  /** The controller the app root shows. */
  val controller = AppController(
    instanceManager = instanceManager,
    aiProviderFactory = SampleDiscovery(script),
    context = SnapshotHarness.ui,
    clock = SampleData.CLOCK,
  )

  fun close() {
    controller.close()
    instanceManager.instances.value.filterIsInstance<RemoteInstance>()
      .forEach { it.instance.close() }
    instanceManager.close()
  }
}

/** Discovery that follows [script] once settings are usable. */
private class SampleDiscovery(private val script: DiscoverScript) : AiDiscoveryProviderFactory {
  override fun create(settings: AiSettings): AiDiscoveryProvider? {
    if (!settings.isUsable) return null
    return object : AiDiscoveryProvider {
      override suspend fun discover(
        request: AiDiscoverRequest,
        onStep: (DiscoveryStep) -> Unit,
      ): AiDiscoverResponse {
        Steps.forEach(onStep)
        return when (script) {
          DiscoverScript.Results -> AiDiscoverResponse(request.query, Candidates)
          DiscoverScript.Nothing -> AiDiscoverResponse(request.query, emptyList())
          DiscoverScript.Running -> {
            onStep(DiscoveryStep("Checking mirrors", "Comparing mirrors with the checksums"))
            awaitCancellation()
          }
          DiscoverScript.Failure -> error(
            "Anthropic rejected the API key (401). Check it in Settings › Discover.",
          )
        }
      }

      override suspend fun verify(): String = "OK"
    }
  }
}

/** A content card's surface, laid out as in a window [windowWidth] wide. */
@Composable
private fun CardFrame(windowWidth: Dp, content: @Composable () -> Unit) {
  CompositionLocalProvider(LocalKetchLayout provides KetchLayout.of(windowWidth)) {
    Box(Modifier.fillMaxSize().background(KetchTheme.colors.surface)) { content() }
  }
}

private val Steps = listOf(
  DiscoveryStep("Understanding", "Blender 4.2 for macOS on Apple silicon: an arm64 .dmg"),
  DiscoveryStep("Searched the web", "3 results from blender.org"),
  DiscoveryStep("Opened blender.org/download", "Found 6 download links"),
)

private val Candidates = listOf(
  AiCandidate(
    url = "https://download.blender.org/release/Blender4.2/blender-4.2.1-macos-arm64.dmg",
    title = "Blender 4.2.1 LTS for Apple silicon",
    fileName = "blender-4.2.1-macos-arm64.dmg",
    fileSize = 432_013_312,
    sourceUrl = "https://www.blender.org/download/",
    confidence = 0.92f,
    description = "Official 4.2.1 LTS installer for Macs with Apple silicon.",
  ),
  AiCandidate(
    url = "https://download.blender.org/release/Blender4.2/blender-4.2.1-macos-x64.dmg",
    title = "Blender 4.2.1 LTS for Intel Macs",
    fileName = "blender-4.2.1-macos-x64.dmg",
    fileSize = 450_887_680,
    sourceUrl = "https://www.blender.org/download/",
    confidence = 0.61f,
    description = "The Intel build of the same release; it runs under Rosetta.",
  ),
  AiCandidate(
    url = "http://mirror.example.edu/blender/release/Blender4.2/blender-4.2.1-macos-arm64.dmg",
    title = "University mirror",
    fileName = "blender-4.2.1-macos-arm64.dmg",
    sourceUrl = "https://www.blender.org/download/mirrors/",
    confidence = 0.55f,
    description = "A mirror of the Apple silicon installer, without TLS.",
  ),
  AiCandidate(
    url = "https://download.blender.org/release/Blender4.2/blender-4.2.1-linux-x64.tar.xz",
    title = "Blender 4.2.1 LTS for Linux",
    fileName = "blender-4.2.1-linux-x64.tar.xz",
    fileSize = 369_098_752,
    sourceUrl = "https://www.blender.org/download/",
    confidence = 0.34f,
    description = "",
  ),
)
