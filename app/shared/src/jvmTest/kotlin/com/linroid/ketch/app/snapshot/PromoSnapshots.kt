package com.linroid.ketch.app.snapshot

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.LayoutInfo
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalPlatformWindowInsets
import androidx.compose.ui.platform.PlatformInsets
import androidx.compose.ui.platform.PlatformWindowInsets
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.linroid.ketch.api.DownloadConfig
import com.linroid.ketch.api.DownloadState
import com.linroid.ketch.api.KetchApi
import com.linroid.ketch.api.ResolveInput
import com.linroid.ketch.api.ResolvedSource
import com.linroid.ketch.api.SourceFile
import com.linroid.ketch.api.SpeedLimit
import com.linroid.ketch.app.App
import com.linroid.ketch.app.RecordingConfigStore
import com.linroid.ketch.app.components.StepperCount
import com.linroid.ketch.app.i18n.load
import com.linroid.ketch.app.i18n.text
import com.linroid.ketch.app.input.KetchCommands
import com.linroid.ketch.app.instance.InstanceFactory
import com.linroid.ketch.app.instance.InstanceManager
import com.linroid.ketch.app.instance.RemoteInstance
import com.linroid.ketch.app.state.AiCandidate
import com.linroid.ketch.app.state.AiDiscoverRequest
import com.linroid.ketch.app.state.AiDiscoverResponse
import com.linroid.ketch.app.state.AiDiscoveryProvider
import com.linroid.ketch.app.state.AiDiscoveryProviderFactory
import com.linroid.ketch.app.state.AiPageRequest
import com.linroid.ketch.app.state.AppController
import com.linroid.ketch.app.state.DiscoveryStep
import com.linroid.ketch.app.state.IntakeRequest
import com.linroid.ketch.app.state.LOCAL_DEVICE_ID
import com.linroid.ketch.app.state.ListTestTask
import com.linroid.ketch.app.state.ObservedPeak
import com.linroid.ketch.app.state.SettingsTarget
import com.linroid.ketch.app.state.SpeedModeController
import com.linroid.ketch.app.state.SpeedScheduler
import com.linroid.ketch.app.state.TaskKey
import com.linroid.ketch.app.theme.KetchAccent
import com.linroid.ketch.app.theme.KetchDensity
import com.linroid.ketch.app.theme.KetchTheme
import com.linroid.ketch.app.ui.inspector.tabs.InspectorTab
import com.linroid.ketch.config.AiSettings
import com.linroid.ketch.config.DensityMode
import com.linroid.ketch.config.LlmProvider
import com.linroid.ketch.config.LlmSettings
import com.linroid.ketch.config.ServerConfig
import com.linroid.ketch.config.SpeedLimitMode
import com.linroid.ketch.config.SpeedRule
import com.linroid.ketch.config.SpeedSettings
import com.linroid.ketch.config.Weekday
import com.linroid.ketch.remote.ConnectionState
import ketch.app.shared.generated.resources.Res
import ketch.app.shared.generated.resources.command_palette
import ketch.app.shared.generated.resources.discover_clear_selection
import ketch.app.shared.generated.resources.discover_results
import ketch.app.shared.generated.resources.discover_select_all
import ketch.app.shared.generated.resources.discover_title
import ketch.app.shared.generated.resources.downloads_inspector_resize
import ketch.app.shared.generated.resources.group_title_waiting
import ketch.app.shared.generated.resources.inspector_connections_live
import ketch.app.shared.generated.resources.inspector_control_priority
import ketch.app.shared.generated.resources.inspector_lane_description
import ketch.app.shared.generated.resources.intake_title_add
import ketch.app.shared.generated.resources.priority_low
import ketch.app.shared.generated.resources.priority_urgent
import ketch.app.shared.generated.resources.settings_network_spread
import ketch.app.shared.generated.resources.settings_speed_mode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.yield
import kotlinx.datetime.TimeZone
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.jetbrains.compose.resources.ExperimentalResourceApi
import org.jetbrains.compose.resources.InternalResourceApi
import org.jetbrains.compose.resources.LocalResourceReader
import org.jetbrains.compose.resources.ResourceReader
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.skia.Data
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import org.junit.Assume.assumeTrue
import java.io.File
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.Base64
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlin.math.sin
import kotlin.random.Random
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.DurationUnit
import kotlin.time.TimeSource

/**
 * Assets of the promotional video in `art/promo`: 60 fps frame sequences of the real app and
 * stills, over the brand-free [ShowcaseData], in the language of one [PromoLocale] and the theme
 * of one film ([Film]), each at the pixel density the film needs to show it sharp at 4K. Not
 * part of the snapshot suite: `art/promo/scripts/assets/render-ui.sh <locale> [--theme light]
 * [test…]` runs it, one asset after another, and copies what it renders into the video;
 * `art/promo/docs/ASSETS.md` explains every step.
 *
 * The run's `-PsnapshotLocale` picks the [PromoLocale] (English without one) and
 * `-PsnapshotTheme` the film's theme (dark without one). An asset is named for the theme it
 * shows (`discover-dark`, `discover-light`), and everything a run renders lands in
 * `build/snapshots/promo/<locale>/` (the dark film) or `promo/<locale>/light/` (the light one):
 * - `<clip>/0000.png` (or `.jpg`), `0001.png`, …: a clip's frames, and `<clip>.txt`, how long
 *   its recording took;
 * - `stills/<still>.png`;
 * - `<asset>.json`, what the video needs to know about each asset besides its pixels: its kind,
 *   size, density, frames and their rate, where its parts are ([AssetBounds]), when its events
 *   happen and which frame of another clip it matches;
 * - `<asset>.semantics.txt`, every semantics node of the screen with its bounds, to find anchors
 *   for new bounds by.
 *
 * Every test records its asset in the film's theme, except [desktopOtherTheme]: S02 turns the
 * window to the other theme and through the four accents, so the dark film's run records the
 * light window for it and the light film's run the dark accents (its window is the dark film's
 * own clip).
 *
 * Everything runs on the virtual time of [SnapshotClock]: the app's timers (rows every 250 ms,
 * speed samples every second, the lane rates), Compose's animations and the data. Frame n
 * renders at exactly n / [FPS] s after its timeline starts, once the data has moved on to that
 * time and the clock has reached it, so a recording is the same on a fast or a busy machine and
 * the app's meters read exactly the rates the data moves at.
 */
class PromoSnapshots {
  @BeforeTest
  fun enabled() = requireSnapshots()

  /** The desktop window S02 and S03 play end to end, in the film's theme. */
  @Test
  fun desktop() = desktopClip(
    Film,
    DESKTOP_FRAMES,
    // The light film's window runs with the dark one, whose clip its S02 shows in the dark beat.
    syncedTo = SyncedTo(DARK_CLIP, offset = 0).takeIf { Film != SnapshotTheme.Dark },
  )

  /**
   * S02's beat in the other theme: in the dark film the light window, [LIGHT_OFFSET_FRAMES]
   * behind the dark one, and the light accent stills; in the light film the dark accent stills
   * only, as its beat plays `desktop-dark` itself.
   */
  @Test
  fun desktopOtherTheme() = when (Film) {
    SnapshotTheme.Dark -> desktopClip(
      SnapshotTheme.Light,
      DESKTOP_LIGHT_FRAMES,
      offset = LIGHT_OFFSET_FRAMES,
      accentsAt = AccentFrames,
    )
    SnapshotTheme.Light -> desktopClip(SnapshotTheme.Dark, count = 0, accentsAt = AccentFrames)
  }

  @Test
  fun discover() = discoverClip(Film)

  @Test
  fun iosLanes() = phoneClip(PhoneKind.Ios, "ios-lanes")

  @Test
  fun androidDevices() = phoneClip(PhoneKind.Android, "android-devices")

  @Test
  fun stillDownloads() = desktopStill(
    "downloads",
    scale = DOWNLOADS_SCALE,
    bounds = AssetBounds.Downloads,
  ) {}

  @Test
  fun stillInspectorOverview() = desktopStill(
    "inspector-overview",
    scale = DOWNLOADS_SCALE,
    bounds = AssetBounds.InspectorOverview,
  ) { env ->
    env.controller.state.inspect(env.studioKey(ShowcaseData.ISO_ID))
  }

  @Test
  fun stillAllDevices() = desktopStill(
    "all-devices",
    device = PromoDevice.SharedDesktop,
    table = AllDevicesColumns,
  ) {
    it.controller.state.showAllDevices()
  }

  @Test
  fun stillPalette() = desktopStill(
    "palette",
    scale = PALETTE_SCALE,
    bounds = AssetBounds.Palette,
  ) { env ->
    env.controller.state.runInShell(KetchCommands.Palette)
  }

  @Test
  fun stillIntakeTorrent() = desktopStill(
    "intake-torrent",
    scale = INTAKE_SCALE,
    bounds = AssetBounds.IntakeTorrent,
  ) { env ->
    env.controller.state.openIntake(IntakeRequest(PROMO_MAGNET))
    scene.settle()
    // Two days left out, to show the choice of files.
    for (file in TorrentSkipped) scene.clickOnText(file)
  }

  @Test
  fun stillSettingsSpeed() = desktopStill(
    "settings-speed",
    device = SettingsDevice,
    scale = SETTINGS_SCALE,
    bounds = AssetBounds.SpeedSettings,
  ) { env ->
    env.controller.state.openSettings(SettingsTarget(SettingsTarget.Page.Speed, LOCAL_DEVICE_ID))
  }

  @Test
  fun stillSettingsNetwork() = desktopStill(
    "settings-network",
    device = SettingsDevice,
    scale = SETTINGS_SCALE,
    bounds = AssetBounds.NetworkSettings,
  ) {
    it.controller.state.openSettings(SettingsTarget(SettingsTarget.Page.Network, LOCAL_DEVICE_ID))
  }

  @Test
  fun stillWeb() = desktopStill("web", device = PromoDevice.Web, size = WebWindow) {}

  /**
   * The Downloads table in each of [PromoLanguages]. The film shows the same ones in every
   * locale, so only the default locale's run renders them and another skips this.
   */
  @Test
  fun stillLanguages() {
    assumeTrue("Every locale shares the English run's lang-* stills", Promo.isDefault)
    for (tag in PromoLanguages) {
      desktopStill("lang-$tag", language = tag, scale = LANGUAGES_SCALE) { env ->
        env.controller.state.inspect(env.studioKey(ShowcaseData.ISO_ID))
      }
    }
  }
}

// ═════════════════════════════════════════════════════════════════════════════════════════════
// Per-locale content: everything the assets show that is not the app's own text. To add a
// locale, add an entry here, a row to `art/promo/scripts/assets/locales.json` and its copy in
// `art/promo/src/scenes/copy/`; docs/ASSETS.md walks through it.
// ═════════════════════════════════════════════════════════════════════════════════════════════

/** The locale this run renders, from the `user.*` properties `-PsnapshotLocale` sets. */
private val Promo: PromoLocale by lazy { PromoLocale.ofRun() }

/**
 * The theme of the film this run records for, from the `ketch.snapshots.theme` property
 * `-PsnapshotTheme` sets: dark (the default) or light. Every asset but [desktopOtherTheme]'s
 * shows the app in it.
 */
private val Film: SnapshotTheme by lazy {
  val id = System.getProperty(THEME_PROPERTY)?.takeIf { it.isNotBlank() } ?: SnapshotTheme.Dark.id
  SnapshotTheme.entries.firstOrNull { it.id == id }
    ?: error(
      "No promo theme -PsnapshotTheme=$id; use one of " +
        SnapshotTheme.entries.joinToString { it.id },
    )
}

