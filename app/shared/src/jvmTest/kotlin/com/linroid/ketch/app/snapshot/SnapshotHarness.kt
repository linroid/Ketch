package com.linroid.ketch.app.snapshot

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.linroid.ketch.app.App
import com.linroid.ketch.app.state.AiDiscoveryProviderFactory
import com.linroid.ketch.app.state.AppController
import com.linroid.ketch.app.state.AppState
import com.linroid.ketch.app.state.IntakeRequest
import com.linroid.ketch.app.state.SettingsTarget
import com.linroid.ketch.app.state.StatusFilter
import com.linroid.ketch.app.theme.KetchDensity
import com.linroid.ketch.app.theme.KetchTheme
import com.linroid.ketch.config.DensityMode
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import org.junit.Assume.assumeTrue
import java.io.File
import java.util.TimeZone
import java.util.concurrent.Executors
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * Renders the app's UI to PNG files without a window, to look at it while working on it.
 *
 * Run every scenario with
 * `./gradlew :app:shared:jvmTest -Psnapshots --tests '*Snapshots*'`; the PNGs land in
 * `app/shared/build/snapshots/` as `<scenario>-<theme>-<width>x<height>.png`. Without
 * `-Psnapshots`, which sets the `ketch.snapshots` system property, scenarios are skipped, so a
 * plain `jvmTest` stays fast.
 *
 * A scenario file is a test class that calls [requireSnapshots] before each test, for example
 * from a `@BeforeTest` function, and whose tests call [snapshot] for one composable, or
 * [appSnapshot] and [appSnapshots] for the real [App] root over [SampleData]. An app scenario
 * opens surfaces through the intents of [AppScenario] and can press keys or move the pointer
 * through [SnapshotScene].
 *
 * Snapshots render at [SCALE] pixels per dp, with motion reduced and the clock fixed at
 * [SampleData.NOW] in UTC. Frames advance until the UI has been still for a few of them, which
 * gives rows, fonts and effects time to settle.
 */
internal object SnapshotHarness {
  /** Whether snapshot scenarios run, from the `ketch.snapshots` system property. */
  val enabled: Boolean = System.getProperty("ketch.snapshots") == "true"

  /** Folder the PNGs are written to. */
  val outputDir: File = File(System.getProperty("ketch.snapshots.dir") ?: "build/snapshots")

  /** Pixels per dp of the PNGs. */
  const val SCALE: Float = 2f

  /** The one thread that composes, renders and runs the app's commands. */
  val ui: CoroutineDispatcher = Executors.newSingleThreadScheduledExecutor { task ->
    Thread(task, "snapshot-ui").apply { isDaemon = true }
  }.asCoroutineDispatcher()

  init {
    if (enabled) {
      // No window, no reading this machine's clipboard, and the same dates everywhere.
      System.setProperty("java.awt.headless", "true")
      TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
    }
  }

  /**
   * Composes [content] at [size], settles it, runs [interact], settles again and writes the
   * frame to `<name>.png` in [outputDir].
   */
  fun capture(
    name: String,
    size: SnapshotSize,
    interact: suspend SnapshotScene.() -> Unit = {},
    content: @Composable () -> Unit,
  ): File = runBlocking(ui) {
    val scene = ImageComposeScene(
      width = (size.width.value * SCALE).toInt(),
      height = (size.height.value * SCALE).toInt(),
      density = Density(SCALE),
      coroutineContext = ui,
      content = content,
    )
    try {
      val snapshotScene = SnapshotScene(scene)
      snapshotScene.settle()
      snapshotScene.interact()
      snapshotScene.settle()
      write(name, snapshotScene.renderFrame())
    } finally {
      scene.close()
    }
  }

  private fun write(name: String, image: Image): File {
    check(!image.isFlat()) { "Snapshot $name is a single color" }
    val data = checkNotNull(image.encodeToData(EncodedImageFormat.PNG)) { "Couldn't encode $name" }
    outputDir.mkdirs()
    return File(outputDir, "$name.png").apply { writeBytes(data.bytes) }
  }

  /** Whether every pixel on a coarse grid has the same color, as in a frame that drew nothing. */
  private fun Image.isFlat(): Boolean {
    val bitmap = Bitmap.makeFromImage(this)
    val first = bitmap.getColor(0, 0)
    for (y in 0 until bitmap.height step FLAT_GRID) {
      for (x in 0 until bitmap.width step FLAT_GRID) {
        if (bitmap.getColor(x, y) != first) return false
      }
    }
    return true
  }

  private const val FLAT_GRID = 16
}

