package com.linroid.ketch.app.snapshot

import androidx.compose.ui.unit.dp
import com.linroid.ketch.api.KetchApi
import com.linroid.ketch.api.KetchError
import com.linroid.ketch.api.ResolvedSource
import com.linroid.ketch.api.SourceFile
import com.linroid.ketch.app.App
import com.linroid.ketch.app.RecordingConfigStore
import com.linroid.ketch.app.instance.InstanceFactory
import com.linroid.ketch.app.instance.InstanceManager
import com.linroid.ketch.app.instance.RemoteInstance
import com.linroid.ketch.app.state.AppController
import com.linroid.ketch.app.state.IntakeRequest
import com.linroid.ketch.app.state.IntakeSeed
import com.linroid.ketch.app.state.LOCAL_DEVICE_ID
import com.linroid.ketch.app.theme.KetchDensity
import com.linroid.ketch.app.util.extractFilename
import com.linroid.ketch.config.DensityMode
import com.linroid.ketch.config.IntakePreferences
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.time.Duration.Companion.seconds

/**
 * The add sheet over the app: a batch of links, every row state, a single link's preview, a
 * torrent's file picker, a magnet waiting for peers, Retry with options and the Advanced
 * headers; see [SnapshotHarness] for how to run it.
 */
class IntakeSheetSnapshots {
  @BeforeTest
  fun enabled() = requireSnapshots()

  @Test
  fun batch_everySizeAndTheme_showsOneRowPerLink() {
    intakeSnapshots("intake-batch", WIDE_AND_PHONE) { state.openIntake(IntakeRequest(BATCH)) }
  }

  @Test
  fun problems_desktopAndPhone_showEveryRowState() {
    intakeSnapshots("intake-problems", DESKTOP_AND_PHONE, resolve = ::problemResolve) {
      state.openIntake(IntakeRequest(PROBLEMS))
    }
  }

  @Test
  fun rowStates_desktopAndPhone_showDuplicatesOldServersAndErrors() {
    intakeSnapshots("intake-row-states", DESKTOP_AND_PHONE, resolve = ::problemResolve) {
      state.openIntake(IntakeRequest(PROBLEMS.lines().drop(4).joinToString("\n")))
    }
  }

  @Test
  fun single_everySize_showsThePreview() {
    intakeSnapshots("intake-single", WIDE_AND_PHONE) {
      state.openIntake(IntakeRequest(KERNEL))
    }
  }

  @Test
  fun torrent_desktopAndPhone_showsTheFilePicker() {
    intakeSnapshots("intake-torrent", DESKTOP_AND_PHONE, resolve = ::problemResolve) {
      state.openIntake(IntakeRequest(MAGNET))
    }
  }

  @Test
  fun magnetWaiting_desktop_showsTheFetchState() {
    intakeSnapshots("intake-magnet-waiting", DESKTOP_ONLY, resolve = { awaitCancellation() }) {
      state.openIntake(IntakeRequest(MAGNET))
    }
  }

  @Test
  fun retry_desktopAndPhone_prefillsTheFailedTask() {
    intakeSnapshots("intake-retry", DESKTOP_AND_PHONE, resolve = ::problemResolve) {
      val key = data.keyOf("model-weights.safetensors")
      val url = data.task("model-weights.safetensors").request.url
      state.openIntake(
        IntakeRequest(
          seeds = listOf(IntakeSeed(url)),
          targetDeviceId = LOCAL_DEVICE_ID,
          retryOf = key,
        ),
      )
    }
  }

  @Test
  fun options_desktop_editsAQueuedTask() {
    intakeSnapshots("intake-options", DESKTOP_ONLY) {
      state.openIntake(IntakeRequest(editTask = data.keyOf("blender-4.2-macos-arm64.dmg")))
    }
  }