/** [base], the name of an asset in every theme, as the asset showing the app in [theme]. */
private fun themed(base: String, theme: SnapshotTheme = Film): String = "$base-${theme.id}"

/**
 * A language the video is made in.
 *
 * @property tag the locale's name in `art/promo`: its assets live in `promo/<tag>/` here and in
 *   `public/clips/<tag>/` there, and `-PsnapshotLocale=<tag>` renders it.
 * @property appLanguage what Settings saves for the app's language; `null` follows the JVM's,
 *   `en-US` by default.
 * @property discover what the Discover clip types and the agent answers.
 */
private enum class PromoLocale(
  val tag: String,
  val appLanguage: String?,
  val discover: PromoDiscoverScript,
) {
  En(
    tag = "en",
    appLanguage = null,
    discover = PromoDiscoverScript(
      query = "Latest Ubuntu Server LTS ISO",
      examples = mapOf(
        "discover_example_blender" to "Open-source 3D modeling app",
        "discover_example_ubuntu" to "Royalty-free ambient music",
      ),
      steps = listOf(
        DiscoveryStep(
          "Understanding",
          "Ubuntu Server, the newest LTS release, as an install image",
        ),
        DiscoveryStep("Searched the web", "5 results from ubuntu.com"),
        DiscoveryStep("Opened releases.ubuntu.com/26.04", "Found 6 download links"),
        DiscoveryStep("Checked 3 links", "Sizes and types match the release page"),
      ),
      title = "Ubuntu Server 26.04 LTS",
      summary = "The newest LTS is 26.04.1. The amd64 installer comes straight from " +
        "releases.ubuntu.com over HTTPS.",
      results = listOf(
        ResultText(
          title = "Ubuntu Server 26.04.1 LTS for 64-bit PCs",
          description = "Official 26.04.1 LTS server installer for 64-bit PCs.",
        ),
        ResultText(
          title = "Ubuntu Server 26.04.1 LTS for 64-bit ARM",
          description = "The same release for 64-bit ARM servers.",
        ),
        ResultText(
          title = "Ubuntu Server 26.04.1 LTS torrent",
          description = "Official torrent of the amd64 installer.",
        ),
      ),
    ),
  ),

  ZhHans(
    tag = "zh-Hans",
    appLanguage = "zh-Hans",
    discover = PromoDiscoverScript(
      query = "帮我找最新的 Ubuntu Server LTS 镜像",
      examples = mapOf(
        "discover_example_blender" to "开源 3D 建模软件",
        "discover_example_ubuntu" to "免版税的背景音乐",
      ),
      steps = listOf(
        DiscoveryStep("理解需求", "要的是 Ubuntu Server 最新 LTS 版的安装镜像"),
        DiscoveryStep("搜索网页", "ubuntu.com 上找到 5 个结果"),
        DiscoveryStep("打开 releases.ubuntu.com/26.04", "找到 6 个下载链接"),
        DiscoveryStep("检查了 3 个链接", "大小和类型都跟发布页对得上"),
      ),
      title = "Ubuntu Server 26.04 LTS",
      summary = "最新的 LTS 是 26.04.1，amd64 镜像直接从 releases.ubuntu.com 走 HTTPS 下载。",
      results = listOf(
        ResultText(
          title = "Ubuntu Server 26.04.1 LTS（64 位 PC）",
          description = "官方 26.04.1 LTS 服务器安装镜像，适合 64 位 PC。",
        ),
        ResultText(
          title = "Ubuntu Server 26.04.1 LTS（64 位 ARM）",
          description = "同一个版本，给 64 位 ARM 服务器用。",
        ),
        ResultText(
          title = "Ubuntu Server 26.04.1 LTS 种子",
          description = "amd64 镜像的官方种子。",
        ),
      ),
    ),
  ),
  ;

  /** Whether this is the video's default language, whose assets the others fall back to. */
  val isDefault: Boolean get() = this == En

  companion object {
    /**
     * The locale of this test JVM: English unless `-PsnapshotLocale` named another. Read from
     * the system properties, which a scenario setting a language leaves alone.
     */
    fun ofRun(): PromoLocale {
      val run = Locale.Builder()
        .setLanguage(System.getProperty("user.language").orEmpty())
        .setScript(System.getProperty("user.script").orEmpty())
        .build()
      return entries.firstOrNull { locale ->
        val wanted = Locale.forLanguageTag(locale.tag)
        wanted.language == run.language && (wanted.script.isEmpty() || wanted.script == run.script)
      } ?: error(
        "No promo locale for -PsnapshotLocale=${run.toLanguageTag()}; " +
          "use one of ${entries.joinToString { it.tag }}",
      )
    }
  }
}

/**
 * What the Discover clip shows in one language: the request typed and sent, the agent's steps,
 * its summary and its results.
 *
 * @property query typed into the composer, a character at a time, between [TYPE_FROM] and
 *   [TYPED_AT], whatever its length.
 * @property examples the page's example searches that name real products, by string key, in
 *   brand-free words; the third, the app's own, shows as it is.
 * @property steps the agent's steps, one at each of the first [CueFrames], title and detail.
 * @property title the session's name, as the agent gives it (shown in the history).
 * @property summary the agent's answer above its results.
 * @property results the title and description of each of [PromoResults], in order.
 */
private class PromoDiscoverScript(
  val query: String,
  val examples: Map<String, String>,
  val steps: List<DiscoveryStep>,
  val title: String,
  val summary: String,
  val results: List<ResultText>,
) {
  init {
    require(steps.size == CueFrames.size - 1) { "One step per cue, the last cue answers" }
    require(results.size == PromoResults.size) { "A title and description for each result" }
  }

  /** The agent's answer: [PromoResults] with this language's words. */
  val candidates: List<AiCandidate>
    get() = PromoResults.zip(results) { result, text ->
      result.copy(title = text.title, description = text.description)
    }
}

/** A Discover result's words in one language. */
private class ResultText(val title: String, val description: String)

/** The Discover clip's results, whose words each [PromoDiscoverScript] gives. */
private val PromoResults = listOf(
  AiCandidate(
    url = "https://releases.ubuntu.com/26.04/ubuntu-26.04.1-live-server-amd64.iso",
    title = "",
    fileName = "ubuntu-26.04.1-live-server-amd64.iso",
    fileSize = (3.2 * GIB).roundToLong(),
    sourceUrl = "https://releases.ubuntu.com/26.04/",
    confidence = 0.96f,
    description = "",
  ),
  AiCandidate(
    url = "https://cdimage.ubuntu.com/releases/26.04/release/" +
      "ubuntu-26.04.1-live-server-arm64.iso",
    title = "",
    fileName = "ubuntu-26.04.1-live-server-arm64.iso",
    fileSize = (2.9 * GIB).roundToLong(),
    sourceUrl = "https://cdimage.ubuntu.com/releases/26.04/release/",
    confidence = 0.91f,
    description = "",
  ),
  AiCandidate(
    url = "https://releases.ubuntu.com/26.04/ubuntu-26.04.1-live-server-amd64.iso.torrent",
    title = "",
    fileName = "ubuntu-26.04.1-live-server-amd64.iso.torrent",
    sourceUrl = "https://releases.ubuntu.com/26.04/",
    confidence = 0.86f,
    description = "",
  ),
)

/** The languages of the `lang-*` stills, by the tag Settings saves. */
private val PromoLanguages =
  listOf("ja", "zh-Hans", "zh-Hant", "de", "ko", "es", "pt-BR", "fr", "ru")

// ═════════════════════════════════════════════════════════════════════════════════════════════
// Bounds: where the parts of a screen the video frames are, found through the screen's
// semantics, so that a scene's camera follows the UI when its layout changes. Each is written
// to the asset's `<asset>.json` and from there to the manifest, as fractions of the image.
// Anchors are the app's own strings, loaded in the run's language, or the data the scenario
// shows, never English text; `<asset>.semantics.txt` lists what there is to anchor on.
// ═════════════════════════════════════════════════════════════════════════════════════════════

/**
 * A part of a screen whose rectangle the manifest records as `bounds.<key>`.
 *
 * @property key its name in the manifest.
 * @property find where it is on the screen, in pixels.
 */
private class Bound(val key: String, val find: suspend Probe.() -> Rect)

/** The bounds of each screen. */
private object AssetBounds {
  /**
   * The desktop clips and the accent stills: the window on Downloads with the ISO's inspector on
   * its Connections tab.
   */
  val Desktop = listOf(
    // The inspector beside the table, its divider included.
    Bound("inspector") { inspectorPane().box },
    // Its tab row: Overview, Connections and Activity.
    Bound("inspectorTabs") { inspectorTabs() },
    // The Connections tab, from its header to its caption.
    Bound("connections") { connectionsTab() },
    // The tab's header: "7 active · 87.5 MB/s" and the connections stepper.
    Bound("connectionsHeader") {
      val tab = connectionsTab()
      Rect(tab.left, tab.top, tab.right, enclosing(stepper()).bottom)
    },
    // The lanes, a row for each running connection.
    Bound("lanes") { enclosing(lanes()) },
    // The tab row down to the bottom of the Connections tab, as S03 frames it.
    Bound("connectionsBlock") { union(inspectorTabs(), connectionsTab()) },
  )

  /** The Discover clip, measured on its last frame: the results in and the best one picked. */
  val Discover = listOf(
    // The follow-up composer at the bottom, where the first one was.
    Bound("composer") { surface(textField()) },
    // The request in its bubble, at the top of the thread.
    Bound("request") { surface(text(Promo.discover.query)) },
    // The agent's steps, in a row.
    Bound("steps") {
      val steps = Promo.discover.steps
      enclosing(text(steps.first().title), text(steps.last().title))
    },
    // The agent's answer above the results.
    Bound("summary") { text(Promo.discover.summary).box },
    // The results, from their "3 downloads" header to the last.
    Bound("results") {
      val count = PromoResults.size
      val header = text(Res.plurals.discover_results.text(count, count).load())
      // The checkbox that selects them all, beside the header, says what it would do.
      val toggle = described(
        Res.string.discover_select_all.text().load(),
        Res.string.discover_clear_selection.text().load(),
      )
      union(listOf(header.box, toggle.box) + resultRows().map { it.box })
    },
    // The top result's row, picked.
    Bound("topResult") { resultRows().first().box },
    // The composer's chip with the model Discover searches with, "qwen3".
    Bound("modelChip") { text(modelChipLabel(controller)).control().box },
    // The page's title, "Discover"; the sidebar's entry reads the same, so it is the one
    // furthest right. S08 keeps it inside the frame.
    Bound("pageTitle") {
      texts(Res.string.discover_title.text().load()).maxBy { it.box.center.x }.box
    },
  )

  /** The add sheet over the window, choosing the files of a torrent. */
  val IntakeTorrent = listOf(
    Bound("sheet") { surface(text(Res.string.intake_title_add.text().load()), lastTorrentFile()) },
    Bound("files") { enclosing(text(TorrentFiles.first()), lastTorrentFile()) },
  )

  /** The command palette over the window. */
  val Palette = listOf(
    Bound("palette") { surface(described(Res.string.command_palette.text().load())) },
  )

  /** The Downloads table, nothing inspected: S05's Scheduling and Smart queue tiles. */
  val Downloads = listOf(
    // The WAITING group: its header and its rows.
    Bound("waitingGroup") {
      val rows = WaitingDownloads.map { text(it).control().box }
      // The header right above the rows; the Waiting tab above the table reads the same.
      val title = Res.string.group_title_waiting.text().load()
      val header = texts(title, ignoreCase = true)
        .map { it.control().box }
        .filter { it.bottom <= rows.first().top }
        .maxBy { it.bottom }
      union(listOf(header) + rows)
    },
    // The download that starts tonight: "Starts today at 23:00".
    Bound("scheduledRow") { text(WaitingDownloads.last()).control().box },
  )

