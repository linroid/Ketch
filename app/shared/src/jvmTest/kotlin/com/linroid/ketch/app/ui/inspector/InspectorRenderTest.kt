package com.linroid.ketch.app.ui.inspector

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.linroid.ketch.api.SpeedLimit
import com.linroid.ketch.app.snapshot.SampleData
import com.linroid.ketch.app.snapshot.SampleEnvironment
import com.linroid.ketch.app.snapshot.SnapshotHarness
import com.linroid.ketch.app.snapshot.SnapshotTheme
import com.linroid.ketch.app.state.AppState
import com.linroid.ketch.app.state.TaskKey
import com.linroid.ketch.app.theme.KetchTheme
import com.linroid.ketch.config.DensityMode
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** The inspector's controls driven through a rendered scene, with pointer and keys. */
class InspectorRenderTest {
  @Test
  fun logSpeedSlider_drag_commitsOnceOnRelease() {
    val commits = mutableListOf<SpeedLimit>()
    val previews = mutableListOf<SpeedLimit?>()
    runScene(
      content = {
        LogSpeedSlider(
          value = SpeedLimit.Unlimited,
          onCommit = { commits += it },
          onPreview = { previews += it },
        )
      },
    ) { scene ->
      val y = SLIDER_HEIGHT / 2f
      scene.press(Offset(40f, y))
      for (x in listOf(80f, 120f, 160f, 200f)) {
        scene.sendPointerEvent(PointerEventType.Move, Offset(x, y), buttons = PRIMARY)
        frames(scene, 50.milliseconds)
      }
      scene.release(Offset(200f, y))
      frames(scene, 200.milliseconds)
    }

    val inset = 8f
    assertEquals(listOf(SpeedScale.limitAt((200f - inset) / (WIDTH - inset * 2))), commits)
    assertTrue(previews.size > 2, "Dragging previews the limit under the thumb: $previews")
    assertEquals(null, previews.last())
  }

  @Test
  fun logSpeedSlider_arrowKeys_commitOnceAfterAPause() {
    val commits = mutableListOf<SpeedLimit>()
    runScene(
      content = {
        val focus = remember { FocusRequester() }
        LogSpeedSlider(
          value = SpeedLimit.mbps(1),
          onCommit = { commits += it },
          modifier = Modifier.focusRequester(focus),
        )
        LaunchedEffect(Unit) { focus.requestFocus() }
      },
    ) { scene ->
      repeat(3) {
        scene.key(Key.DirectionRight)
        frames(scene, 50.milliseconds)
      }
      assertEquals(emptyList(), commits)
      frames(scene, 600.milliseconds)
    }

    assertEquals(listOf(SpeedLimit.mbps(10)), commits)
  }

  @Test
  fun speedControl_typedLimit_commitsOnceAfterThePause() {
    val commits = mutableListOf<SpeedLimit>()
    runScene(height = POPOVER_SCENE_HEIGHT, content = { SpeedControlUnderTest(commits) }) { scene ->
      scene.openCustomField()
      for (text in listOf("7", "75", "750", "750k")) {
        scene.typeIntoField(text)
        frames(scene, 100.milliseconds)
      }
      assertEquals(emptyList(), commits)
      frames(scene, 1.seconds)
    }

    assertEquals(listOf(SpeedLimit.kbps(750)), commits)
  }

  @Test
  fun speedControl_closedWhileTyping_commitsTheTypedLimitOnce() {
    val commits = mutableListOf<SpeedLimit>()
    runScene(height = POPOVER_SCENE_HEIGHT, content = { SpeedControlUnderTest(commits) }) { scene ->
      scene.openCustomField()
      scene.typeIntoField("3m")
      frames(scene, 100.milliseconds)
      scene.key(Key.Escape)
      frames(scene, 1.seconds)
      assertTrue(scene.texts().none { it == "Custom…" }, "The popover closed: ${scene.texts()}")
    }

    assertEquals(listOf(SpeedLimit.mbps(3)), commits)
  }

  @Test
  fun inspector_urgentWithEverySlotTaken_asksBeforeStarting() {
    inspectSample(QUEUED) { state, key, scene ->
      val starting = "start now"
      scene.press(scene.centerOf("Urgent"))
      scene.release(scene.centerOf("Urgent"))
      frames(scene, 300.milliseconds)
      assertTrue(scene.texts().any { it.startsWith("Starts now; may pause") }, "${scene.texts()}")
      assertTrue(state.pendingLabels(key).none { it == starting })

      val confirm = scene.centersOf("Start now").last()
      scene.press(confirm)
      scene.release(confirm)
      frames(scene, 100.milliseconds)
      assertTrue(starting in state.pendingLabels(key), "${state.pending.value}")
    }
  }

  @Test
  fun inspector_scheduledTask_startShowsTheTimeItWaitsFor() {
    inspectSample(SCHEDULED) { _, _, scene ->
      val texts = scene.texts()
      // The reason line and the Start control both name the time.
      assertEquals(2, texts.count { it.startsWith("Starts ") }, "$texts")
      assertTrue("Now" !in texts, "$texts")
    }
  }