  @Test
  fun advanced_desktop_showsTheHeaders() {
    intakeSnapshots("intake-advanced", DESKTOP_ONLY) {
      state.appSettings.saveUi { it.copy(intakeAdvancedOpen = true) }
      state.openIntake(
        IntakeRequest(
          seeds = listOf(
            IntakeSeed(
              url = "https://files.example.com/reports/q4-board-deck.pdf",
              headers = mapOf("Referer" to "https://files.example.com/reports"),
            ),
          ),
        ),
      )
    }
  }

  @Test
  fun saveToMenu_desktop_listsPinnedFoldersAndChooseFolder() {
    intakeSnapshots("intake-save-to", DESKTOP_ONLY) {
      state.appSettings.saveUi {
        val pinned = IntakePreferences(favoriteFolders = listOf("/Volumes/Media/Movies"))
        it.copy(intake = mapOf(LOCAL_DEVICE_ID to pinned))
      }
      state.openIntake(IntakeRequest(KERNEL))
      scene.settle()
      // The Save to pill of the single link's sheet.
      scene.click(470.dp, 350.dp)
    }
  }

  @Test
  fun empty_desktopAndPhone_showsThePlaceholder() {
    intakeSnapshots("intake-empty", DESKTOP_AND_PHONE) { state.openIntake(IntakeRequest()) }
  }

  /** Renders the app with the add sheet [setup] opens, at [sizes] in every theme. */
  private fun intakeSnapshots(
    name: String,
    sizes: List<SnapshotSize>,
    themes: List<SnapshotTheme> = SnapshotTheme.entries,
    resolve: suspend (String) -> ResolvedSource = ::readyResolve,
    setup: suspend AppScenario.() -> Unit,
  ) {
    for (size in sizes) {
      for (theme in themes) intakeSnapshot(name, size, theme, resolve, setup)
    }
  }

  private fun intakeSnapshot(
    name: String,
    size: SnapshotSize,
    theme: SnapshotTheme,
    resolve: suspend (String) -> ResolvedSource,
    setup: suspend AppScenario.() -> Unit,
  ) {
    val data = SampleData.downloads()
    val density = when (size.density) {
      KetchDensity.Compact -> DensityMode.Compact
      KetchDensity.Comfortable -> DensityMode.Comfortable
    }
    val environment = runBlocking(SnapshotHarness.ui) {
      IntakeEnvironment(data, theme, density, resolve)
    }
    try {
      runBlocking(SnapshotHarness.ui) {
        withTimeoutOrNull(5.seconds) {
          environment.controller.taskList.rows.first { it.size == data.tasks.size }
        } ?: error("The task list of $name never listed every sample task")
      }
      SnapshotHarness.capture(
        name = "$name-${theme.id}-${size.id}",
        size = size,
        interact = { AppScenario(environment.controller, data, this).setup() },
      ) {
        App(environment.controller)
      }
    } finally {
      runBlocking(SnapshotHarness.ui) { environment.close() }
    }
  }
}