  /** The ISO inspected on its Overview tab: S05's Priorities tile. */
  val InspectorOverview = listOf(
    // "Priority" and its Low, Normal, High and Urgent.
    Bound("priority") {
      val label = text(Res.string.inspector_control_priority.text().load())
      val choices = enclosing(
        text(Res.string.priority_low.text().load()),
        text(Res.string.priority_urgent.text().load()),
      )
      union(label.box, choices)
    },
  )

  /** Settings → Speed: S05's Speed modes tile. */
  val SpeedSettings = listOf(
    // The Speed mode card: Full speed, Slow lane or Auto.
    Bound("speedMode") { surface(text(Res.string.settings_speed_mode.text().load())) },
  )

  /** Settings → Network: S05's Several networks tile. */
  val NetworkSettings = listOf(
    // The card of networks to spread downloads across.
    Bound("networks") { surface(text(Res.string.settings_network_spread.text().load())) },
  )
}

// Anchors of the bounds above.

/** The inspector's pane: what holds its resize handle, the divider at its left edge. */
private suspend fun Probe.inspectorPane(): SemanticsNode {
  val handle = described(Res.string.downloads_inspector_resize.text().load())
  return checkNotNull(handle.parent) { "The inspector's resize handle stands alone" }
}

/** The inspector's tab row. */
private suspend fun Probe.inspectorTabs(): Rect =
  enclosing(text(InspectorTab.Overview.title.load()), text(InspectorTab.Activity.title.load()))

/** The Connections tab's content. */
private suspend fun Probe.connectionsTab(): Rect = enclosing(stepper() + connectionsCaption())

/** The note at the bottom of the Connections tab: a new count re-splits the bytes live. */
private suspend fun Probe.connectionsCaption(): SemanticsNode =
  text(Res.string.inspector_connections_live.text().load())

/** The Connections tab's stepper: its − and + buttons. */
private suspend fun Probe.stepper(): List<SemanticsNode> = listOf(
  described(StepperCount.Connections.fewerLabel.load()),
  described(StepperCount.Connections.moreLabel.load()),
)

/** The Connections lanes, each described as "Connection 3, 1.2–1.6 GB, 12.8 MB/s". */
private suspend fun Probe.lanes(): List<SemanticsNode> =
  describedLike(Res.string.inspector_lane_description, args = 3)

/** The rows of the Discover results, top first: each a checkbox that picks it. */
private fun Probe.resultRows(): List<SemanticsNode> =
  PromoResults.mapNotNull { it.fileName }.map { name -> text(name).control() }

/** The last file the add sheet lists. */
private fun Probe.lastTorrentFile(): SemanticsNode = text(TorrentSkipped.last())

// ═════════════════════════════════════════════════════════════════════════════════════════════
// Scenarios
// ═════════════════════════════════════════════════════════════════════════════════════════════

/** What a settings still runs on: the laptop, shared, with speed modes. */
private val SettingsDevice = PromoDevice.SettingsDesktop

/**
 * The laptop's window on Studio in [theme]: every download moving and the ISO's lanes on the
 * inspector's Connections tab, [count] frames from [offset] frames into the dark clip's
 * timeline, so that frame k of this clip shows what frame k + [offset] of the dark one does (and
 * no clip with a [count] of 0); its record says so with [syncedTo]. Then, for each of
 * [accentsAt], frames of the dark clip's timeline, the window turns to the next accent, a still
 * of each at that frame.
 */
private fun desktopClip(
  theme: SnapshotTheme,
  count: Int,
  offset: Int = 0,
  accentsAt: List<Int> = emptyList(),
  syncedTo: SyncedTo? = DARK_CLIP.takeIf { offset != 0 }?.let { SyncedTo(it, offset = offset) },
) {
  val clip = themed("desktop", theme)
  if (count > 0) clearClips(clip)
  withEnvironment(
    create = {
      PromoEnvironment(
        PromoDevice.Desktop,
        theme,
        IdleDiscovery,
        inspectorWidth = CLIP_INSPECTOR_WIDTH,
      )
    },
  ) { env ->
    val state = env.controller.state
    val traffic = Traffic(env)
    shoot(ClipWindow, CLIP_SCALE, { MacWindow(env.controller) }) {
      warmUp(env, traffic) {
        state.inspect(env.studioKey(ShowcaseData.ISO_ID))
        scene.settle()
        clickTab(InspectorTab.Connections.title.load())
        scene.rest(ClipWindow)
      }
      // The clip starts on the first accent.
      env.controller.appSettings.saveAccent(KetchAccent.Indigo)
      val timeline = timeline()
      timeline.skip(PREROLL_FRAMES + offset, traffic::advance)
      if (count > 0) {
        timeline.record(clip, count, LARGE_CLIP_FORMAT, step = traffic::advance)
        describe(
          AssetRecord(
            name = clip,
            kind = AssetKind.Clip,
            frames = count,
            format = LARGE_CLIP_FORMAT,
            syncedTo = syncedTo,
          ),
          AssetBounds.Desktop,
          env.controller,
        )
      }
      for ((accent, at) in KetchAccent.entries.zip(accentsAt)) {
        val target = PREROLL_FRAMES + at
        val settle = if (accent == KetchAccent.Indigo) 0 else ACCENT_SETTLE_FRAMES
        check(target - timeline.frame >= settle) { "No time for $accent to settle by $at" }
        if (accent != KetchAccent.Indigo) env.controller.appSettings.saveAccent(accent)
        timeline.skip(target - timeline.frame, traffic::advance)
        val name = themed("desktop-accent-${accent.name.lowercase()}", theme)
        timeline.still(name, traffic::advance)
        describe(
          AssetRecord(name, AssetKind.Still, syncedTo = SyncedTo(DARK_CLIP, frame = at)),
          AssetBounds.Desktop,
          env.controller,
        )
        appendStats(clip, "$name=frame $at of the dark clip's timeline")
      }
      timeline.finish()
    }
  }
}

/**
 * Discover on the laptop: the locale's request typed into the composer and sent, the agent's
 * steps one by one, its results, then the best one picked, which brings up the add bar.
 */
private fun discoverClip(theme: SnapshotTheme) {
  val cue = MutableStateFlow(0)
  val clip = themed("discover", theme)
  val script = Promo.discover
  clearClips(clip)
  withEnvironment(
    // Without the history beside it, the chat keeps its place as the search starts.
    create = {
      PromoEnvironment(
        PromoDevice.Desktop,
        theme,
        ScriptedDiscovery(cue, script),
        discoverHistory = false,
      )
    },
  ) { env ->
    val state = env.controller.state
    val discover = state.aiDiscover
    val traffic = Traffic(env)
    var focus: FocusManager? = null
    val content: @Composable () -> Unit = {
      val manager = LocalFocusManager.current
      SideEffect { focus = manager }
      PromoStrings(script.examples) { MacWindow(env.controller) }
    }
    shoot(DesktopWindow, DISCOVER_SCALE, content) {
      warmUp(env, traffic) {
        state.runInShell(KetchCommands.Discover)
        scene.settle()
        scene.rest(DesktopWindow)
      }
      val timeline = timeline()
      timeline.skip(PREROLL_FRAMES, traffic::advance)
      val start = timeline.frame
      val settle = { global: Int -> global - start in DiscoverSettleFrames }
      timeline.record(clip, DISCOVER_FRAMES, LARGE_CLIP_FORMAT, settle) { global, time ->
        traffic.advance(global, time)
        val frame = global - start
        val typed = typedAt(script.query, frame)
        if (typed != null && typed != discover.draft.text.text) {
          discover.draft.text = TextFieldValue(typed, TextRange(typed.length))
        }
        if (frame == SEND_AT) discover.send()
        // Sent, the composer has nothing to type; without its caret blinking, nothing moves
        // while the results wait to be picked.
        if (frame == BLUR_AT) focus?.clearFocus(force = true)
        cue.value = CueFrames.count { it <= frame }
        if (frame == SELECT_AT) discover.selected = setOf(script.candidates.first().url)
      }
      describe(
        AssetRecord(
          name = clip,
          kind = AssetKind.Clip,
          frames = DISCOVER_FRAMES,
          format = LARGE_CLIP_FORMAT,
          events = DiscoverEvents,
        ),
        AssetBounds.Discover,
        env.controller,
      )
      timeline.finish()
    }
  }
}

/**
 * How much of [query] shows at frame [frame] of the Discover clip, while it is typed: a
 * character more every so often, evenly, from one at [TYPE_FROM] to all of them at [TYPED_AT],
 * so a query of any length is typed over the same frames; `null` outside those frames.
 */
private fun typedAt(query: String, frame: Int): String? {
  if (frame !in TYPE_FROM..TYPED_AT) return null
  val characters = query.codePointCount(0, query.length)
  val count = 1 + (frame - TYPE_FROM) * (characters - 1) / (TYPED_AT - TYPE_FROM)
  return query.substring(0, query.offsetByCodePoints(0, count))
}

/**
 * [content] with the Discover page's example searches that name real products in favour of
 * [examples], brand-free ones, by string key; every other resource reads as it is.
 */
@OptIn(InternalResourceApi::class, ExperimentalResourceApi::class)
@Composable
private fun PromoStrings(examples: Map<String, String>, content: @Composable () -> Unit) {
  val base = LocalResourceReader.current
  val reader = remember(base, examples) { PromoResourceReader(base, examples) }
  CompositionLocalProvider(LocalResourceReader provides reader, content = content)
}

/** [base], with [replacements] in place of the strings of their keys, in every language. */
@OptIn(InternalResourceApi::class, ExperimentalResourceApi::class)
private class PromoResourceReader(
  private val base: ResourceReader,
  private val replacements: Map<String, String>,
) : ResourceReader {
  override suspend fun read(path: String): ByteArray = base.read(path)

  override suspend fun readPart(path: String, offset: Long, size: Long): ByteArray {
    val part = base.readPart(path, offset, size)
    // A string resource is a line "string|<key>|<Base64 of its text>".
    val line = part.decodeToString()
    val replacement = replacements.entries.firstOrNull { (key, _) ->
      line.startsWith("string|$key|")
    } ?: return part
    val text = Base64.getEncoder().encodeToString(replacement.value.encodeToByteArray())
    return "string|${replacement.key}|$text".encodeToByteArray()
  }

  override fun getUri(path: String): String = base.getUri(path)
}

/**
 * A phone reaching Studio, in the film's theme: on Android its Devices page, each card's speed
 * drawing on; on iOS the ISO's inspector sheet held up on its Connections tab. Then frames it in
 * a phone, as clip [base] in that theme.
 */