  /** Renders the docked inspector of the sample download [name] and runs [test] on it. */
  private fun inspectSample(
    name: String,
    test: suspend (AppState, TaskKey, ImageComposeScene) -> Unit,
  ) {
    val data = SampleData.downloads()
    val environment = runBlocking(SnapshotHarness.ui) {
      SampleEnvironment(data, SnapshotTheme.Light, DensityMode.Compact)
    }
    try {
      runBlocking(SnapshotHarness.ui) { withTimeout(5.seconds) { environment.start() } }
      val state = environment.controller.state
      val key = data.keyOf(name)
      runScene(
        width = INSPECTOR_WIDTH,
        height = INSPECTOR_HEIGHT,
        content = {
          Box(Modifier.width(INSPECTOR_WIDTH.dp)) {
            TaskInspector(state, key, InspectorPlacement.Docked, onClose = {})
          }
        },
      ) { scene -> test(state, key, scene) }
    } finally {
      runBlocking(SnapshotHarness.ui) { environment.close() }
    }
  }

  @Composable
  private fun SpeedControlUnderTest(commits: MutableList<SpeedLimit>) {
    SpeedControl(
      value = SpeedLimit.Unlimited,
      presets = listOf(SpeedLimit.mbps(5), SpeedLimit.mbps(2), SpeedLimit.mbps(1)),
      onCommit = { commits += it },
      globalCap = SpeedLimit.Unlimited,
      globalName = "Global limit",
      pending = false,
    )
  }

  /** Opens the Speed control's popover from its last segment, then its Custom… field. */
  private suspend fun ImageComposeScene.openCustomField() {
    nodes().last { it.config.getOrNull(SemanticsProperties.Role) == Role.Tab }.click()
    frames(this, 300.milliseconds)
    nodes().first { it.text() == "Custom…" }.click()
    frames(this, 300.milliseconds)
  }

  private fun ImageComposeScene.typeIntoField(text: String) {
    val field = nodes().first { SemanticsActions.SetText in it.config }
    field.config[SemanticsActions.SetText].action?.invoke(AnnotatedString(text))
  }

  /** Clicks [this] node, or the closest clickable one around it. */
  private fun SemanticsNode.click() {
    var node: SemanticsNode? = this
    while (node != null && SemanticsActions.OnClick !in node.config) node = node.parent
    val action = node?.config?.get(SemanticsActions.OnClick)?.action
    assertTrue(action != null, "Nothing to click around $this")
    action()
  }

  private fun AppState.pendingLabels(key: TaskKey): List<String> =
    pending.value.filter { it.first == key }.map { it.second }

  private fun runScene(
    width: Int = WIDTH.toInt(),
    height: Int = SLIDER_HEIGHT,
    content: @Composable () -> Unit,
    test: suspend (ImageComposeScene) -> Unit,
  ) {
    runBlocking(SnapshotHarness.ui) {
      val scene = ImageComposeScene(
        width = width,
        height = height,
        density = Density(1f),
        coroutineContext = SnapshotHarness.ui,
      ) {
        KetchTheme(darkTheme = false, density = DensityMode.Compact, reduceMotion = true) {
          content()
        }
      }
      try {
        frames(scene, 300.milliseconds)
        test(scene)
      } finally {
        scene.close()
      }
    }
  }

  private suspend fun frames(scene: ImageComposeScene, duration: Duration) {
    repeat((duration / FRAME).toInt().coerceAtLeast(1)) {
      scene.render(System.nanoTime())
      delay(FRAME)
    }
  }

  private fun ImageComposeScene.press(at: Offset) {
    sendPointerEvent(PointerEventType.Move, at)
    sendPointerEvent(PointerEventType.Press, at, buttons = PRIMARY, button = PointerButton.Primary)
  }

  private fun ImageComposeScene.release(at: Offset) {
    sendPointerEvent(
      eventType = PointerEventType.Release,
      position = at,
      buttons = PointerButtons(),
      button = PointerButton.Primary,
    )
  }

  @OptIn(InternalComposeUiApi::class)
  private fun ImageComposeScene.key(key: Key) {
    for (type in listOf(KeyEventType.KeyDown, KeyEventType.KeyUp)) {
      sendKeyEvent(KeyEvent(key = key, type = type))
    }
  }

  private fun ImageComposeScene.nodes(): List<SemanticsNode> =
    semanticsOwners.flatMap { it.unmergedRootSemanticsNode.all() }

  private fun SemanticsNode.all(): List<SemanticsNode> =
    listOf(this) + children.flatMap { it.all() }

  private fun SemanticsNode.text(): String? =
    config.getOrNull(SemanticsProperties.Text)?.joinToString { it.text }

  private fun ImageComposeScene.texts(): List<String> = nodes().mapNotNull { it.text() }

  private fun ImageComposeScene.centersOf(text: String): List<Offset> =
    nodes().filter { it.text() == text }.map { it.boundsInRoot.center }

  private fun ImageComposeScene.centerOf(text: String): Offset =
    centersOf(text).firstOrNull() ?: error("No \"$text\" in ${texts()}")

  private companion object {
    const val WIDTH = 300f
    const val SLIDER_HEIGHT = 28
    const val INSPECTOR_WIDTH = 320
    const val INSPECTOR_HEIGHT = 1400
    const val POPOVER_SCENE_HEIGHT = 480
    const val QUEUED = "blender-4.2-macos-arm64.dmg"
    const val SCHEDULED = "llama-3.1-8b-instruct-q4_k_m.gguf"
    val FRAME = 16.milliseconds
    val PRIMARY = PointerButtons(isPrimaryPressed = true)
  }
}