/** Light or dark colors of a snapshot, and its name in file names. */
internal enum class SnapshotTheme(val id: String) {
  Light("light"),
  Dark("dark"),
}

/**
 * Window size and density of a snapshot.
 *
 * @property width width of the window.
 * @property height height of the window.
 * @property density [KetchDensity.Compact] for pointer layouts, [KetchDensity.Comfortable] for
 *   touch.
 */
internal data class SnapshotSize(val width: Dp, val height: Dp, val density: KetchDensity) {
  /** `1280x800`, as in file names. */
  val id: String get() = "${width.value.toInt()}x${height.value.toInt()}"

  companion object {
    /** The default desktop window. */
    val Desktop = SnapshotSize(1280.dp, 800.dp, KetchDensity.Compact)

    /** The narrowest window of the expanded tier. */
    val SmallDesktop = SnapshotSize(1024.dp, 720.dp, KetchDensity.Compact)

    /** A narrow desktop window, in the medium tier. */
    val Medium = SnapshotSize(760.dp, 700.dp, KetchDensity.Compact)

    /** A phone, in the compact tier. */
    val Phone = SnapshotSize(390.dp, 844.dp, KetchDensity.Comfortable)

    /** Every size above, widest first. */
    val All: List<SnapshotSize> = listOf(Desktop, SmallDesktop, Medium, Phone)
  }
}

/**
 * A rendered scene a scenario can interact with before it is captured, such as pressing Tab to
 * show keyboard focus or hovering a row. Positions are in dp, half the PNG's pixels. Each
 * interaction settles before it returns.
 */
internal class SnapshotScene(private val scene: ImageComposeScene) {
  private val start = TimeSource.Monotonic.markNow()

  /**
   * Renders frames until nothing has changed for a few of them, at least [minimum] and at most
   * [maximum] long.
   */
  suspend fun settle(minimum: Duration = MINIMUM_SETTLE, maximum: Duration = MAXIMUM_SETTLE) {
    val begin = TimeSource.Monotonic.markNow()
    var quiet = 0
    while (true) {
      renderFrame()
      delay(FRAME)
      Snapshot.sendApplyNotifications()
      quiet = if (scene.hasInvalidations()) 0 else quiet + 1
      val elapsed = begin.elapsedNow()
      if (elapsed >= minimum && quiet >= QUIET_FRAMES || elapsed >= maximum) return
    }
  }

  /** Presses and releases [key], with Shift held when [shift] is set. */
  @OptIn(InternalComposeUiApi::class)
  suspend fun pressKey(key: Key, shift: Boolean = false) {
    // Compose has no public way to make a key event without a window.
    for (type in listOf(KeyEventType.KeyDown, KeyEventType.KeyUp)) {
      scene.sendKeyEvent(KeyEvent(key = key, type = type, isShiftPressed = shift))
    }
    settle(minimum = INTERACTION_SETTLE)
  }

  /** Moves the pointer to [x], [y] from the top left, for hover states. */
  suspend fun hover(x: Dp, y: Dp) {
    scene.sendPointerEvent(PointerEventType.Move, offset(x, y))
    settle(minimum = INTERACTION_SETTLE)
  }

  /** Clicks the primary button at [x], [y] from the top left. */
  suspend fun click(x: Dp, y: Dp) {
    val position = offset(x, y)
    scene.sendPointerEvent(PointerEventType.Move, position)
    scene.sendPointerEvent(
      eventType = PointerEventType.Press,
      position = position,
      buttons = PointerButtons(isPrimaryPressed = true),
      button = PointerButton.Primary,
    )
    scene.sendPointerEvent(
      eventType = PointerEventType.Release,
      position = position,
      buttons = PointerButtons(),
      button = PointerButton.Primary,
    )
    settle(minimum = INTERACTION_SETTLE)
  }

  /** Renders the current frame. */
  fun renderFrame(): Image = scene.render(start.elapsedNow().inWholeNanoseconds)

  private fun offset(x: Dp, y: Dp): Offset =
    Offset(x.value * SnapshotHarness.SCALE, y.value * SnapshotHarness.SCALE)

  private companion object {
    val FRAME = 16.milliseconds
    val MINIMUM_SETTLE = 600.milliseconds
    val MAXIMUM_SETTLE = 4.seconds
    val INTERACTION_SETTLE = 150.milliseconds
    const val QUIET_FRAMES = 6
  }
}

/**
 * The app of an [appSnapshot], with intents that open its surfaces.
 *
 * @property controller the controller the app root shows.
 * @property data the devices and downloads it shows.
 * @property scene the rendered scene, for keys and the pointer.
 */