private fun phoneClip(kind: PhoneKind, base: String) {
  val theme = Film
  val clip = themed(base, theme)
  // Discover runs on Android; iOS offers none.
  val ai = IdleDiscovery.takeIf { kind == PhoneKind.Android }
  clearClips(clip, "$clip-screen")
  withEnvironment(create = { PromoEnvironment(PromoDevice.Phone, theme, ai) }) { env ->
    val state = env.controller.state
    val traffic = Traffic(env)
    shoot(SnapshotSize.Phone, PHONE_SCALE, { PromoPhoneApp(env.controller, kind) }) {
      warmUp(env, traffic) {
        when (kind) {
          PhoneKind.Android -> {
            state.runInShell(KetchCommands.Devices)
            scene.settle()
            // LAYOUT: the second card, Home server, runs under the bottom bar. Scrolled past the
            // page's sentence to the cards, its speed and speed mode clear the bar in every
            // language (CJK lines are taller, which pushes the card lower).
            scrollPastFirstLine()
          }
          PhoneKind.Ios -> {
            state.inspect(env.studioKey(ShowcaseData.ISO_ID))
            scene.settle()
            clickTab(InspectorTab.Connections.title.load())
            // Held up far enough for every lane, the list still showing above it.
            scene.drag(
              SheetGrip.first,
              SheetGrip.second,
              SheetGrip.first,
              SheetHeld,
              release = false,
            )
          }
        }
      }
      val timeline = timeline()
      timeline.skip(PREROLL_FRAMES, traffic::advance)
      timeline.record("$clip-screen", PHONE_FRAMES, FrameFormat.Png, step = traffic::advance)
      timeline.finish()
    }
  }
  framePhone("$clip-screen", clip, kind, theme)
}

/**
 * Renders the laptop's window, or [device]'s, in the film's theme and in [language] or else the
 * run's, with the [table] columns, after [setup] and as much live speed as the clips have, at
 * [scale] pixels per dp to `stills/<name>.png` in [PromoDir], [name] being [base] in that theme,
 * and records it with its [bounds].
 */
private fun desktopStill(
  base: String,
  device: PromoDevice = PromoDevice.Desktop,
  size: SnapshotSize = DesktopWindow,
  scale: Float = STILL_SCALE,
  language: String? = Promo.appLanguage,
  table: Map<String, String> = emptyMap(),
  bounds: List<Bound> = emptyList(),
  setup: suspend Shot.(PromoEnvironment) -> Unit,
) {
  val name = themed(base)
  val locale = Locale.getDefault()
  // The web app has no Discover.
  val ai = IdleDiscovery.takeIf { device != PromoDevice.Web }
  try {
    withEnvironment(
      create = {
        PromoEnvironment(device, Film, ai, language = language, table = table)
      },
    ) { env ->
      val traffic = Traffic(env)
      val content: @Composable () -> Unit = if (device == PromoDevice.Web) {
        { App(env.controller) }
      } else {
        { MacWindow(env.controller) }
      }
      shoot(size, scale, content) {
        warmUp(env, traffic) {
          setup(env)
          scene.settle()
          scene.rest(size)
        }
        val timeline = timeline()
        timeline.skip(PREROLL_FRAMES, traffic::advance)
        timeline.still(name, traffic::advance)
        describe(AssetRecord(name, AssetKind.Still), bounds, env.controller)
        timeline.finish()
      }
    }
  } finally {
    // A language sets the JVM's default locale, which every later scenario would keep.
    Locale.setDefault(locale)
  }
}

/**
 * Gets a scene ready to record: lets it settle, speeds Studio's downloads up and rewinds them so
 * that they reach their places as the recording starts, plays the speed history the sparklines
 * draw, then runs [setup] while the downloads stand still.
 */
private suspend fun Shot.warmUp(
  env: PromoEnvironment,
  traffic: Traffic,
  setup: suspend Shot.() -> Unit,
) {
  scene.settle()
  traffic.boost()
  traffic.rewind(PREROLL_FRAMES)
  env.playSpeedHistory(SPEED_HISTORY_SECONDS)
  setup()
}

// ═════════════════════════════════════════════════════════════════════════════════════════════
// Asset records: `<asset>.json` and `<asset>.semantics.txt`
// ═════════════════════════════════════════════════════════════════════════════════════════════

/** What an asset is. */
private enum class AssetKind(val id: String) {
  /** A folder of frames. */
  Clip("clip"),

  /** One PNG. */
  Still("still"),
}

/**
 * A frame of another clip an asset matches, so a scene can cut between them.
 *
 * @property clip the other clip.
 * @property offset for a clip: its frame k shows what [clip]'s frame k + offset does.
 * @property frame for a still: the frame of [clip] it shows.
 */
private class SyncedTo(val clip: String, val offset: Int? = null, val frame: Int? = null)

/**
 * What `<name>.json` says about an asset besides its pixels, for
 * `art/promo/scripts/assets/sync.mjs` to put in the locale's manifest.
 *
 * @property frames a clip's number of frames, at [FPS].
 * @property format how a clip's frames are encoded.
 * @property alpha whether the frames are transparent around their content, as framed phones are.
 * @property events frames of a clip where something happens, by name.
 * @property syncedTo the frame of another clip this asset matches.
 */
private class AssetRecord(
  val name: String,
  val kind: AssetKind,
  val frames: Int? = null,
  val format: FrameFormat = FrameFormat.Png,
  val alpha: Boolean = false,
  val events: Map<String, Int> = emptyMap(),
  val syncedTo: SyncedTo? = null,
)

/**
 * Records [record], whose last frame the scene just rendered, of the app [controller] shows:
 * measures [bounds] on that frame, writes every semantics node of it to `<name>.semantics.txt`
 * and the record with the bounds and the scene's density to `<name>.json`. Fails, once both are
 * written, if a bound cannot be found.
 */
private suspend fun Shot.describe(
  record: AssetRecord,
  bounds: List<Bound>,
  controller: AppController,
) {
  val probe = Probe(compose, controller)
  val found = linkedMapOf<String, Rect>()
  val missing = linkedMapOf<String, Throwable>()
  for (bound in bounds) {
    runCatching { bound.find(probe) }
      .onSuccess { found[bound.key] = it }
      .onFailure { missing[bound.key] = it }
  }
  File(PromoDir, "${record.name}.semantics.txt").writeText(probe.dump(record.name, width, height))
  writeRecord(record, width, height, scale, found)
  if (missing.isNotEmpty()) {
    val first = missing.values.first()
    throw AssertionError(
      "Couldn't find ${missing.keys.joinToString()} on ${record.name}; see " +
        "${record.name}.semantics.txt",
      first,
    )
  }
}

/**
 * Writes `<name>.json` for [record], a [width] × [height] px image at [density] pixels per dp
 * with [bounds] in pixels.
 */
private fun writeRecord(
  record: AssetRecord,
  width: Int,
  height: Int,
  density: Float,
  bounds: Map<String, Rect>,
) {
  val json = buildJsonObject {
    put("name", record.name)
    put("kind", record.kind.id)
    put("locale", Promo.tag)
    put("theme", Film.id)
    put("width", width)
    put("height", height)
    put("density", density)
    if (record.kind == AssetKind.Clip) {
      put("frames", checkNotNull(record.frames) { "A clip without frames" })
      put("fps", FPS)
      put("ext", record.format.extension)
      put("alpha", record.alpha)
    }
    if (bounds.isNotEmpty()) {
      putJsonObject("bounds") {
        for ((key, rect) in bounds) put(key, rect.normalized(width, height))
      }
    }
    if (record.events.isNotEmpty()) {
      putJsonObject("events") { for ((event, frame) in record.events) put(event, frame) }
    }
    record.syncedTo?.let { synced ->
      putJsonObject("syncedTo") {
        put("clip", synced.clip)
        synced.offset?.let { put("offset", it) }
        synced.frame?.let { put("frame", it) }
      }
    }
  }
  val text = RecordJson.encodeToString(JsonObject.serializer(), json)
  File(PromoDir, "${record.name}.json").writeText("$text\n")
}

private val RecordJson = Json { prettyPrint = true }

/**
 * This rectangle, in pixels of a [width] × [height] image, as fractions of it to four places,
 * clipped to the image.
 */
private fun Rect.normalized(width: Int, height: Int): JsonObject {
  val clipped = intersect(Rect(0f, 0f, width.toFloat(), height.toFloat()))
  fun fraction(value: Float, of: Int) = JsonPrimitive(
    BigDecimal(value.toDouble() / of).setScale(BOUNDS_PLACES, RoundingMode.HALF_UP),
  )
  return buildJsonObject {
    put("x", fraction(clipped.left, width))
    put("y", fraction(clipped.top, height))
    put("w", fraction(clipped.width, width))
    put("h", fraction(clipped.height, height))
  }
}

/**
 * Writes the record of clip [name], [count] PNG frames of [width] × [height] px at [density]
 * pixels per dp, with no bounds.
 */
private fun writeClipRecord(
  name: String,
  count: Int,
  width: Int,
  height: Int,
  density: Float,
  alpha: Boolean,
) {
  val record = AssetRecord(name, AssetKind.Clip, frames = count, alpha = alpha)
  writeRecord(record, width, height, density, emptyMap())
}

/**
 * Finds the parts of a rendered screen through its semantics: the text it shows, what it
 * describes to a screen reader and the layouts around them, in pixels of the scene.
 *
 * @property controller the app on the screen, for text the app makes up from its state.
 */
private class Probe(scene: ImageComposeScene, val controller: AppController) {
  /** Every semantics node, unmerged, parents before their children. */
  val nodes: List<SemanticsNode> = scene.nodes()

  private val patterns = HashMap<StringResource, Regex>()

  /**
   * The first node that reads [text], as a text or a text field's value: exactly, or with
   * [ignoreCase] in any case, as a label the app shows in capitals.
   */
  fun text(text: String, ignoreCase: Boolean = false): SemanticsNode =
    texts(text, ignoreCase).first()

  /** Every node that reads [text], as [text] matches them, in the order they are composed. */
  fun texts(text: String, ignoreCase: Boolean = false): List<SemanticsNode> =
    nodes.filter { it.reads(text, ignoreCase) }
      .ifEmpty { throw NoSuchElementException("Nothing reads $text") }

  /** The first node described to a screen reader as one of [descriptions]. */
  fun described(vararg descriptions: String): SemanticsNode = nodes.firstOrNull { node ->
    node.config.getOrNull(SemanticsProperties.ContentDescription)
      ?.any { it in descriptions } == true
  } ?: throw NoSuchElementException("Nothing is described as ${descriptions.joinToString()}")

  /**
   * Every node described as [resource], with its [args] arguments filled with anything, such as
   * "Connection 3, 1.2–1.6 GB, 12.8 MB/s", in the order they are composed.
   */
  suspend fun describedLike(resource: StringResource, args: Int): List<SemanticsNode> {
    val pattern = patterns.getOrPut(resource) { pattern(resource, args) }
    return nodes.filter { node ->
      node.config.getOrNull(SemanticsProperties.ContentDescription)
        ?.any { pattern.matches(it) } == true
    }.ifEmpty { throw NoSuchElementException("Nothing is described like $pattern") }
  }

  /** The first text field. */
  fun textField(): SemanticsNode =
    nodes.firstOrNull { SemanticsProperties.EditableText in it.config }
      ?: throw NoSuchElementException("No text field")

  /**
   * The innermost layout that holds every one of [nodes], as its content box, inside its
   * padding, such as a column of rows.
   */
  fun enclosing(vararg nodes: SemanticsNode): Rect = enclosing(nodes.toList())

  /** [enclosing] for a list. */
  fun enclosing(nodes: List<SemanticsNode>): Rect = commonLayout(nodes).coordinates.boundsInWindow()

  /**
   * The surface every one of [nodes] is drawn on: the background of the innermost layout around
   * them all that paints one, such as a card or a sheet.
   */
  fun surface(vararg nodes: SemanticsNode): Rect =
    generateSequence(commonLayout(nodes.toList())) { it.parentInfo }
      .firstNotNullOfOrNull { it.background() }
      ?: throw NoSuchElementException("No background around ${nodes.size} nodes")

  /** The smallest rectangle around all of [rects]. */
  fun union(vararg rects: Rect): Rect = union(rects.toList())