/** The sample devices, with links checked by [resolve] on the embedded one. */
private class IntakeEnvironment(
  data: SampleData,
  theme: SnapshotTheme,
  density: DensityMode,
  resolve: suspend (String) -> ResolvedSource,
) {
  private val api = ResolvingApi(SampleKetchApi(data), resolve)
  private val instanceManager = InstanceManager(
    factory = InstanceFactory(deviceName = data.deviceName, embeddedFactory = { api }),
    initialRemotes = data.remotes,
    configStore = RecordingConfigStore(data.config(theme, density)),
  )

  val controller: AppController = AppController(
    instanceManager = instanceManager,
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

/** [base] with its links checked by [check]. */
private class ResolvingApi(
  private val base: KetchApi,
  private val check: suspend (String) -> ResolvedSource,
) : KetchApi by base {
  override suspend fun resolve(url: String, properties: Map<String, String>): ResolvedSource =
    check(url)
}

private val WIDE_AND_PHONE = listOf(SnapshotSize.Desktop, SnapshotSize.Medium, SnapshotSize.Phone)
private val DESKTOP_AND_PHONE = listOf(SnapshotSize.Desktop, SnapshotSize.Phone)
private val DESKTOP_ONLY = listOf(SnapshotSize.Desktop)

private const val MAGNET =
  "magnet:?xt=urn:btih:3f2a91c0d4b5e6f708192a3b4c5d6e7f8091a2b3&dn=Big.Buck.Bunny"
private const val KERNEL = "https://cdn.kernel.org/pub/linux/kernel/v6.x/linux-6.12.tar.xz"

private val BATCH = listOf(
  "https://releases.ubuntu.com/24.04/ubuntu-24.04-live-server-amd64.iso",
  "https://download.blender.org/release/Blender4.2/blender-4.2-linux-x64.tar.xz",
  "https://cdn.kernel.org/pub/linux/kernel/v6.x/linux-6.12.tar.xz",
  "https://dl.google.com/android/repository/platform-tools-latest-darwin.zip",
  "https://github.com/JetBrains/kotlin/releases/download/v2.4.20/kotlin-compiler-2.4.20.zip",
).joinToString("\n")

private val PROBLEMS = listOf(
  "https://releases.ubuntu.com/24.04/ubuntu-24.04-live-server-amd64.iso",
  "https://cdn.example.org/set/part[01-04].rar",
  MAGNET,
  "https://intranet.example.com/q3-report.pdf",
  "https://docs.northwind.example/finance/q3-report.pdf",
  "http://legacy.example.net/firmware/router-fw-2.1.bin",
  "https://mirror.example.edu/archive/2019/dataset.tar",
  "https://busy.example.com/exports/catalog.csv.gz",
).joinToString("\n")

private const val MIB = 1L shl 20

private fun readyResolve(url: String): ResolvedSource = ResolvedSource(
  url = url,
  sourceType = "http",
  totalBytes = (url.length * 37L % 900 + 120) * MIB,
  supportsResume = true,
  suggestedFileName = extractFilename(url),
  maxSegments = 16,
)

private suspend fun problemResolve(url: String): ResolvedSource = when {
  url.startsWith("magnet:") -> torrent(url)
  "intranet" in url -> throw KetchError.Http(403, "Forbidden")
  "mirror.example.edu" in url -> throw KetchError.Http(404, "Not Found")
  "busy.example.com" in url -> throw KetchError.Http(503, "Service Unavailable")
  "legacy" in url -> readyResolve(url).copy(supportsResume = false, maxSegments = 1)
  "model-weights" in url -> throw KetchError.Http(403, "Forbidden")
  "part" in url -> readyResolve(url).copy(totalBytes = 1_181_116_006)
  else -> readyResolve(url).copy(totalBytes = 2_754_981_888)
}

private fun torrent(url: String): ResolvedSource {
  val files = buildList {
    for (episode in 1..6) {
      val number = episode.toString().padStart(2, '0')
      add("Season 1/S01E$number.1080p.mkv" to 1_503_238_553L + episode * 7_340_032L)
      add("Season 1/S01E$number.en.srt" to 48_128L + episode * 512L)
    }
    add("Season 1/Extras/sample.mkv" to 52_428_800L)
    add("Season 1/Extras/making-of.mkv" to 734_003_200L)
    add("Soundtrack/01 Opening.flac" to 31_457_280L)
    add("Soundtrack/02 Meadow.flac" to 28_311_552L)
    add("Artwork/poster.jpg" to 2_621_440L)
    add("info.nfo" to 4_096L)
    add("readme.txt" to 512L)
  }.mapIndexed { index, (path, size) -> SourceFile(index.toString(), path, size) }
  return ResolvedSource(
    url = url,
    sourceType = "torrent",
    totalBytes = files.sumOf { it.size },
    supportsResume = true,
    suggestedFileName = "Big.Buck.Bunny",
    maxSegments = files.size,
    metadata = mapOf("infoHash" to "3f2a91c0d4b5e6f708192a3b4c5d6e7f8091a2b3"),
    files = files,
  )
}
