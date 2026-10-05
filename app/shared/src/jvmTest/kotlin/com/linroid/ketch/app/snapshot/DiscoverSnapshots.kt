package com.linroid.ketch.app.snapshot

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.linroid.ketch.app.input.KetchCommands
import com.linroid.ketch.app.instance.RemoteInstance
import com.linroid.ketch.app.state.AiCandidate
import com.linroid.ketch.app.state.AiDiscoverRequest
import com.linroid.ketch.app.state.AiDiscoverResponse
import com.linroid.ketch.app.state.AiDiscoveryProvider
import com.linroid.ketch.app.state.AiDiscoveryProviderFactory
import com.linroid.ketch.app.state.AiPageKind
import com.linroid.ketch.app.state.AiPageRequest
import com.linroid.ketch.app.state.DiscoverRequest
import com.linroid.ketch.app.state.DiscoverSession
import com.linroid.ketch.app.state.DiscoverTurn
import com.linroid.ketch.app.state.DiscoveryStep
import com.linroid.ketch.app.state.InMemoryDiscoverHistoryStore
import com.linroid.ketch.app.state.PageAccessChoice
import com.linroid.ketch.app.state.PageApproval
import com.linroid.ketch.app.state.TurnStatus
import com.linroid.ketch.app.theme.KetchDensity
import com.linroid.ketch.app.theme.KetchTheme
import com.linroid.ketch.app.ui.discover.DiscoverScreen
import com.linroid.ketch.app.ui.shell.KetchLayout
import com.linroid.ketch.app.ui.shell.LocalKetchLayout
import com.linroid.ketch.config.AiSettings
import com.linroid.ketch.config.LlmProvider
import com.linroid.ketch.config.LlmSettings
import com.linroid.ketch.config.PageAccessMode
import com.linroid.ketch.config.PageAccessSettings
import com.linroid.ketch.remote.ConnectionState
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * The Discover destination: the setup page before a model is chosen, the chat before its first
 * message, the agent's steps while it runs, its plan folded with Show more and every step's
 * details unfolded, the results with the add bar, a follow-up with the agent's reply and a
 * discarded result, a request to open a website waiting for an answer (also asking every time,
 * for a redirect to a long host, and in another search than the one shown), what a failed,
 * stopped, waiting or empty search says, the history of searches, also while Discover is
 * not set up, and the page's menu, which turns Discover off; see [SnapshotHarness] for how to run it.
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
          state.runInShell(KetchCommands.Discover)
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
  fun steps_planAndDetails_foldTheRunningStepAndListEveryStep() {
    val cases = listOf(
      SnapshotSize.Desktop to SnapshotTheme.Light,
      SnapshotSize.Phone to SnapshotTheme.Dark,
    )
    for ((size, theme) in cases) {
      // The plan the agent works on, folded to four lines with Show more.
      discoverSnapshot("discover-steps-running", size, theme, DiscoverScript.Planning) {
        state.openDiscover(DiscoverRequest(QUERY))
      }
      // A finished search with Details open: every step with all the agent said.
      discoverSnapshot("discover-steps-details", size, theme, DiscoverScript.Results) {
        state.openDiscover(DiscoverRequest(QUERY))
        scene.settle()
        // Back to the top of the thread, where the steps are.
        scene.scroll(x = size.width / 2, y = size.height / 2, ticks = -SCROLL_TICKS)
        scene.clickOnText(DETAILS)
        // Off the toggle, so it shows without its hover.
        scene.hover(x = size.width - 24.dp, y = 140.dp)
      }
    }
  }

  @Test
  fun search_results_showsThemWithTheAddBar() {
    for (size in AppSizes) {
      for (theme in SnapshotTheme.entries) {
        discoverSnapshot("discover-results", size, theme, DiscoverScript.Results) {
          state.openDiscover(DiscoverRequest(QUERY))
          state.aiDiscover.selected = setOf(Candidates.first().url)
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
      state.aiDiscover.selected = Candidates.take(2).map { it.url }.toSet()
      state.aiDiscover.target = NAS_ID
    }
  }

  @Test
  fun chat_followUp_showsBothMessagesTheReplyAndADiscardedResult() {
    for (size in AppSizes) {
      for (theme in SnapshotTheme.entries) {
        discoverSnapshot("discover-chat", size, theme, DiscoverScript.Results) {
          state.openDiscover(DiscoverRequest(QUERY))
          scene.settle()
          followUp()
          state.aiDiscover.discard(listOf(MIRROR_URL))
          // One from this message, one from the first.
          state.aiDiscover.selected = setOf(Candidates[0].url, Candidates[1].url)
        }
      }
    }
  }

  @Test
  fun chat_waitingForAnApproval_showsTheCard() {
    for (size in listOf(SnapshotSize.Desktop, SnapshotSize.Phone)) {
      for (theme in SnapshotTheme.entries) {
        discoverSnapshot(
          name = "discover-approval",
          size = size,
          theme = theme,
          script = DiscoverScript.Results,
          followUp = DiscoverScript.Approval,
        ) {
          state.openDiscover(DiscoverRequest(QUERY))
          scene.settle()
          followUp()
          // The first site was allowed for the chat; the mirror still waits. With a keyboard ⌘↩
          // answers from the composer, which keeps the keyboard; on touch, Allow does.
          val first = awaitApproval()
          if (size.density == KetchDensity.Compact) {
            scene.pressKey(Key.Enter, primary = true)
          } else {
            state.aiDiscover.answer(first.id, PageAccessChoice.AllowSite)
          }
          awaitApproval()
        }
      }
    }
  }

  @Test
  fun chat_approvalScrolledAway_offersTheJump() {
    discoverSnapshot(
      name = "discover-approval-jump",
      size = SnapshotSize.Phone,
      theme = SnapshotTheme.Light,
      script = DiscoverScript.Results,
      followUp = DiscoverScript.Approval,
    ) {
      state.openDiscover(DiscoverRequest(QUERY))
      scene.settle()
      followUp()
      awaitApproval()
      // Reads back up the thread, so the card leaves the screen.
      scene.scroll(x = 386.dp, y = 400.dp, ticks = -SCROLL_TICKS)
    }
  }

  @Test
  fun chat_askingEveryTime_offersAllowOnceForARedirect() {
    val cases = listOf(
      SnapshotSize.Desktop to SnapshotTheme.Dark,
      SnapshotSize.Medium to SnapshotTheme.Light,
      SnapshotSize.Phone to SnapshotTheme.Light,
    )
    for ((size, theme) in cases) {
      discoverSnapshot(
        name = "discover-approval-ask",
        size = size,
        theme = theme,
        script = DiscoverScript.Redirect,
        access = PageAccessSettings(mode = PageAccessMode.AskEveryTime),
      ) {
        // A long message, and a redirect to a long host with a long link.
        state.openDiscover(DiscoverRequest(LONG_QUERY))
        awaitApproval()
      }
    }
  }

  @Test
  fun chat_anotherSearchWaiting_countsItAndPostsAToast() {
    val cases = listOf(
      SnapshotSize.Desktop to SnapshotTheme.Light,
      SnapshotSize.Medium to SnapshotTheme.Dark,
      SnapshotSize.Phone to SnapshotTheme.Light,
    )
    for ((size, theme) in cases) {
      discoverSnapshot(
        name = "discover-waiting-elsewhere",
        size = size,
        theme = theme,
        script = DiscoverScript.Approval,
      ) {
        state.openDiscover(DiscoverRequest(QUERY))
        awaitApproval()
        // The user starts another search; the first one's request now waits out of view.
        state.aiDiscover.newSession()
      }
    }
  }

  @Test
  fun chat_stoppedFollowUp_offersTryAgain() {
    val cases = listOf(
      SnapshotSize.Desktop to SnapshotTheme.Light,
      SnapshotSize.Phone to SnapshotTheme.Dark,
    )
    for ((size, theme) in cases) {
      discoverSnapshot(
        name = "discover-stopped",
        size = size,
        theme = theme,
        script = DiscoverScript.Results,
        followUp = DiscoverScript.Running,
      ) {
        state.openDiscover(DiscoverRequest(QUERY))
        scene.settle()
        followUp()
        state.aiDiscover.stop()
      }
    }
  }

  @Test
  fun chat_failedFollowUp_explainsUnderTheEarlierResults() {
    for (theme in SnapshotTheme.entries) {
      discoverSnapshot(
        name = "discover-failed-follow-up",
        size = SnapshotSize.Medium,
        theme = theme,
        script = DiscoverScript.Results,
        followUp = DiscoverScript.Failure,
      ) {
        state.openDiscover(DiscoverRequest(QUERY))
        scene.settle()
        followUp()
      }
    }
  }

  @Test
  fun chat_moreSearchesThanSlots_waitsToStart() {
    discoverSnapshot(
      name = "discover-queued",
      size = SnapshotSize.Desktop,
      theme = SnapshotTheme.Light,
      script = DiscoverScript.Running,
    ) {
      // Three searches take every slot, so the fourth waits.
      for (query in listOf("Ubuntu 24.04 server ISO", "ffmpeg 7 static build", "OBS Studio 30")) {
        state.openDiscover(DiscoverRequest(query))
      }
      state.openDiscover(DiscoverRequest(QUERY))
    }
  }

  @Test
  fun chat_discardingAResult_postsUndoAboveTheComposer() {
    for (size in listOf(SnapshotSize.Desktop, SnapshotSize.Phone)) {
      discoverSnapshot("discover-discarded", size, SnapshotTheme.Light, DiscoverScript.Results) {
        state.openDiscover(DiscoverRequest(QUERY))
        scene.settle()
        state.aiDiscover.selected = setOf(Candidates[0].url)
        state.discardDiscovered(listOf(MIRROR_URL))
      }
    }
  }

  @Test
  fun history_savedSearches_listsThemByDay() {
    for (size in AppSizes) {
      for (theme in SnapshotTheme.entries) {
        discoverSnapshot(
          name = "discover-history",
          size = size,
          theme = theme,
          script = DiscoverScript.Running,
          history = SavedSessions,
        ) {
          state.openDiscover(DiscoverRequest(QUERY))
          scene.settle()
          // The search started last keeps running while an earlier one shows.
          state.aiDiscover.open(UBUNTU_ID)
          scene.settle()
          // Docked beside the chat on the desktop; elsewhere it opens over it.
          if (size != SnapshotSize.Desktop) scene.clickOn(SHOW_HISTORY)
        }
      }
    }
  }

  @Test
  fun history_discoverNotSetUp_showsASearchWithoutTheComposer() {
    withEnvironment(
      create = {
        discovery(
          theme = SnapshotTheme.Light,
          size = SnapshotSize.Desktop,
          script = DiscoverScript.Results,
          followUp = DiscoverScript.Results,
          configured = false,
          history = SavedSessions,
        )
      },
    ) { env ->
      captureApp("discover-history-unavailable", SnapshotSize.Desktop, SnapshotTheme.Light, env) {
        state.aiDiscover.open(UBUNTU_ID)
        state.runInShell(KetchCommands.Discover)
      }
    }
  }

  @Test
  fun menu_open_offersToTurnDiscoverOff() {
    for (size in listOf(SnapshotSize.Desktop, SnapshotSize.Phone)) {
      discoverSnapshot("discover-menu", size, SnapshotTheme.Light, DiscoverScript.Results) {
        state.openDiscover(DiscoverRequest(QUERY))
        scene.settle()
        scene.clickOn(MORE)
      }
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

  @Test
  fun search_contentFilterHidSome_saysHowManyUnderTheResults() {
    discoverSnapshot(
      "discover-filtered",
      SnapshotSize.Desktop,
      SnapshotTheme.Light,
      DiscoverScript.SomeFiltered,
    ) {
      state.openDiscover(DiscoverRequest(QUERY))
    }
  }

  @Test
  fun search_contentFilterHidEverything_offersItsSettings() {
    for (theme in SnapshotTheme.entries) {
      val script = DiscoverScript.AllFiltered
      discoverSnapshot("discover-all-filtered", SnapshotSize.Medium, theme, script) {
        state.openDiscover(DiscoverRequest(QUERY))
      }
    }
  }

  /**
   * Renders the app at [size], with Discover running [script] for a chat's first message and
   * [followUp] for the others, after [open].
   */
  private fun discoverSnapshot(
    name: String,
    size: SnapshotSize,
    theme: SnapshotTheme,
    script: DiscoverScript,
    followUp: DiscoverScript = script,
    twoDevices: Boolean = false,
    history: List<DiscoverSession> = emptyList(),
    access: PageAccessSettings = PageAccessSettings(),
    open: suspend AppScenario.() -> Unit,
  ): File = withEnvironment(
    create = {
      discovery(
        theme = theme,
        size = size,
        script = script,
        followUp = followUp,
        configured = true,
        twoDevices = twoDevices,
        history = history,
        access = access,
      )
    },
  ) {
    captureApp(name, size, theme, it, open)
  }

  /** Sends the follow-up to the shown chat and lets the thread settle. */
  private suspend fun AppScenario.followUp() {
    state.aiDiscover.draft.text = TextFieldValue(FOLLOW_UP)
    state.aiDiscover.send()
    scene.settle()
  }

  /** The oldest request to open a website, once one waits. */
  private suspend fun AppScenario.awaitApproval(): PageApproval {
    val approval = withTimeoutOrNull(APPROVAL_TIMEOUT) {
      while (state.aiDiscover.approvals.isEmpty()) delay(FRAME)
      state.aiDiscover.approvals.first()
    }
    scene.settle()
    return checkNotNull(approval) { "No request to open a website came" }
  }

  /**
   * Renders Discover before it is set up in a content card of [size], in a window [window]
   * wide; the shell offers Discover before it is set up only once the fleet shell lands.
   */
  private fun setupSnapshot(
    name: String,
    size: SnapshotSize,
    theme: SnapshotTheme,
    open: (SampleEnvironment) -> Unit = {},
  ): File = withEnvironment(
    create = {
      discovery(theme, size, DiscoverScript.Results, DiscoverScript.Results, configured = false)
    },
  ) { env ->
    onUiThread { open(env) }
    snapshot(name, size, theme) {
      CardFrame(windowWidth = WindowWidths.getValue(size)) { DiscoverScreen(env.controller.state) }
    }
  }

  private companion object {
    const val QUERY = "Blender 4.2 for Apple silicon"
    const val LONG_QUERY = "Blender 4.2 LTS for Apple silicon, the official build from the " +
      "release page, plus the matching Python API reference as a single archive if they " +
      "publish one"
    const val FOLLOW_UP = "Only the LTS release, from a source with TLS"
    val APPROVAL_TIMEOUT = 2.seconds
    val FRAME = 16.milliseconds
    const val SCROLL_TICKS = 40f
    const val NAS_ID = "nas.local:8642"
    const val SHOW_HISTORY = "Show history"
    const val DETAILS = "Details"
    const val MORE = "More"
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

  /** Reports its steps up to its plan, [BlenderPlan] for Blender, and keeps working on it. */
  Planning,

  /** Reports its steps and finds nothing it trusts. */
  Nothing,

  /** Reports its steps and finds [Candidates], with two more the content filter hid. */
  SomeFiltered,

  /** Reports its steps and finds three results, which the content filter all hid. */
  AllFiltered,

  /** Fails as a provider that rejects the key does. */
  Failure,

  /** Reports its steps and asks to open two websites, then keeps working. */
  Approval,

  /** Reports its steps and asks to follow a redirect to a long host, then keeps working. */
  Redirect,
}

/**
 * The sample's devices with discovery, at [size]'s density: set up with Anthropic when
 * [configured], else not set up on a platform that can run it. Discovery follows [script] for a
 * chat's first message and [followUp] for the others. With [twoDevices] the NAS is connected,
 * so results can go to it. [history] holds the searches saved before the app started, and
 * [access] says when Discover asks before it opens a website.
 */
internal fun discovery(
  theme: SnapshotTheme,
  size: SnapshotSize,
  script: DiscoverScript,
  followUp: DiscoverScript,
  configured: Boolean,
  twoDevices: Boolean = false,
  history: List<DiscoverSession> = emptyList(),
  access: PageAccessSettings = PageAccessSettings(),
): SampleEnvironment {
  val nas = SampleData.NAS.copy(name = "NAS-Basement", watch = twoDevices)
  val data = SampleData(tasks = SampleData.downloads().tasks, remotes = listOf(nas))
  return SampleEnvironment(
    data = data,
    theme = theme,
    density = size.density.toMode(),
    aiProviderFactory = SampleDiscovery(script, followUp),
    remote = { config ->
      RemoteInstance(
        instance = SampleKetchApi(data),
        remoteConfig = config,
        connectionState = MutableStateFlow(ConnectionState.Connected),
      )
    },
    config = { config ->
      config.copy(
        ai = if (configured) {
          AiSettings(
            enabled = true,
            llm = LlmSettings(provider = LlmProvider.Anthropic, apiKey = "sk-ant-sample"),
            access = access,
          )
        } else {
          AiSettings()
        },
      )
    },
    discoverHistory = InMemoryDiscoverHistoryStore(history),
    seedHistory = false,
  )
}

/**
 * Discovery that follows [script] for a chat's first message and [followUp] for the others,
 * once settings are usable. A first message that finds results answers with [Candidates] and
 * names the chat [FIRST_TITLE]; a follow-up that finds results answers with [FollowUpCandidates]
 * and a reply.
 */
private class SampleDiscovery(
  private val script: DiscoverScript,
  private val followUp: DiscoverScript = script,
) : AiDiscoveryProviderFactory {
  override fun create(settings: AiSettings): AiDiscoveryProvider? {
    if (!settings.isUsable) return null
    return object : AiDiscoveryProvider {
      override suspend fun discover(
        request: AiDiscoverRequest,
        onStep: (DiscoveryStep) -> Unit,
        approve: suspend (AiPageRequest) -> Boolean,
      ): AiDiscoverResponse {
        val first = request.history.isEmpty()
        val current = if (first) script else followUp
        // A follow-up keeps to what the chat's first message asked for.
        val steps = stepsFor(request.history.firstOrNull()?.request ?: request.query)
        val reported = when (current) {
          DiscoverScript.Planning -> steps.take(steps.indexOf(BlenderPlan) + 1)
          else -> steps
        }
        reported.forEach(onStep)
        return when (current) {
          DiscoverScript.Results -> if (first) {
            AiDiscoverResponse(request.query, Candidates, title = FIRST_TITLE)
          } else {
            AiDiscoverResponse(request.query, FollowUpCandidates, FOLLOW_UP_SUMMARY)
          }
          DiscoverScript.Nothing -> AiDiscoverResponse(request.query, emptyList())
          DiscoverScript.SomeFiltered ->
            AiDiscoverResponse(request.query, Candidates, title = FIRST_TITLE, filtered = 2)
          DiscoverScript.AllFiltered -> AiDiscoverResponse(
            query = request.query,
            candidates = emptyList(),
            summary = "Found 3 official installers for Windows and macOS.",
            filtered = 3,
          )
          DiscoverScript.Running -> {
            onStep(DiscoveryStep("Checking mirrors", "Comparing mirrors with the checksums"))
            awaitCancellation()
          }
          DiscoverScript.Planning -> awaitCancellation()
          DiscoverScript.Failure -> error(
            "Anthropic rejected the API key (401). Check it in Settings › Discover.",
          )
          DiscoverScript.Approval -> {
            onStep(DiscoveryStep("Checking the LTS page", "blender.org/download/lts"))
            Approvals.forEach { approve(it) }
            awaitCancellation()
          }
          DiscoverScript.Redirect -> {
            onStep(DiscoveryStep("Opening the release", "github.com/blender/blender/releases"))
            approve(RedirectApproval)
            awaitCancellation()
          }
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

/** Requests to open websites, in the order the agent asks. */
private val Approvals = listOf(
  AiPageRequest(
    url = "https://www.blender.org/download/lts/4-2/",
    host = "www.blender.org",
    kind = AiPageKind.Page,
    reason = "The LTS page lists the current 4.2 builds.",
  ),
  AiPageRequest(
    url = "https://mirror.example.edu/blender/release/Blender4.2/" +
      "blender-4.2.1-macos-arm64.dmg?session=a8f2e91c",
    host = "mirror.example.edu",
    kind = AiPageKind.FileInfo,
    reason = "Checking that the mirror serves the same file over HTTPS.",
  ),
)

/** A release link that redirects to a long CDN host, with a long signed link. */
private val RedirectApproval = AiPageRequest(
  url = "https://objects.githubusercontent-production.example-cdn.com/github-production-" +
    "release-asset-2e65be/1170917/blender-4.2.1-macos-arm64.dmg?X-Amz-Algorithm=AWS4-HMAC-" +
    "SHA256&X-Amz-Credential=releaseassetproduction&X-Amz-Signature=8c1f0e6b2a",
  host = "objects.githubusercontent-production.example-cdn.com",
  kind = AiPageKind.Page,
  reason = "GitHub sends the release download to its file storage; the file is the same " +
    "installer the release page lists, and following it confirms its size and type.",
  redirectFrom = "github.com",
)

/** The plan the agent reports for Blender, one numbered line per thing it will do. */
internal val BlenderPlan = DiscoveryStep(
  title = "Plan",
  detail = "1. Search blender.org for the macOS Apple silicon downloads, the current and LTS " +
    "releases.\n" +
    "2. Open the Blender download pages and extract the direct .dmg links.\n" +
    "3. Check each link with a HEAD request for its size and type.\n" +
    "4. Compare the mirrors with the checksums blender.org publishes.\n" +
    "5. Rank the official arm64 build first, then the builds for other platforms.",
)

/** What the agent reports while it looks for Blender, the sample's main search. */
internal val BlenderSteps = listOf(
  DiscoveryStep("Understanding", "Blender 4.2 for macOS on Apple silicon: an arm64 .dmg"),
  BlenderPlan,
  DiscoveryStep("Searched the web", "3 results from blender.org"),
  DiscoveryStep("Opened blender.org/download", "Found 6 download links"),
)

private val UbuntuSteps = listOf(
  DiscoveryStep("Understanding", "Ubuntu Server 24.04 LTS: the live server install ISO"),
  DiscoveryStep("Searched the web", "4 results from ubuntu.com"),
  DiscoveryStep("Opened ubuntu.com/download/server", "Found 2 download links"),
  DiscoveryStep("Opened releases.ubuntu.com/24.04.1", "Matched the ISO's size and checksum"),
)

private val UbuntuArmSteps = listOf(
  DiscoveryStep("Understanding", "The arm64 server image, for a Raspberry Pi"),
  DiscoveryStep("Opened ubuntu.com/download/server/arm", "Found the 24.04.1 arm64 image"),
  DiscoveryStep("Opened cdimage.ubuntu.com", "Matched the ISO's size and checksum"),
)

// The search failed before it opened a page.
private val FfmpegSteps = listOf(
  DiscoveryStep("Understanding", "FFmpeg 7 as a static build for 64-bit Linux"),
  DiscoveryStep("Searched the web", "5 results from ffmpeg.org"),
)

private val FootageSteps = listOf(
  DiscoveryStep("Understanding", "4K nature clips in the public domain"),
  DiscoveryStep("Searched the web", "6 results from archive.org"),
  DiscoveryStep("Opened archive.org/details/nature-4k", "Found 3 videos"),
)

// Stopped while it searched.
private val InkscapeSteps = listOf(
  DiscoveryStep("Understanding", "Inkscape 1.4 for Windows: the portable .7z archive"),
  DiscoveryStep("Searched the web", "4 results from inkscape.org"),
)

private val FedoraSteps = listOf(
  DiscoveryStep("Understanding", "Fedora Workstation 41: the live ISO"),
  DiscoveryStep("Searched the web", "3 results from fedoraproject.org"),
  DiscoveryStep("Opened fedoraproject.org/workstation/download", "Only Fedora 42 is listed"),
)

private val ObsSteps = listOf(
  DiscoveryStep("Understanding", "OBS Studio 30 for macOS on Apple silicon"),
  DiscoveryStep("Searched the web", "3 results from obsproject.com"),
  DiscoveryStep("Opened obsproject.com/download", "Found 4 download links"),
)

/** What the agent reports while it looks for [subject]; Blender unless it is another sample. */
private fun stepsFor(subject: String): List<DiscoveryStep> {
  val words = subject.lowercase()
  return when {
    "ubuntu" in words -> UbuntuSteps
    "ffmpeg" in words -> FfmpegSteps
    "obs studio" in words -> ObsSteps
    "inkscape" in words -> InkscapeSteps
    "fedora" in words -> FedoraSteps
    "footage" in words -> FootageSteps
    else -> BlenderSteps
  }
}

private const val MIRROR_URL =
  "http://mirror.example.edu/blender/release/Blender4.2/blender-4.2.1-macos-arm64.dmg"

/** What the agent calls a chat whose first message finds [Candidates]. */
private const val FIRST_TITLE = "Blender 4.2 LTS for Apple silicon"

private const val FOLLOW_UP_SUMMARY = "The 4.2.1 LTS installer for Apple silicon comes " +
  "straight from blender.org over HTTPS. I left out the university mirror: it serves the same " +
  "file without TLS."

/** What a chat's first message finds, best first; the third one has no TLS. */
internal val Candidates = listOf(
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
    url = MIRROR_URL,
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

/** What the follow-up finds: the LTS build again, and the mirror, which the chat discards. */
private val FollowUpCandidates = listOf(Candidates[0].copy(confidence = 0.96f), Candidates[2])

/** Id of the saved Ubuntu search, which has a follow-up. */
internal const val UBUNTU_ID = "saved-ubuntu"

/** Searches saved over the last two weeks, as the history lists them before a new one runs. */
internal val SavedSessions: List<DiscoverSession> by lazy {
  listOf(
    saved(
      id = UBUNTU_ID,
      at = "2026-10-01T13:12:00Z",
      title = "Ubuntu Server 24.04 install image",
      turns = listOf(
        savedTurn("Ubuntu 24.04 server ISO", "2026-10-01T13:06:00Z", UbuntuCandidates),
        savedTurn(
          message = "Only the arm64 image, for a Raspberry Pi",
          at = "2026-10-01T13:11:00Z",
          candidates = UbuntuCandidates.drop(1),
          steps = UbuntuArmSteps,
          summary = "The arm64 image is Canonical's own 24.04.1 LTS build; it boots on the " +
            "Raspberry Pi 4 and 5 and on other ARM servers.",
        ),
      ),
    ),
    saved(
      id = "saved-ffmpeg",
      at = "2026-10-01T11:05:00Z",
      turns = listOf(
        savedTurn("ffmpeg 7 static build for Linux", "2026-10-01T11:04:00Z")
          .copy(status = TurnStatus.Failed, errorText = "The model provider timed out."),
      ),
    ),
    saved(
      id = "saved-footage",
      at = "2026-09-30T18:20:00Z",
      title = "Public-domain 4K nature clips",
      turns = listOf(
        savedTurn("Public-domain 4K nature footage", "2026-09-30T18:16:00Z", FootageCandidates),
      ),
    ),
    saved(
      id = "saved-inkscape",
      at = "2026-09-30T09:40:00Z",
      turns = listOf(
        savedTurn("Inkscape 1.4 portable for Windows", "2026-09-30T09:39:00Z")
          .copy(status = TurnStatus.Stopped),
      ),
    ),
    saved(
      id = "saved-fedora",
      at = "2026-09-24T16:02:00Z",
      title = "Fedora Workstation 41 live image",
      turns = listOf(savedTurn("Fedora Workstation 41 live ISO", "2026-09-24T16:00:00Z")),
    ),
    saved(
      id = "saved-obs",
      at = "2026-09-18T08:45:00Z",
      turns = listOf(
        savedTurn("OBS Studio 30 for Apple silicon", "2026-09-18T08:43:00Z", ObsCandidates),
      ),
    ),
  )
}

/**
 * A saved chat of [turns], last run [at]: called [title], the agent's name for it, or by its first
 * message when the agent gave none, as when its first turn failed.
 */
private fun saved(
  id: String,
  at: String,
  turns: List<DiscoverTurn>,
  title: String = turns.first().message,
): DiscoverSession =
  DiscoverSession(
    id = id,
    title = title,
    createdAt = turns.first().startedAt,
    updatedAt = Instant.parse(at),
    turns = turns,
  )

/** A finished turn that asked for [message]; its [steps] are those of its subject by default. */
private fun savedTurn(
  message: String,
  at: String,
  candidates: List<AiCandidate> = emptyList(),
  summary: String = "",
  steps: List<DiscoveryStep> = stepsFor(message),
): DiscoverTurn = DiscoverTurn(
  id = "turn-$at",
  message = message,
  sites = emptyList(),
  startedAt = Instant.parse(at),
  status = TurnStatus.Done,
  steps = steps,
  candidates = candidates,
  summary = summary,
)

private val UbuntuCandidates = listOf(
  AiCandidate(
    url = "https://releases.ubuntu.com/24.04.1/ubuntu-24.04.1-live-server-amd64.iso",
    title = "Ubuntu Server 24.04.1 LTS",
    fileName = "ubuntu-24.04.1-live-server-amd64.iso",
    fileSize = 2_773_874_688,
    sourceUrl = "https://ubuntu.com/download/server",
    confidence = 0.95f,
    description = "The official 64-bit PC (AMD64) server install image.",
  ),
  AiCandidate(
    url = "https://cdimage.ubuntu.com/releases/24.04.1/release/" +
      "ubuntu-24.04.1-live-server-arm64.iso",
    title = "Ubuntu Server 24.04.1 LTS for ARM",
    fileName = "ubuntu-24.04.1-live-server-arm64.iso",
    fileSize = 2_545_442_816,
    sourceUrl = "https://ubuntu.com/download/server/arm",
    confidence = 0.9f,
    description = "The official 64-bit ARM (ARMv8/AArch64) server install image.",
  ),
)

private val FootageCandidates = listOf("yosemite-falls", "kelp-forest", "aurora-timelapse").map {
  AiCandidate(
    url = "https://archive.org/download/nature-4k/$it-4k.mp4",
    title = it,
    fileName = "$it-4k.mp4",
    sourceUrl = "https://archive.org/details/nature-4k",
    confidence = 0.8f,
    description = "",
  )
}

private val ObsCandidates = listOf(
  AiCandidate(
    url = "https://cdn-fastly.obsproject.com/downloads/obs-studio-30.2.3-macos-arm64.dmg",
    title = "OBS Studio 30.2.3 for Apple silicon",
    fileName = "obs-studio-30.2.3-macos-arm64.dmg",
    sourceUrl = "https://obsproject.com/download",
    confidence = 0.93f,
    description = "",
  ),
)
