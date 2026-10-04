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
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.linroid.ketch.app.App
import com.linroid.ketch.app.input.KeyboardPlatform
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
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.swing.Swing
import kotlinx.coroutines.withTimeoutOrNull
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import org.junit.Assume.assumeTrue
import java.io.File
import java.util.TimeZone
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.math.roundToInt
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

  /**
   * The one thread that composes, renders and runs the app's commands: the AWT event thread, as
   * in the desktop app.
   *
   * Compose posts work to that thread whichever thread drives the scene: a frame that lays out
   * nodes asks for its `RectManager` to dispatch, 16 ms later, the callbacks of node bounds and
   * compact its list of them, and the frame's end cancels that. A frame slower than 16 ms, as on
   * a busy machine, lets it run in the middle of the frame, and from another thread the two
   * change the list at once: a node's bounds go missing, and a later layout or disposal fails
   * with "LayoutNode … not found in RectList".
   */
  val ui: CoroutineDispatcher = Dispatchers.Swing

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
  ): File = write(name, render(size.width, size.height, SCALE, interact, content))

  /**
   * Composes [content] in a [width] by [height] window at [scale] pixels per dp, settles it,
   * runs [interact], settles again and returns the frame.
   */
  fun render(
    width: Dp,
    height: Dp,
    scale: Float = SCALE,
    interact: suspend SnapshotScene.() -> Unit = {},
    content: @Composable () -> Unit,
  ): Image = withScene(
    width = (width.value * scale).roundToInt(),
    height = (height.value * scale).roundToInt(),
    density = Density(scale),
    content = content,
  ) {
    val snapshotScene = SnapshotScene(this, scale)
    snapshotScene.settle()
    snapshotScene.interact()
    snapshotScene.settle()
    snapshotScene.renderFrame()
  }

  /** Writes [image] to `<name>.png` in [outputDir] and returns the file. */
  fun write(name: String, image: Image): File {
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

/**
 * Runs [block] on [SnapshotHarness.ui] and waits for it, for a test that creates, drives and
 * closes an [ImageComposeScene] of its own.
 */
internal fun <T> onUiThread(block: suspend CoroutineScope.() -> T): T =
  runBlocking(SnapshotHarness.ui, block)

/**
 * Composes [content] in a [width] by [height] pixel scene at [density] on [SnapshotHarness.ui],
 * runs [test] on it and closes it.
 */
internal fun <T> withScene(
  width: Int,
  height: Int,
  density: Density = Density(1f),
  content: @Composable () -> Unit,
  test: suspend ImageComposeScene.() -> T,
): T = runBlocking(SnapshotHarness.ui) {
  // Compose logs an exception thrown while recomposing and carries on, so a broken composition
  // would still render a picture; the handler collects it to fail the scene instead.
  val errors = ConcurrentLinkedQueue<Throwable>()
  val scene = ImageComposeScene(
    width = width,
    height = height,
    density = density,
    coroutineContext = SnapshotHarness.ui +
      CoroutineExceptionHandler { _, error -> errors += error },
    content = content,
  )
  try {
    scene.test().also {
      errors.peek()?.let { throw AssertionError("The scene failed while composing", it) }
    }
  } finally {
    scene.close()
  }
}

/** Renders [count] frames 16 ms apart, each at the time [clock] gives for its index. */
internal suspend fun ImageComposeScene.frames(
  count: Int,
  clock: (Int) -> Long = { System.nanoTime() },
) {
  repeat(count) { frame ->
    render(clock(frame))
    delay(16.milliseconds)
  }
}

/** Presses and releases [key] with the modifiers that are set. */
@OptIn(InternalComposeUiApi::class)
internal fun ImageComposeScene.sendKey(
  key: Key,
  meta: Boolean = false,
  ctrl: Boolean = false,
  alt: Boolean = false,
  shift: Boolean = false,
) {
  // Compose has no public way to make a key event without a window.
  for (type in listOf(KeyEventType.KeyDown, KeyEventType.KeyUp)) {
    val event = KeyEvent(
      key = key,
      type = type,
      isMetaPressed = meta,
      isCtrlPressed = ctrl,
      isAltPressed = alt,
      isShiftPressed = shift,
    )
    sendKeyEvent(event)
  }
}

/** Every semantics node of the scene, unmerged, parents before their children. */
internal fun ImageComposeScene.nodes(): List<SemanticsNode> =
  semanticsOwners.flatMap { it.unmergedRootSemanticsNode.withDescendants() }

private fun SemanticsNode.withDescendants(): List<SemanticsNode> =
  listOf(this) + children.flatMap { it.withDescendants() }

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
 * show keyboard focus or hovering a row. Positions are in dp, [scale] pixels each. Each
 * interaction settles before it returns.
 */
internal class SnapshotScene(
  private val scene: ImageComposeScene,
  private val scale: Float = SnapshotHarness.SCALE,
) {
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

  /**
   * Presses and releases [key], with Shift held when [shift] is set and the platform's primary
   * modifier, ⌘ or Ctrl, when [primary] is.
   */
  suspend fun pressKey(key: Key, shift: Boolean = false, primary: Boolean = false) {
    val apple = KeyboardPlatform.current.isApple
    scene.sendKey(key, meta = primary && apple, ctrl = primary && !apple, shift = shift)
    settle(minimum = INTERACTION_SETTLE)
  }

  /** Moves the pointer to [x], [y] from the top left, for hover states. */
  suspend fun hover(x: Dp, y: Dp) {
    scene.sendPointerEvent(PointerEventType.Move, offset(x, y))
    settle(minimum = INTERACTION_SETTLE)
  }

  /**
   * Turns the mouse wheel over [x], [y] by [ticks]: negative scrolls toward the top, as a
   * desktop list scrolls by the wheel and never by a drag.
   */
  suspend fun scroll(x: Dp, y: Dp, ticks: Float) {
    scene.sendPointerEvent(
      eventType = PointerEventType.Scroll,
      position = offset(x, y),
      scrollDelta = Offset(0f, ticks),
    )
    settle(minimum = INTERACTION_SETTLE)
  }

  /**
   * Clicks the middle of the first control whose content description, such as a button's
   * tooltip, is [description].
   */
  suspend fun clickOn(description: String) {
    val node = scene.nodes().firstOrNull {
      it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(description) == true
    }
    val center = checkNotNull(node) { "Nothing on screen is described as $description" }
      .boundsInRoot.center
    click((center.x / scale).dp, (center.y / scale).dp)
  }

  /** Clicks the middle of the first text on screen that reads [text], such as a link's. */
  suspend fun clickOnText(text: String) {
    val node = scene.nodes().firstOrNull { node ->
      node.config.getOrNull(SemanticsProperties.Text)?.any { it.text == text } == true
    }
    val center = checkNotNull(node) { "Nothing on screen reads $text" }.boundsInRoot.center
    click((center.x / scale).dp, (center.y / scale).dp)
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

  /**
   * Drags with the primary button from [fromX], [fromY] to [toX], [toY] in [steps] moves, as a
   * finger pulls a sheet up. With [release] unset the button stays down, so what follows the
   * finger stays where it was dragged to instead of settling.
   */
  suspend fun drag(
    fromX: Dp,
    fromY: Dp,
    toX: Dp,
    toY: Dp,
    steps: Int = DRAG_STEPS,
    release: Boolean = true,
  ) {
    val from = offset(fromX, fromY)
    val to = offset(toX, toY)
    val pressed = PointerButtons(isPrimaryPressed = true)
    scene.sendPointerEvent(PointerEventType.Move, from)
    scene.sendPointerEvent(
      eventType = PointerEventType.Press,
      position = from,
      buttons = pressed,
      button = PointerButton.Primary,
    )
    for (step in 1..steps) {
      delay(FRAME)
      val position = from + (to - from) * (step.toFloat() / steps)
      scene.sendPointerEvent(PointerEventType.Move, position, buttons = pressed)
      renderFrame()
    }
    if (release) {
      scene.sendPointerEvent(
        eventType = PointerEventType.Release,
        position = to,
        buttons = PointerButtons(),
        button = PointerButton.Primary,
      )
    }
    settle(minimum = INTERACTION_SETTLE)
  }

  /** Renders the current frame. */
  fun renderFrame(): Image = scene.render(start.elapsedNow().inWholeNanoseconds)

  private fun offset(x: Dp, y: Dp): Offset = Offset(x.value * scale, y.value * scale)

  private companion object {
    val FRAME = 16.milliseconds
    val MINIMUM_SETTLE = 600.milliseconds
    val MAXIMUM_SETTLE = 4.seconds
    val INTERACTION_SETTLE = 150.milliseconds
    const val QUIET_FRAMES = 6
    const val DRAG_STEPS = 12
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

  /** Shows the task named [name] in the inspector. */
  fun inspect(name: String) {
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

  /** Opens the device switcher, or the device sheet on phones, and lets it settle in. */
  suspend fun openDevices() {
    state.showInstanceSelector = true
    // A popup that fades in needs frames of its own before the capture settles.
    scene.settle()
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
): File = withSample(theme, size.density.toMode(), data, aiProviderFactory) {
  captureApp(name, size, theme, it, setup)
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

/**
 * An app a snapshot renders: the controller its root shows and the devices and downloads it
 * shows; see [withEnvironment].
 */
internal interface SnapshotEnvironment {
  /** The controller the app root shows. */
  val controller: AppController

  /** The devices and downloads it shows. */
  val data: SampleData

  /** Gets the app ready to render, such as by waiting until it lists every sample task. */
  suspend fun start() {}

  /** Closes the controller and the devices. */
  fun close()
}

/**
 * Creates the environment [create] makes, starts it within [timeout], runs [block] with it and
 * closes it; the environment is created, started and closed on [SnapshotHarness.ui].
 */
internal fun <E : SnapshotEnvironment, T> withEnvironment(
  create: () -> E,
  timeout: Duration = START_TIMEOUT,
  block: (E) -> T,
): T {
  val environment = runBlocking(SnapshotHarness.ui) { create() }
  try {
    runBlocking(SnapshotHarness.ui) {
      withTimeoutOrNull(timeout) { environment.start() }
        ?: error("${environment::class.simpleName} never got ready")
    }
    return block(environment)
  } finally {
    runBlocking(SnapshotHarness.ui) { environment.close() }
  }
}

/** Runs [block] over a started [SampleEnvironment] of [data]; see [withEnvironment]. */
internal fun <T> withSample(
  theme: SnapshotTheme,
  density: DensityMode = DensityMode.Compact,
  data: SampleData = SampleData.downloads(),
  aiProviderFactory: AiDiscoveryProviderFactory? = null,
  block: (SampleEnvironment) -> T,
): T = withEnvironment(
  create = { SampleEnvironment(data, theme, density, aiProviderFactory) },
  block = block,
)

/**
 * Renders the real [App] root of [environment] to `<name>-<theme>-<width>x<height>.png` and
 * returns the file; [setup] runs once the app is composed, like [appSnapshot]'s.
 */
internal fun captureApp(
  name: String,
  size: SnapshotSize,
  theme: SnapshotTheme,
  environment: SnapshotEnvironment,
  setup: suspend AppScenario.() -> Unit = {},
): File = SnapshotHarness.capture(
  name = fileName(name, theme, size),
  size = size,
  interact = { AppScenario(environment.controller, environment.data, this).setup() },
) {
  App(environment.controller)
}

private val START_TIMEOUT = 5.seconds

private fun fileName(name: String, theme: SnapshotTheme, size: SnapshotSize): String =
  "$name-${theme.id}-${size.id}"

/** The [DensityMode] the app's settings name [this] by. */
internal fun KetchDensity.toMode(): DensityMode = when (this) {
  KetchDensity.Compact -> DensityMode.Compact
  KetchDensity.Comfortable -> DensityMode.Comfortable
}