internal class AppScenario(
  val controller: AppController,
  val data: SampleData,
  val scene: SnapshotScene,
) {
  /** State and commands of the app. */
  val state: AppState get() = controller.state

  /** Shows the task named [name] in the inspector, and the inspector itself. */
  fun inspect(name: String) {
    state.updateInspectorOpen(true)
    state.inspect(data.keyOf(name))
  }

  /** Selects the tasks named [names] in the list. */
  fun select(vararg names: String) {
    state.selectedKeys = names.mapTo(LinkedHashSet(), data::keyOf)
  }

  /** Opens the add sheet with [text] in its input. */
  fun openAddSheet(text: String = "") {
    state.openIntake(IntakeRequest(text = text))
  }

  /** Opens Settings at [page]. */
  fun openSettings(page: SettingsTarget.Page = SettingsTarget.Page.General) {
    state.openSettings(SettingsTarget(page))
  }

  /** Shows the Downloads list on the [filter] tab. */
  fun showTab(filter: StatusFilter) {
    state.showDownloads(filter)
  }

  /** Types [query] in the Downloads search field. */
  fun search(query: String) {
    state.searchQuery = query
  }

  /** Opens the device switcher. */
  fun openDevices() {
    state.showInstanceSelector = true
  }
}

/** Skips the calling test unless snapshots are enabled; see [SnapshotHarness]. */
internal fun requireSnapshots() {
  assumeTrue("Snapshots run with -Psnapshots", SnapshotHarness.enabled)
}

/**
 * Renders [content] in [KetchTheme], on the canvas color, to
 * `<name>-<theme>-<width>x<height>.png` and returns the file.
 *
 * @param interact what to do in the rendered scene before it is captured.
 */
internal fun snapshot(
  name: String,
  size: SnapshotSize,
  theme: SnapshotTheme,
  interact: suspend SnapshotScene.() -> Unit = {},
  content: @Composable () -> Unit,
): File = SnapshotHarness.capture(fileName(name, theme, size), size, interact) {
  KetchTheme(
    darkTheme = theme == SnapshotTheme.Dark,
    density = size.density.toMode(),
    reduceMotion = true,
  ) {
    Box(Modifier.fillMaxSize().background(KetchTheme.colors.canvas)) {
      content()
    }
  }
}

/**
 * Renders the real [App] root over [data] to `<name>-<theme>-<width>x<height>.png` and returns
 * the file.
 *
 * [setup] runs once the app is composed and its task list loaded, so it can open surfaces and
 * interact with the scene; the harness settles again before the capture.
 */
internal fun appSnapshot(
  name: String,
  size: SnapshotSize,
  theme: SnapshotTheme,
  data: SampleData = SampleData.downloads(),
  aiProviderFactory: AiDiscoveryProviderFactory? = null,
  setup: suspend AppScenario.() -> Unit = {},
): File {
  val environment = runBlocking(SnapshotHarness.ui) {
    SampleEnvironment(data, theme, size.density.toMode(), aiProviderFactory)
  }
  try {
    runBlocking(SnapshotHarness.ui) {
      withTimeoutOrNull(START_TIMEOUT) { environment.start() }
        ?: error("The task list of $name never listed every sample task")
    }
    return SnapshotHarness.capture(
      name = fileName(name, theme, size),
      size = size,
      interact = { AppScenario(environment.controller, data, this).setup() },
    ) {
      App(environment.controller)
    }
  } finally {
    runBlocking(SnapshotHarness.ui) { environment.close() }
  }
}

/** Renders [appSnapshot] at each of [sizes] in each of [themes], over fresh [data] each time. */
internal fun appSnapshots(
  name: String,
  sizes: List<SnapshotSize> = SnapshotSize.All,
  themes: List<SnapshotTheme> = SnapshotTheme.entries,
  data: () -> SampleData = SampleData::downloads,
  aiProviderFactory: AiDiscoveryProviderFactory? = null,
  setup: suspend AppScenario.() -> Unit = {},
): List<File> = sizes.flatMap { size ->
  themes.map { theme -> appSnapshot(name, size, theme, data(), aiProviderFactory, setup) }
}

private val START_TIMEOUT = 5.seconds

private fun fileName(name: String, theme: SnapshotTheme, size: SnapshotSize): String =
  "$name-${theme.id}-${size.id}"

private fun KetchDensity.toMode(): DensityMode = when (this) {
  KetchDensity.Compact -> DensityMode.Compact
  KetchDensity.Comfortable -> DensityMode.Comfortable
}