  /** [union] for a list. */
  fun union(rects: List<Rect>): Rect = rects.reduce { all, rect ->
    Rect(
      minOf(all.left, rect.left),
      minOf(all.top, rect.top),
      maxOf(all.right, rect.right),
      maxOf(all.bottom, rect.bottom),
    )
  }

  /** Every node, indented by depth, with what it reads, its role and actions and its box. */
  fun dump(name: String, width: Int, height: Int): String = buildString {
    appendLine(
      "# $name, $width × $height px, ${Promo.tag}, ${Film.id} film: semantics nodes, unmerged",
    )
    appendLine("# indent · \"text\" · [description] · role · actions · @ left,top width×height px")
    for (node in nodes) {
      var depth = 0
      var parent = node.parent
      while (parent != null) {
        depth++
        parent = parent.parent
      }
      append("  ".repeat(depth))
      node.config.getOrNull(SemanticsProperties.Text)?.let { texts ->
        append(texts.joinToString(" ") { "\"$it\"" }).append(' ')
      }
      node.config.getOrNull(SemanticsProperties.EditableText)?.let { append("field=\"$it\" ") }
      node.config.getOrNull(SemanticsProperties.ContentDescription)?.let { descriptions ->
        append(descriptions.joinToString(" ") { "[$it]" }).append(' ')
      }
      node.config.getOrNull(SemanticsProperties.Role)?.let { append("role=$it ") }
      if (SemanticsActions.OnClick in node.config) append("click ")
      val box = node.box
      append("@ ${box.left.roundToInt()},${box.top.roundToInt()} ")
      appendLine("${box.width.roundToInt()}×${box.height.roundToInt()}")
    }
  }

  /** The text of [resource] in the screen's language, its [args] arguments matching anything. */
  private suspend fun pattern(resource: StringResource, args: Int): Regex {
    val markers = List(args) { "\u0000$it\u0000" }
    val text = resource.text(*markers.toTypedArray()).load()
    val quoted = markers.fold(Regex.escape(text)) { escaped, marker ->
      escaped.replace(marker, "\\E.+?\\Q")
    }
    return Regex("^$quoted$")
  }

  private fun commonLayout(nodes: List<SemanticsNode>): LayoutInfo {
    require(nodes.isNotEmpty()) { "No nodes to enclose" }
    val chains = nodes.map { node -> generateSequence(node.layoutInfo) { it.parentInfo }.toList() }
    val others = chains.drop(1).map { it.toSet() }
    return chains.first().firstOrNull { layout -> others.all { layout in it } }
      ?: throw NoSuchElementException("The nodes are in different layers")
  }
}

/** Where this node shows, clipped by what holds it, in pixels of the scene. */
private val SemanticsNode.box: Rect get() = boundsInWindow

/** Whether this node reads [text], in any case with [ignoreCase]. */
private fun SemanticsNode.reads(text: String, ignoreCase: Boolean = false): Boolean {
  val texts = config.getOrNull(SemanticsProperties.Text)?.map { it.text }.orEmpty() +
    listOfNotNull(config.getOrNull(SemanticsProperties.EditableText)?.text)
  return texts.any { it.equals(text, ignoreCase) }
}

/** This node's control: itself or its nearest ancestor that can be clicked, such as a button. */
private fun SemanticsNode.control(): SemanticsNode =
  generateSequence(this) { it.parent }.firstOrNull { SemanticsActions.OnClick in it.config }
    ?: throw NoSuchElementException("Nothing clickable holds node $id")

/**
 * Where this layout paints its background: the box of the first background among its
 * modifiers. Compose has no public way to ask what a modifier paints; the class of
 * `Modifier.background`'s element is the steadiest sign of one.
 */
private fun LayoutInfo.background(): Rect? = getModifierInfo()
  .firstOrNull { it.modifier.javaClass.simpleName == BACKGROUND_ELEMENT }
  ?.coordinates
  ?.boundsInWindow()

private const val BACKGROUND_ELEMENT = "BackgroundElement"
private const val BOUNDS_PLACES = 4

// ═════════════════════════════════════════════════════════════════════════════════════════════
// Recording
// ═════════════════════════════════════════════════════════════════════════════════════════════

/**
 * A scene being shot: its interactions before the timeline starts, and its timeline.
 *
 * @property width the scene's width in pixels.
 * @property height the scene's height in pixels.
 * @property scale the scene's pixels per dp.
 */
private class Shot(
  val compose: ImageComposeScene,
  val scene: SnapshotScene,
  val width: Int,
  val height: Int,
  val scale: Float,
) {
  /** The scene's timeline from now on; once it starts, only the timeline renders. */
  fun timeline(): Timeline = Timeline(compose)

  /**
   * Clicks the tab titled [title], such as the inspector's Connections, wherever the layout and
   * the language put it: a text that reads the same elsewhere, as a field's label, is no tab.
   */
  suspend fun clickTab(title: String) {
    val tab = compose.nodes().firstNotNullOfOrNull { node ->
      if (!node.reads(title)) return@firstNotNullOfOrNull null
      generateSequence(node) { it.parent }.firstOrNull {
        it.config.getOrNull(SemanticsProperties.Role) == Role.Tab
      }
    } ?: throw NoSuchElementException("No tab reads $title")
    val center = tab.box.center
    scene.click((center.x / scale).dp, (center.y / scale).dp)
  }

  /**
   * Scrolls the page's vertical list until its first line of text has just left its top, as a
   * phone's Devices page scrolled past its sentence to the cards, with the phone's top bar in
   * place. Measured, not a number of wheel ticks, so the distance follows the line's height in
   * every language.
   *
   * The phone's top bar takes a scroll down first, sliding away, and the list only moves once it
   * has gone; a scroll back up brings it back first. So this scrolls until the list itself has
   * moved that far, then back up by what the bar took, which leaves the list where it is: the
   * page as a finger leaves it after scrolling down and nudging back.
   */
  suspend fun scrollPastFirstLine() {
    val nodes = compose.nodes()
    val list = nodes.firstOrNull { SemanticsProperties.VerticalScrollAxisRange in it.config }
      ?: throw NoSuchElementException("Nothing scrolls vertically")
    val line = nodes.firstOrNull { node ->
      SemanticsProperties.Text in node.config &&
        generateSequence(node.parent) { it.parent }.any { it.id == list.id }
    } ?: throw NoSuchElementException("The list has no text")
    val scrollBy = checkNotNull(list.config.getOrNull(SemanticsActions.ScrollBy)?.action) {
      "The list cannot be scrolled by an amount"
    }
    val scrolled = list.config[SemanticsProperties.VerticalScrollAxisRange].value
    val target = line.box.bottom - list.box.top
    var barTook = 0f
    repeat(MAX_SCROLLS) {
      val before = scrolled()
      if (before >= target - 0.5f) {
        if (barTook > 0f) {
          scrollBy(0f, -barTook)
          scene.settle()
        }
        check(abs(scrolled() - target) < 1f) { "The list moved back with the top bar" }
        return
      }
      scrollBy(0f, target - before)
      scene.settle()
      barTook += target - before - (scrolled() - before)
    }
    error("The list did not scroll ${target}px in $MAX_SCROLLS scrolls")
  }
}

/** Composes [content] in a [size] window at [scale] pixels per dp and runs [block] on it. */
private fun <T> shoot(
  size: SnapshotSize,
  scale: Float,
  content: @Composable () -> Unit,
  block: suspend Shot.() -> T,
): T {
  val width = (size.width.value * scale).roundToInt()
  val height = (size.height.value * scale).roundToInt()
  return withScene(width = width, height = height, density = Density(scale), content = content) {
    Shot(this, SnapshotScene(this, scale), width, height, scale).block()
  }
}

/**
 * Moves the data on to frame `frame`, which shows `time` after the timeline started, before
 * the clock reaches that time.
 */
private typealias FrameStep = (frame: Int, time: Duration) -> Unit

/**
 * Renders a scene frame after frame on the virtual time of [SnapshotClock]. Frame n shows
 * exactly n / [FPS] s after the timeline starts: a step moves the data on to that time, the
 * clock moves on to it, running the app's timers that fall due on the way, and the frame renders
 * at it on Compose's clock. Nothing waits for real time, so a slow frame changes nothing.
 */
private class Timeline(private val scene: ImageComposeScene) {
  private val base = SnapshotClock.nanos.nanoseconds
  private var rendering = Duration.ZERO
  private var rendered = 0
  private var slowest = Duration.ZERO

  // Frames are encoded on other threads while the next ones render.
  private val writer = FrameWriter()

  /** Index of the next frame. */
  var frame = 0
    private set

  /**
   * Steps the data with [step], lets the clock reach the frame's time and renders the frame;
   * `null` without [render], when only the data and the app move on. With [settle], it renders
   * once more half a frame earlier and lets the effects that sets off run, so what the app moves
   * a frame after a change, such as a list making room for a new row, has landed.
   */
  suspend fun next(settle: Boolean = false, render: Boolean = true, step: FrameStep): Image? {
    val due = frameTime(frame)
    step(frame, due)
    val wait = base + due - SnapshotClock.nanos.nanoseconds
    // Either way the collectors of the flows step changed run before the frame renders.
    if (wait.isPositive()) delay(wait) else yield()
    frame++
    if (!render) return null
    Snapshot.sendApplyNotifications()
    if (settle) {
      scene.render((base + due - FRAME / 2).inWholeNanoseconds).close()
      repeat(SETTLE_YIELDS) { yield() }
      Snapshot.sendApplyNotifications()
    }
    val mark = TimeSource.Monotonic.markNow()
    val image = scene.render((base + due).inWholeNanoseconds)
    val took = mark.elapsedNow()
    rendering += took
    rendered++
    slowest = maxOf(slowest, took)
    return image
  }

  /**
   * Moves [count] frames on without keeping them. Only the last [PREROLL_RENDERED_FRAMES] of
   * them render: the data and the app's timers need the time, Compose's animations only the
   * frames before the ones that are kept.
   */
  suspend fun skip(count: Int, step: FrameStep) {
    repeat(count) { index ->
      next(render = count - index <= PREROLL_RENDERED_FRAMES, step = step)?.close()
    }
  }

  /**
   * Writes [count] frames to `promo/<locale>/<clip>/NNNN.<ext>` in [format] and how long they
   * took to `<clip>.txt`, settling the frames [settle] picks by their index on the timeline.
   */
  suspend fun record(
    clip: String,
    count: Int,
    format: FrameFormat,
    settle: (frame: Int) -> Boolean = { false },
    step: FrameStep,
  ) {
    val began = TimeSource.Monotonic.markNow()
    val first = frame
    val dir = File(PromoDir, clip)
    repeat(count) { index ->
      val image = checkNotNull(next(settle(frame), step = step))
      writer.write(frameFile(dir, index, format), image, format)
    }
    File(PromoDir, "$clip.txt").writeText(
      "frames=$count fps=$FPS format=${format.extension} first=$first " +
        "wall=${began.elapsedNow()} renderAvg=${rendering / rendered} renderMax=$slowest\n",
    )
  }

  /** Writes the next frame to `promo/<locale>/stills/<name>.png`. */
  suspend fun still(name: String, step: FrameStep) {
    val image = checkNotNull(next(step = step))
    writer.write(File(PromoDir, "stills/$name.png"), image, FrameFormat.Png)
  }

  /** Waits for the frames to be written; the timeline renders no more. */
  fun finish() {
    writer.close()
  }
}

private fun appendStats(clip: String, line: String) {
  File(PromoDir, "$clip.txt").appendText("$line\n")
}

