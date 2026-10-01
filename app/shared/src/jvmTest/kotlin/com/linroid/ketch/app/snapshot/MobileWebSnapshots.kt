package com.linroid.ketch.app.snapshot

import androidx.compose.runtime.remember
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.linroid.ketch.app.theme.KetchDensity
import com.linroid.ketch.app.ui.onboarding.KetchSplash
import com.linroid.ketch.app.ui.onboarding.WelcomeFlow
import com.linroid.ketch.app.ui.onboarding.WelcomePlatform
import com.linroid.ketch.app.ui.onboarding.WelcomeState
import com.linroid.ketch.app.ui.onboarding.WelcomeStep
import com.linroid.ketch.config.AppearanceConfig
import com.linroid.ketch.config.DensityMode
import com.linroid.ketch.config.ThemeMode
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.BeforeTest
import kotlin.test.Test

/**
 * Phone onboarding and the Android splash (W4-MOBILE-WEB): each welcome step on Android and iOS
 * phones, a tablet and a landscape phone, the chosen and failed folder, keyboard focus, the
 * splash shown while the Android service binds, and the phone's Add button moving aside while
 * the list scrolls; see [SnapshotHarness] for how to run it.
 */
class MobileWebSnapshots {
  @BeforeTest
  fun enabled() = requireSnapshots()

  @Test
  fun welcome_androidSteps_showEachStep() {
    for (theme in SnapshotTheme.entries) {
      for (step in WelcomeStep.entries) {
        welcome("welcome-android-${step.name.lowercase()}", Phone, theme, WelcomePlatform.Android) {
          WelcomeState(step)
        }
      }
    }
  }

  @Test
  fun welcome_iosSteps_showEachStep() {
    for (theme in SnapshotTheme.entries) {
      for (step in WelcomeStep.entries) {
        welcome("welcome-ios-${step.name.lowercase()}", Phone, theme, WelcomePlatform.Ios) {
          WelcomeState(step)
        }
      }
    }
  }

  @Test
  fun welcome_folderChosen_confirmsIt() {
    for (theme in SnapshotTheme.entries) {
      welcome("welcome-android-folder-chosen", Phone, theme, WelcomePlatform.Android) {
        WelcomeState(folder = DOWNLOAD_TREE)
      }
    }
  }

  @Test
  fun welcome_folderFailed_saysWhy() {
    welcome("welcome-android-folder-error", Phone, SnapshotTheme.Light, WelcomePlatform.Android) {
      WelcomeState().apply {
        runBlocking { chooseFolder(pick = { DOWNLOAD_TREE }, apply = { error("No grant") }) }
      }
    }
  }

  @Test
  fun welcome_smallAndWideScreens_fit() {
    for (theme in SnapshotTheme.entries) {
      welcome("welcome-android-use", Small, theme, WelcomePlatform.Android) {
        WelcomeState(WelcomeStep.Use)
      }
      welcome("welcome-android-intake", Landscape, theme, WelcomePlatform.Android) {
        WelcomeState(WelcomeStep.Intake)
      }
      welcome("welcome-ios-use", Tablet, theme, WelcomePlatform.Ios, noun = "This iPad") {
        WelcomeState(WelcomeStep.Use)
      }
    }
  }

  @Test
  fun welcome_keyboard_showsFocus() {
    for (theme in SnapshotTheme.entries) {
      welcome(
        name = "welcome-android-use-focus",
        size = Tablet,
        theme = theme,
        platform = WelcomePlatform.Android,
        noun = "This tablet",
        interact = { repeat(FOCUS_STEPS) { pressKey(Key.Tab) } },
      ) {
        WelcomeState(WelcomeStep.Use)
      }
    }
  }

  @Test
  fun downloads_phoneListScrolled_movesTheAddButtonAside() {
    for (theme in SnapshotTheme.entries) {
      appSnapshot("downloads-scrolled", Phone, theme) { scene.wheel(LIST_X, LIST_Y, SCROLL_DOWN) }
      appSnapshot("downloads-scrolled-back", Phone, theme) {
        scene.wheel(LIST_X, LIST_Y, SCROLL_DOWN)
        scene.wheel(LIST_X, LIST_Y, SCROLL_UP)
      }
    }
  }

  @Test
  fun splash_savedAppearance_showsTheLanes() {
    for (theme in SnapshotTheme.entries) {
      val mode = if (theme == SnapshotTheme.Dark) ThemeMode.Dark else ThemeMode.Light
      snapshot("splash", Phone, theme) { KetchSplash(AppearanceConfig(theme = mode)) }
    }
  }

  private fun welcome(
    name: String,
    size: SnapshotSize,
    theme: SnapshotTheme,
    platform: WelcomePlatform,
    noun: String = "This phone",
    interact: suspend SnapshotScene.() -> Unit = {},
    state: () -> WelcomeState,
  ): File {
    val environment = runBlocking(SnapshotHarness.ui) {
      SampleEnvironment(SampleData.empty(), theme, size.densityMode)
    }
    try {
      return snapshot(name, size, theme, interact) {
        WelcomeFlow(
          state = environment.controller.state,
          platform = platform,
          onDone = {},
          deviceNoun = noun,
          welcome = remember { state() },
        )
      }
    } finally {
      runBlocking(SnapshotHarness.ui) { environment.close() }
    }
  }

  // Turns the mouse wheel over [x], [y]; the harness only clicks, hovers and types.
  private suspend fun SnapshotScene.wheel(x: Dp, y: Dp, ticks: Float) {
    val field = SnapshotScene::class.java.getDeclaredField("scene").apply { isAccessible = true }
    val scene = field.get(this) as ImageComposeScene
    val scale = SnapshotHarness.SCALE
    val position = Offset(x.value * scale, y.value * scale)
    scene.sendPointerEvent(PointerEventType.Move, position)
    scene.sendPointerEvent(PointerEventType.Scroll, position, scrollDelta = Offset(0f, ticks))
    settle()
  }

  private val SnapshotSize.densityMode: DensityMode
    get() = if (density == KetchDensity.Compact) DensityMode.Compact else DensityMode.Comfortable

  private companion object {
    val Phone = SnapshotSize.Phone
    val Small = SnapshotSize(360.dp, 640.dp, KetchDensity.Comfortable)
    val Landscape = SnapshotSize(844.dp, 390.dp, KetchDensity.Comfortable)
    val Tablet = SnapshotSize(820.dp, 1180.dp, KetchDensity.Comfortable)

    // Where the wheel turns on the phone's list, and by how many notches each way.
    val LIST_X = 195.dp
    val LIST_Y = 500.dp
    const val SCROLL_DOWN = 24f
    const val SCROLL_UP = -6f

    // Tab presses past Back and Skip to the first card.
    const val FOCUS_STEPS = 3

    const val DOWNLOAD_TREE =
      "content://com.android.externalstorage.documents/tree/primary%3ADownload"
  }
}