/** Frame [index] of the clip in [dir]: `NNNN.png`, or `NNNN.jpg` in [FrameFormat.Jpeg]. */
private fun frameFile(dir: File, index: Int, format: FrameFormat): File {
  // Not String.format: the default locale, which a language changes, picks the digits.
  return File(dir, "${index.toString().padStart(FRAME_DIGITS, '0')}.${format.extension}")
}

/**
 * Removes the frames and records of [clips] before a scene starts, so nothing of an older one
 * is left.
 */
private fun clearClips(vararg clips: String) {
  for (clip in clips) {
    File(PromoDir, clip).deleteRecursively()
    File(PromoDir, "$clip.json").delete()
  }
}

/**
 * Draws each frame of [screens], a phone's screen, in a phone of [kind] on a transparent canvas,
 * as the README showcase draws its phones, to `promo/<locale>/<clip>/`, and records the clip.
 */
private fun framePhone(screens: String, clip: String, kind: PhoneKind, theme: SnapshotTheme) {
  val frames = File(PromoDir, screens).listFiles { file -> file.extension == "png" }
    .orEmpty()
    .sorted()
  val geometry = PhoneGeometry.of(kind)
  val edge = geometry.bezel + geometry.frame
  val width = SnapshotSize.Phone.width + (edge + PhoneMargin) * 2
  val height = SnapshotSize.Phone.height + (edge + PhoneMargin) * 2
  val widthPx = (width.value * PHONE_SCALE).roundToInt()
  val heightPx = (height.value * PHONE_SCALE).roundToInt()
  var screen by mutableStateOf<ImageBitmap?>(null)
  val dir = File(PromoDir, clip)
  dir.deleteRecursively()
  withScene(
    width = widthPx,
    height = heightPx,
    density = Density(PHONE_SCALE),
    content = {
      KetchTheme(darkTheme = theme == SnapshotTheme.Dark, reduceMotion = true) {
        screen?.let { image ->
          Phone(
            kind = kind,
            screen = image,
            screenWidth = SnapshotSize.Phone.width,
            screenHeight = SnapshotSize.Phone.height,
            scale = 1f,
            ink = KetchTheme.colors.textPrimary,
            dark = theme == SnapshotTheme.Dark,
            time = STATUS_TIME,
            modifier = Modifier.padding(PhoneMargin),
          )
        }
      }
    },
  ) {
    FrameWriter().use { writer ->
      frames.forEachIndexed { index, file ->
        val decoded = Image.makeFromEncoded(file.readBytes())
        // The bitmap is a copy; the decoded image goes at once, not when the GC gets to it.
        screen = try {
          decoded.toComposeImageBitmap()
        } finally {
          decoded.close()
        }
        Snapshot.sendApplyNotifications()
        val image = render(frameTime(index).inWholeNanoseconds)
        writer.write(frameFile(dir, index, FrameFormat.Png), image, FrameFormat.Png)
      }
    }
  }
  writeClipRecord(clip, frames.size, widthPx, heightPx, PHONE_SCALE, alpha = true)
}

/** Moves the pointer to the window's right margin, where it hovers nothing. */
private suspend fun SnapshotScene.rest(size: SnapshotSize) {
  hover(size.width - 4.dp, size.height / 2)
}

/** When frame [index] shows, from the first. */
private fun frameTime(index: Int): Duration = (index * NANOS_PER_SECOND / FPS).nanoseconds

/**
 * Moves every running download on as a fast line would: Studio's at [SPEEDUP] times the
 * showcase's speed, split evenly between its unfinished lanes, or as
 * [ShowcaseData.ISO_LANE_RATES] for the ISO, and a torrent, which has no lanes, by its progress.
 * Each second every lane, or a torrent, runs up to [WANDER] faster or slower, as lines do; a
 * download with a limit of its own holds at its speed. A finished lane stops, and the
 * download's speed drops by its rate.
 */
private class Traffic(env: PromoEnvironment) {
  private val boosted = env.studioTasks.toSet()
  private val running = env.allTasks.filter { it.state.value is DownloadState.Downloading }
  private val rates = HashMap<ListTestTask, List<Long>>()
  private val speeds = HashMap<ListTestTask, Long>()
  private var time = Duration.ZERO

  /**
   * Sets each running download's speed to the sum of its lanes, or [SPEEDUP] times its own on
   * Studio, keeping a download with a limit of its own, such as the time-lapse's 5 MB/s, under
   * it.
   */
  fun boost() {
    for (task in running) {
      val lanes = laneRates(task)
      rates[task] = lanes
      val progress = (task.state.value as DownloadState.Downloading).progress
      val speed = if (lanes.isNotEmpty()) lanes.sum() else progress.bytesPerSecond * speedupOf(task)
      speeds[task] = speed
      task.state.value = DownloadState.Downloading(progress.copy(bytesPerSecond = speed))
    }
  }

  /**
   * Puts the ISO's lanes at [IsoLanes] less what they download in [frames], so they reach
   * those places as the recording starts.
   */
  fun rewind(frames: Int) {
    val iso = running.first { it.taskId == ShowcaseData.ISO_ID }
    val lanes = rates.getValue(iso)
    val seconds = frames.toDouble() / FPS
    iso.segments.update { segments ->
      segments.mapIndexed { index, segment ->
        val size = segment.end - segment.start + 1
        val target = (size * IsoLanes[index]).toLong()
        val back = (lanes[index] * seconds).roundToLong()
        segment.copy(downloadedBytes = max(0L, target - back))
      }
    }
    val state = iso.state.value as DownloadState.Downloading
    val downloaded = iso.segments.value.sumOf { it.downloadedBytes }
    iso.state.value = DownloadState.Downloading(state.progress.copy(downloadedBytes = downloaded))
  }

  /**
   * A [FrameStep]: moves every running download on to [now] on the timeline, by the rates it
   * runs at in the second [now] is in.
   */
  @Suppress("UNUSED_PARAMETER")
  fun advance(frame: Int, now: Duration) {
    val elapsed = now - time
    if (!elapsed.isPositive()) return
    time = now
    val seconds = elapsed.toDouble(DurationUnit.SECONDS)
    val second = now.inWholeSeconds
    running.forEachIndexed { index, task ->
      val state = task.state.value as? DownloadState.Downloading ?: return@forEachIndexed
      val steady = !task.request.speedLimit.isUnlimited
      fun wander(lane: Int): Double = if (steady) 1.0 else 1 + WANDER * noise(index, lane, second)
      val lanes = rates[task].orEmpty()
      if (lanes.isEmpty()) {
        // A torrent has no lanes; it moves by its speed.
        val speed = (speeds.getValue(task) * wander(-1)).roundToLong()
        val bytes = (speed * seconds).roundToLong()
        val done = (state.progress.downloadedBytes + bytes).coerceAtMost(state.progress.totalBytes)
        val progress = state.progress.copy(downloadedBytes = done, bytesPerSecond = speed)
        task.state.value = DownloadState.Downloading(progress)
        return@forEachIndexed
      }
      val current = lanes.mapIndexed { lane, rate -> (rate * wander(lane)).roundToLong() }
      task.segments.update { segments ->
        segments.mapIndexed { lane, segment ->
          val step = (current.getOrElse(lane) { 0L } * seconds).roundToLong()
          val done = (segment.downloadedBytes + step).coerceAtMost(segment.totalBytes)
          segment.copy(downloadedBytes = done)
        }
      }
      val segments = task.segments.value
      val speed = segments.withIndex()
        .filter { (_, segment) -> !segment.isComplete }
        .sumOf { (lane, _) -> current.getOrElse(lane) { 0L } }
      val progress = state.progress.copy(
        downloadedBytes = segments.sumOf { it.downloadedBytes },
        bytesPerSecond = speed,
      )
      task.state.value = DownloadState.Downloading(progress)
    }
  }

  /** The same number in -1..1 for a lane of a download every time in one second. */
  private fun noise(task: Int, lane: Int, second: Long): Double =
    Random((task * NOISE_LANES + lane + 1) * NOISE_SECONDS + second).nextDouble(-1.0, 1.0)

  private fun laneRates(task: ListTestTask): List<Long> {
    val segments = task.segments.value
    if (segments.isEmpty()) return emptyList()
    if (task.taskId == ShowcaseData.ISO_ID) return ShowcaseData.ISO_LANE_RATES.map { it * SPEEDUP }
    val open = segments.count { !it.isComplete }.coerceAtLeast(1)
    val progress = (task.state.value as DownloadState.Downloading).progress
    val speed = progress.bytesPerSecond * speedupOf(task)
    return segments.map { if (it.isComplete) 0L else speed / open }
  }

  // Only Studio's line is fast; a limited download already runs close to its limit.
  private fun speedupOf(task: ListTestTask): Long =
    if (task in boosted && task.request.speedLimit.isUnlimited) SPEEDUP else 1L
}

/**
 * How a frame is encoded.
 *
 * @property extension the frame files' extension, which the record names.
 */
private enum class FrameFormat(val extension: String) {
  /** Lossless, with alpha: stills, the phones and every small clip. */
  Png("png"),

  /**
   * JPEG at [JPEG_QUALITY]: the dense desktop clips, whose PNG frames would take several GB
   * a locale. Opaque only; the film rounds the window's corners itself.
   */
  Jpeg("jpg"),
  ;

  fun encode(image: Image): Data? = when (this) {
    Png -> image.encodeToData(EncodedImageFormat.PNG)
    Jpeg -> image.encodeToData(EncodedImageFormat.JPEG, JPEG_QUALITY)
  }
}

/**
 * Encodes frames on other threads, at most two per thread waiting, and closes each image once
 * written; [close] waits for the last one and fails if any did.
 */
private class FrameWriter : AutoCloseable {
  private val threads = (Runtime.getRuntime().availableProcessors() - 2).coerceAtLeast(2)
  private val pool = Executors.newFixedThreadPool(threads) { task ->
    Thread(task, "promo-frames").apply { isDaemon = true }
  }
  private val backlog = Semaphore(threads * 2)
  private val failure = AtomicReference<Throwable?>()

  fun write(file: File, image: Image, format: FrameFormat) {
    backlog.acquire()
    pool.execute {
      try {
        val data = checkNotNull(format.encode(image)) { "Couldn't encode $file" }
        file.parentFile.mkdirs()
        file.writeBytes(data.bytes)
      } catch (e: Throwable) {
        failure.compareAndSet(null, e)
      } finally {
        image.close()
        backlog.release()
      }
    }
  }

  override fun close() {
    pool.shutdown()
    check(pool.awaitTermination(ENCODE_TIMEOUT_MINUTES, TimeUnit.MINUTES)) { "Frames left" }
    failure.get()?.let { throw AssertionError("Couldn't write a frame", it) }
  }
}

// ═════════════════════════════════════════════════════════════════════════════════════════════
// Devices and data
// ═════════════════════════════════════════════════════════════════════════════════════════════

/** Which app a [PromoEnvironment] runs. */
private enum class PromoDevice {
  /** The laptop's desktop app. */
  Desktop,

  /** The laptop's desktop app, shared with other devices. */
  SharedDesktop,

  /** The laptop's desktop app, shared with other devices, with speed modes on Auto. */
  SettingsDesktop,

  /** A phone, which reaches the laptop as a remote. */
  Phone,

  /** The web app in a browser, which reaches every device as a remote. */
  Web,
}

/**
 * The devices of the README showcase behind one asset, as [ShowcaseEnvironment] has them, with
 * motion on, Discover set up on Ollama, the laptop's networks and the app in [language]; the
 * laptop is shared on a [PromoDevice.SharedDesktop] and also has its speed modes on Auto on a
 * [PromoDevice.SettingsDesktop]. Studio's magnets resolve to [promoTorrent].
 */
private class PromoEnvironment(
  device: PromoDevice,
  theme: SnapshotTheme,
  aiProviderFactory: AiDiscoveryProviderFactory?,
  language: String? = Promo.appLanguage,
  discoverHistory: Boolean = true,
  inspectorWidth: Int? = null,
  table: Map<String, String> = emptyMap(),
) : SnapshotEnvironment {
  /** Studio's downloads, with [promoIso] in place of the showcase's ISO. */
  val studioTasks: List<ListTestTask> = ShowcaseData.studioTasks().map { task ->
    if (task.taskId == ShowcaseData.ISO_ID) promoIso() else task
  }

  private val homeTasks = ShowcaseData.homeTasks()
  private val laptopTasks = ShowcaseData.laptopTasks()

  /** Every device's downloads, whose flows a scenario moves on. */
  val allTasks: List<ListTestTask> = studioTasks + homeTasks + laptopTasks

  private val desktop = device != PromoDevice.Phone && device != PromoDevice.Web
  private val shared = device == PromoDevice.SharedDesktop || device == PromoDevice.SettingsDesktop
  private val laptopId = if (desktop) LOCAL_DEVICE_ID else ShowcaseData.LAPTOP_ID
  private val density = if (device == PromoDevice.Phone) {
    DensityMode.Comfortable
  } else {
    DensityMode.Compact
  }

  override val data = SampleData(
    tasks = if (desktop) laptopTasks else emptyList(),
    downloadConfig = ShowcaseData.StudioConfig,
    remotes = listOfNotNull(
      ShowcaseData.StudioRemote,
      ShowcaseData.HomeRemote,
      ShowcaseData.LaptopRemote.takeUnless { desktop },
    ),
    deviceName = if (desktop) ShowcaseData.LAPTOP else PHONE_NAME,
    // Lanes glide and rows slide between the row updates, four a second.
    ui = { ui ->
      ui.copy(
        lastDeviceId = ShowcaseData.STUDIO_ID,
        reduceMotion = false,
        discoverHistory = discoverHistory,
        inspectorWidth = inspectorWidth ?: ui.inspectorWidth,
        table = ui.table + table,
      )
    },
  )

  private val studio = PromoResolvingApi(
    SampleKetchApi(
      name = ShowcaseData.STUDIO,
      initial = studioTasks,
      config = ShowcaseData.StudioConfig,
      directory = ShowcaseData.STUDIO_DIR,
      version = PromoVersion,
    ),
  )
  private val home = SampleKetchApi(
    name = "Home server",
    initial = homeTasks,
    config = DownloadConfig(defaultDirectory = ShowcaseData.HOME_DIR, maxConcurrentDownloads = 2),
    os = "Linux",
    directory = ShowcaseData.HOME_DIR,
    version = PromoVersion,
  )
  private val laptop = NetworkedApi(
    SampleKetchApi(
      name = ShowcaseData.LAPTOP,
      initial = laptopTasks,
      config = DownloadConfig(defaultDirectory = ShowcaseData.LAPTOP_DIR),
      directory = ShowcaseData.LAPTOP_DIR,
      version = PromoVersion,
    ),
  )
  private val speedScope = CoroutineScope(SupervisorJob() + SnapshotHarness.ui)
  private val speed = PromoSpeed.takeIf { device == PromoDevice.SettingsDesktop }

  private val instanceManager = InstanceManager(
    factory = InstanceFactory(
      deviceName = data.deviceName,
      embeddedFactory = if (desktop) ({ laptop }) else null,
      localServerFactory = PretendServer.takeIf { shared },
      remoteFactory = { config ->
        val api: KetchApi = when (config.host) {
          ShowcaseData.StudioRemote.host -> studio
          ShowcaseData.LaptopRemote.host -> laptop
          else -> home
        }
        RemoteInstance(api, config, MutableStateFlow(ConnectionState.Connected))
      },
    ),
    initialRemotes = data.remotes,
    configStore = RecordingConfigStore(
      data.config(theme, density).let { config ->
        config.copy(
          ai = PromoAi,
          speed = speed ?: config.speed,
          server = if (shared) {
            ServerConfig(apiToken = PROMO_TOKEN, mdnsEnabled = true)
          } else {
            config.server
          },
          appearance = config.appearance.copy(language = language),
        )
      },
    ),
    context = SnapshotHarness.ui,
    clock = SampleData.CLOCK,
  )

  private val speedMode = speed?.let { settings ->
    SpeedModeController(
      config = { laptop.status().config },
      apply = { laptop.updateConfig(it) },
      scope = speedScope,
      settings = settings,
      observedPeak = ObservedPeak(
        bytesPerSecond = PROMO_PEAK,
        atEpochMillis = (SampleData.NOW - 1.days).toEpochMilliseconds(),
      ),
      scheduler = SpeedScheduler { TimeZone.UTC },
      clock = SampleData.CLOCK,
    )
  }

  override val controller: AppController = AppController(
    instanceManager = instanceManager,
    aiProviderFactory = aiProviderFactory,
    context = SnapshotHarness.ui,
    speedMode = speedMode,
    clock = SampleData.CLOCK,
    listDispatcher = SnapshotHarness.ui,
    timeSource = SnapshotClock.timeSource,
  )

  init {
    if (shared) instanceManager.startServer()
  }

  /** Key of Studio's task [taskId] in this app. */
  fun studioKey(taskId: String): TaskKey = TaskKey(ShowcaseData.STUDIO_ID, taskId)

  override suspend fun start() {
    // Let the store forget the tasks of the empty list it starts from.
    repeat(STARTUP_YIELDS) { yield() }
    val states = studioTasks.associate { studioKey(it.taskId) to it.state.value } +
      homeTasks.associate { TaskKey(ShowcaseData.HOME_ID, it.taskId) to it.state.value } +
      laptopTasks.associate { TaskKey(laptopId, it.taskId) to it.state.value }
    seedSpeedHistory(controller.speedHistory, states, Random(SEED)) { second, random ->
      0.84 + 0.12 * sin(second * PI / 23) + 0.08 * random.nextDouble()
    }
    controller.taskList.rows.first { it.size == studioTasks.size }
    instanceManager.presence.first { list -> list.all { it.disk != null } }
  }

  /**
   * Plays [seconds] of each device's live speed, as [ShowcaseEnvironment.playSpeedHistory]
   * does: once a second its running downloads swing around their speed together, then settle
   * back on it for the last samples.
   */
  suspend fun playSpeedHistory(seconds: Int) {
    val devices = listOf(studioTasks, homeTasks, laptopTasks)
    val speeds = devices.flatten().associateWith { task ->
      (task.state.value as? DownloadState.Downloading)?.progress?.bytesPerSecond
    }
    val random = Random(SEED)
    fun swing(factors: List<Double>) = devices.forEachIndexed { index, tasks ->
      for (task in tasks) {
        val speed = speeds[task] ?: continue
        val state = task.state.value as? DownloadState.Downloading ?: continue
        val progress = state.progress.copy(bytesPerSecond = (speed * factors[index]).roundToLong())
        task.state.value = DownloadState.Downloading(progress)
      }
    }
    for (second in 0 until seconds) {
      swing(
        devices.indices.map { index ->
          SWING_BASE + SWING_WAVE * sin(second * SWING_STEP + index * SWING_PHASE) +
            SWING_NOISE * random.nextDouble()
        },
      )
      delay(1.seconds)
    }
    swing(devices.map { 1.0 })
    delay(SETTLE_SAMPLES.seconds)
  }

  override fun close() {
    controller.close()
    speedScope.cancel()
    instanceManager.close()
  }

  private companion object {
    const val PHONE_NAME = "Phone"
    const val SEED = 26
    const val STARTUP_YIELDS = 3
    const val SWING_BASE = 0.76
    const val SWING_WAVE = 0.18
    const val SWING_NOISE = 0.1
    const val SWING_STEP = 0.75
    const val SWING_PHASE = 2.1
    const val SETTLE_SAMPLES = 2
  }
}

/**
 * The showcase's ISO at the size of a server installer, so that its lanes, at the clips' speed,
 * move a good part of their length in a few seconds without one of them finishing.
 */
private fun promoIso(): ListTestTask = downloading(
  id = ShowcaseData.ISO_ID,
  url = "https://mirror.example.org/releases/26.10/$PROMO_ISO_NAME",
  total = ISO_BYTES,
  speed = ShowcaseData.ISO_LANE_RATES.sum(),
  lanes = IsoLanes,
  ago = 7.minutes,
  dir = ShowcaseData.STUDIO_DIR,
)

/**
 * Discovery that reports the [script]'s steps, then answers with its candidates, each once [cue]
 * passes its number, so the clip decides when each shows rather than the clock.
 */
private class ScriptedDiscovery(
  private val cue: MutableStateFlow<Int>,
  private val script: PromoDiscoverScript,
) : AiDiscoveryProviderFactory {
  override fun create(settings: AiSettings): AiDiscoveryProvider? {
    if (!settings.isUsable) return null
    return object : AiDiscoveryProvider {
      override suspend fun discover(
        request: AiDiscoverRequest,
        onStep: (DiscoveryStep) -> Unit,
        approve: suspend (AiPageRequest) -> Boolean,
      ): AiDiscoverResponse {
        script.steps.forEachIndexed { index, step ->
          cue.first { it > index }
          onStep(step)
        }
        cue.first { it > script.steps.size }
        return AiDiscoverResponse(
          query = request.query,
          candidates = script.candidates,
          summary = script.summary,
          title = script.title,
        )
      }

      override suspend fun verify(): String = "OK"
    }
  }
}

/** [base], with magnets resolving to [promoTorrent]. */
private class PromoResolvingApi(private val base: KetchApi) : KetchApi by base {
  override suspend fun resolve(input: ResolveInput): ResolvedSource =
    if (input is ResolveInput.Url && input.url.startsWith("magnet:")) {
      promoTorrent(input.url)
    } else {
      base.resolve(input)
    }
}

private fun promoTorrent(url: String): ResolvedSource {
  val files = TorrentFiles.mapIndexed { index, name ->
    "$TORRENT_NAME/$name" to 412_000_000L + index * 7_340_032L
  }.plus("$TORRENT_NAME/Notes.txt" to 4_096L)
    .mapIndexed { index, (path, size) -> SourceFile(index.toString(), path, size) }
  return ResolvedSource(
    url = url,
    sourceType = "torrent",
    totalBytes = files.sumOf { it.size },
    supportsResume = true,
    suggestedFileName = TORRENT_NAME,
    maxSegments = files.size,
    metadata = mapOf("infoHash" to PROMO_INFO_HASH),
    files = files,
  )
}

/** The app on a phone of [kind], under its status bar and over its gesture area. */
@OptIn(InternalComposeUiApi::class)
@Composable
private fun PromoPhoneApp(controller: AppController, kind: PhoneKind) {
  val geometry = PhoneGeometry.of(kind)
  val density = LocalDensity.current
  val insets = remember(density, geometry) {
    with(density) {
      PromoPhoneInsets(geometry.statusBar.roundToPx(), geometry.bottomInset.roundToPx())
    }
  }
  CompositionLocalProvider(LocalPlatformWindowInsets provides insets) {
    App(controller)
  }
}

/** A phone's system bars: the status bar on top and the gesture area at the bottom, in pixels. */
@OptIn(InternalComposeUiApi::class)
private class PromoPhoneInsets(top: Int, bottom: Int) : PlatformWindowInsets {
  override val statusBars: PlatformInsets = PlatformInsets(0, top, 0, 0)
  override val navigationBars: PlatformInsets = PlatformInsets(0, 0, 0, bottom)
  override val systemBars: PlatformInsets = PlatformInsets(0, top, 0, bottom)
}

// ═════════════════════════════════════════════════════════════════════════════════════════════
// Constants. The ones marked LAYOUT depend on the app's layout: check the assets (and the
// scenes that frame them) when the app's layout changes. The ones marked SCENE are mirrored by
// the video's scenes or its manifest; change both together.
// ═════════════════════════════════════════════════════════════════════════════════════════════

/**
 * Where this run writes, made on first use: `build/snapshots/promo/<locale>/` for the dark film,
 * `promo/<locale>/light/` for the light one (`art/promo/scripts/assets/lib.mjs` reads the same).
 */
private val PromoDir: File by lazy {
  val theme = if (Film == SnapshotTheme.Dark) "" else "/${Film.id}"
  File(SnapshotHarness.outputDir, "promo/${Promo.tag}$theme").apply { mkdirs() }
}

/** The system property `-PsnapshotTheme` sets (`app/shared/build.gradle.kts`). */
private const val THEME_PROPERTY = "ketch.snapshots.theme"

/**
 * The version every device reports, as the Devices page's cards show it: `release` in
 * [PROMO_FACTS], the one place the film keeps the numbers it states. `npm run facts` updates it
 * after a release, and `npm run assets` then shows the new version here, as it does in the
 * extension popup and the film's own text.
 */
private val PromoVersion: String by lazy {
  val facts = generateSequence(File("").absoluteFile) { it.parentFile }
    .map { File(it, PROMO_FACTS) }
    .firstOrNull(File::isFile)
    ?: error("No $PROMO_FACTS in ${File("").absolutePath} or a folder above it")
  val release = Json.parseToJsonElement(facts.readText()).jsonObject["release"]
  checkNotNull(release?.jsonPrimitive?.contentOrNull) { "$facts has no \"release\"" }
}

/** The film's facts, from the repository's root; Gradle runs the tests in `app/shared`. */
private const val PROMO_FACTS = "art/promo/src/facts.json"

/** SCENE. The clips' frame rate, in the record as `fps`: the 4K film's 60. */
private const val FPS = 60
private const val NANOS_PER_SECOND = 1_000_000_000L
private val FRAME: Duration = (NANOS_PER_SECOND / FPS).nanoseconds
private const val FRAME_DIGITS = 4
private const val ENCODE_TIMEOUT_MINUTES = 10L

/**
 * How the large desktop clips (`desktop-*`, `discover-*`) store their frames, and the quality
 * of a JPEG frame (0–100).
 */
private val LARGE_CLIP_FORMAT = FrameFormat.Png
private const val JPEG_QUALITY = 93

// Pixels per dp of each asset, in its record as `density`: enough for the film to show it at
// least 1:1 at 4K (3840 × 2160) at the largest scale a scene's camera gives it. Window sizes stay
// in dp, so bounds, which are fractions of an image, keep their values. SCENE: the scenes convert
// their source-px constants by the density the manifest records.

/** The desktop clips and the accent stills: S03's zoom into the lanes. */
private const val CLIP_SCALE = 4.25f

/** The Discover clip: S08's camera on the steps and results. */
private const val DISCOVER_SCALE = 4f

/** Downloads and the ISO's Overview: S05's Smart queue, Scheduling and Priorities tiles. */
private const val DOWNLOADS_SCALE = 3.75f

/** The add sheet with the torrent's files: S04's crop. */
private const val INTAKE_SCALE = 3.25f

/** Settings → Speed and Network: S05's Speed modes and Several networks tiles. */
private const val SETTINGS_SCALE = 3f

/** The `lang-*` stills: S11's window. */
private const val LANGUAGES_SCALE = 2.75f

/** The command palette: S05's tile. */
private const val PALETTE_SCALE = 2.5f

/** Every other still: All devices and the web app, which the film shows small. */
private const val STILL_SCALE = 2f

/** The laptop's window, 1280 × 800 dp with its title bar. */
private val DesktopWindow = SnapshotSize(1280.dp, 800.dp, KetchDensity.Compact)
private val WebWindow = SnapshotSize(1024.dp, 720.dp, KetchDensity.Compact)

/**
 * LAYOUT. The desktop clips' window, 1312 × 820 dp: 32 dp wider than [DesktopWindow], so that
 * the inspector, [CLIP_INSPECTOR_WIDTH] wide, fits the whole "7 active · 87.5 MB/s" beside the
 * connections stepper and the table keeps its width.
 */
private val ClipWindow = SnapshotSize(1312.dp, 820.dp, KetchDensity.Compact)
private const val CLIP_INSPECTOR_WIDTH = 352

/**
 * SCENE. 14 s: S02 and S03 play the film's desktop clip end to end (`CLIP_NEEDS` in
 * `src/assets.ts`).
 */
private const val DESKTOP_FRAMES = 840

/** SCENE. The dark film's light window, for S02's light beat: 4 s. */
private const val DESKTOP_LIGHT_FRAMES = 240
private const val DARK_CLIP = "desktop-dark"

/** SCENE. How far the light clip runs behind the dark one: S02 plays it 1 s behind. */
private const val LIGHT_OFFSET_FRAMES = 60

/** SCENE. Frames of the dark clip's timeline that S02 shows each accent's still from. */
private val AccentFrames = listOf(300, 336, 372, 408)
private const val ACCENT_SETTLE_FRAMES = 24

/**
 * Frames before a recording starts, 10 s with the downloads moving, so the lane rates, which
 * average a sample a second, have settled on them. Only the last [PREROLL_RENDERED_FRAMES]
 * render, for the animations they leave in place.
 */
private const val PREROLL_FRAMES = 600
private const val PREROLL_RENDERED_FRAMES = 60
private const val SPEED_HISTORY_SECONDS = 16

/** Yields after a settling render, for the effects it starts. */
private const val SETTLE_YIELDS = 4

/** How much faster or slower than its rate a lane runs in a second, at most. */
private const val WANDER = 0.06
private const val NOISE_LANES = 64
private const val NOISE_SECONDS = 100_003L

/**
 * LAYOUT. The All devices table's columns: Status and Device a little narrower than their
 * defaults, so that the Name column fits "sunrise-timelapse-8k.mov" beside its speed limit.
 */
private val AllDevicesColumns = mapOf(
  "All" to "size,progress,speed,left,added,status:100,device:112",
)

/**
 * How far each of the ISO's lanes is as a recording starts: the first done, and none of the
 * others done after the 14 s of the longest clip.
 */
private val IsoLanes = listOf(1.0, 0.44, 0.50, 0.42, 0.46, 0.36, 0.27, 0.22)

/** The ISO Studio downloads: 3.2 GB, eight lanes of 409.6 MB. */
private const val PROMO_ISO_NAME = "server-26.10-amd64.iso"
private const val ISO_BYTES = 3_435_973_632L

/** Speed of Studio's downloads, against the showcase's, so lanes visibly move in a few seconds. */
private const val SPEEDUP = 5L

// 780 × 1688 px screens in a frame with room for its shadow.
private const val PHONE_SCALE = 2f

/** SCENE. 6 s: S06 plays them in its first beat. */
private const val PHONE_FRAMES = 360
private val PhoneMargin: Dp = 112.dp
private const val STATUS_TIME = "14:30"

/**
 * LAYOUT. A blank spot beside the half-open sheet's handle to drag it by without the handle's
 * tooltip, and where it is held, in the phone's dp.
 */
private val SheetGrip = 300.dp to 462.dp
private val SheetHeld = 206.dp

/** Scrolls [Shot.scrollPastFirstLine] may take: the top bar takes the first ones. */
private const val MAX_SCROLLS = 6

/** Discover set up on a local model, which the header names. */
private val PromoAi = AiSettings(
  enabled = true,
  llm = LlmSettings(provider = LlmProvider.Ollama, model = "qwen3"),
)

private val PromoSpeed = SpeedSettings(
  mode = SpeedLimitMode.Auto,
  standard = SpeedLimit.Unlimited,
  rules = listOf(
    SpeedRule(
      days = setOf(
        Weekday.Monday,
        Weekday.Tuesday,
        Weekday.Wednesday,
        Weekday.Thursday,
        Weekday.Friday,
      ),
      start = "09:00",
      end = "18:00",
    ),
    SpeedRule(days = setOf(Weekday.Saturday, Weekday.Sunday), start = "20:00", end = "23:30"),
  ),
)

private const val PROMO_PEAK = 10_840_000L
private const val PROMO_TOKEN = "3f2a9c1e5b7d4a60b8e2c9f1a3d5e7b9"

// The Discover clip's moments, in its frames (at [FPS]); S08 reads them from the manifest's
// `events`.

/** SCENE. When the Discover clip types the query: from a character at the first to all of it. */
private const val TYPE_FROM = 8
private const val TYPED_AT = 116

/** SCENE. When the query is sent. */
private const val SEND_AT = 132

/** SCENE. 10 s: S08 plays it through the scene (`CLIP_NEEDS` in `src/assets.ts`). */
private const val DISCOVER_FRAMES = 600

/** SCENE. Frames at which the agent reports each of its steps, then answers. */
private val CueFrames = listOf(160, 208, 256, 304, 360)

/** SCENE. When the top result is picked. */
private const val SELECT_AT = 510

/** When the composer lets go of the keyboard, once the search is under way. */
private const val BLUR_AT = SEND_AT + 12

/** Frames from a change in the Discover clip that settle before they show: 0.1 s. */
private const val SETTLE_SPAN = 6

/** The Discover clip's frames where the page changes, which settle before they show. */
private val DiscoverSettleFrames: Set<Int> =
  (listOf(SEND_AT, BLUR_AT, SELECT_AT) + CueFrames).flatMap { it until it + SETTLE_SPAN }.toSet()

/** The Discover clip's events, by the names the manifest gives them. */
private val DiscoverEvents: Map<String, Int> = buildMap {
  put("typing", TYPE_FROM)
  put("typed", TYPED_AT)
  put("send", SEND_AT)
  put("blur", BLUR_AT)
  CueFrames.dropLast(1).forEachIndexed { index, frame -> put("step${index + 1}", frame) }
  put("results", CueFrames.last())
  put("select", SELECT_AT)
}

private const val GIB = 1L shl 30

/**
 * Studio's downloads waiting in its queue, as [ShowcaseData] has them and the Downloads table
 * groups them: two queued, then one scheduled for tonight.
 */
private val WaitingDownloads =
  listOf("dataset-shard-04.tar", "design-assets-2026.zip", "local-model-8b-q4.gguf")

/** The torrent the add sheet opens: a folder of a day's recordings each, and notes. */
private const val TORRENT_NAME = "Field Recordings 2026"
private val TorrentFiles = List(TORRENT_DAYS) { index ->
  "Day ${(index + 1).toString().padStart(2, '0')}.flac"
}
private const val TORRENT_DAYS = 8
private val TorrentSkipped = listOf("Day 05.flac", "Day 06.flac")
private const val PROMO_INFO_HASH = "9a3c5e7f1b2d4c6e8f0a1b3c5d7e9f1a2b4c6d8e"
private const val PROMO_MAGNET = "magnet:?xt=urn:btih:$PROMO_INFO_HASH&dn=Field+Recordings+2026"

/** The label of Discover's model chip, as `DiscoverModelChip` shows it. */
private fun modelChipLabel(controller: AppController): String {
  val ai = controller.state.aiSettings
  val active = ai.withPlatformCredentials(ai.settings).llm
  return active.effectiveModel.ifBlank { active.displayName }
}
