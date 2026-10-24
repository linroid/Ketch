package com.linroid.ketch.app.desktop

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.linroid.ketch.app.icons.KetchIcon
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import org.jetbrains.skia.Surface
import org.junit.Assume.assumeTrue
import java.io.File
import java.util.Locale
import kotlin.math.roundToInt
import kotlin.test.BeforeTest
import kotlin.test.Test

/**
 * The tray menu Ketch draws on Windows, over a desktop with a taskbar along its bottom edge,
 * opened by a right click on the icon near its right end; the PNGs show the desktop's right part.
 * Run with
 * `./gradlew :app:desktop:test -Psnapshots --tests '*TrayMenuSnapshots*'`; the PNGs land in
 * `app/desktop/build/snapshots/` as `tray-menu-<scenario>-<theme>.png`.
 */
class TrayMenuSnapshots {
  private val menu = TraySamples.menu

  @BeforeTest
  fun requireSnapshots() {
    assumeTrue("Snapshots run with -Psnapshots", System.getProperty("ketch.snapshots") == "true")
  }

  @Test
  fun menu() = themes { dark -> capture("menu", dark) }

  @Test
  fun devices() = themes { dark ->
    val devices = TraySamples.submenuWith(menu) { it == MenuAction.AddDevice }
    val pc = devices.entries.first() as MenuEntry.Submenu
    val speed = TraySamples.submenuWith(pc.entries) { it is MenuAction.SetSpeedMode }
    capture("devices", dark) {
      press(Key.DirectionDown, times = TraySamples.downsTo(devices))
      press(Key.DirectionRight, times = 2)
      press(Key.DirectionDown, times = TraySamples.downsTo(speed, pc.entries) - 1)
      press(Key.DirectionRight)
    }
  }

  @Test
  fun zoomed() = themes { dark ->
    val speed = TraySamples.submenuWith(menu) { it is MenuAction.SetSpeedMode }
    capture("zoom-150", dark, zoom = 1.5f) {
      press(Key.DirectionDown, times = TraySamples.downsTo(speed))
      press(Key.DirectionRight)
    }
  }

  @Test
  fun chinese() {
    val system = Locale.getDefault()
    Locale.setDefault(Locale.SIMPLIFIED_CHINESE)
    try {
      val recent = TraySamples.submenuWith(menu) { it is MenuAction.OpenFile }
      themes { dark ->
        capture("zh", dark) {
          press(Key.DirectionDown, times = TraySamples.downsTo(recent))
          press(Key.DirectionRight)
        }
      }
    } finally {
      Locale.setDefault(system)
    }
  }

  private fun themes(block: (dark: Boolean) -> Unit) {
    block(false)
    block(true)
  }

  // Renders the work area with the menu over the desktop, then the taskbar under it.
  private fun capture(
    name: String,
    dark: Boolean,
    zoom: Float = 1f,
    interact: TrayMenuScene.() -> Unit = {},
  ) {
    val width = px(WIDTH)
    val area = px(AREA_HEIGHT)
    val taskbar = px(TASKBAR_HEIGHT)
    val pointer = IntOffset(px(POINTER_X), area + taskbar / 2)
    val menuFrame = TrayMenuScene(
      width = width,
      height = area,
      scale = SCALE,
      pointer = pointer,
      entries = menu,
      darkTheme = dark,
      zoom = zoom,
      backdrop = { content -> Desktop(dark, content) },
    ).use { scene ->
      scene.settle()
      scene.interact()
      scene.image()
    }
    val taskbarScene = ImageComposeScene(width, taskbar, Density(SCALE)) { Taskbar(dark) }
    val taskbarFrame = try {
      taskbarScene.render(0)
    } finally {
      taskbarScene.close()
    }
    val crop = px(CROP_WIDTH)
    val left = (crop - width).toFloat()
    val surface = Surface.makeRasterN32Premul(crop, area + taskbar)
    surface.canvas.drawImage(menuFrame, left, 0f)
    surface.canvas.drawImage(taskbarFrame, left, area.toFloat())
    write("tray-menu-$name-${if (dark) "dark" else "light"}", surface.makeImageSnapshot())
  }

  private fun write(name: String, image: Image) {
    val data = checkNotNull(image.encodeToData(EncodedImageFormat.PNG)) { "Couldn't encode $name" }
    val dir = File(System.getProperty("ketch.snapshots.dir") ?: "build/snapshots")
    dir.mkdirs()
    File(dir, "$name.png").writeBytes(data.bytes)
  }

  private fun px(dp: Int): Int = (dp * SCALE).roundToInt()

  private companion object {
    const val SCALE = 2f
    const val WIDTH = 1920
    const val CROP_WIDTH = 1040
    const val AREA_HEIGHT = 640
    const val TASKBAR_HEIGHT = 48
    const val POINTER_X = 1860
  }
}

// A plain desktop wallpaper behind the menu.
@Composable
private fun Desktop(dark: Boolean, content: @Composable () -> Unit) {
  val colors = if (dark) {
    listOf(Color(0xFF1C2433), Color(0xFF0F141C))
  } else {
    listOf(Color(0xFFD6E2F0), Color(0xFFAFC3DC))
  }
  Box(Modifier.fillMaxSize().background(Brush.linearGradient(colors))) { content() }
}

// A Windows 11 style taskbar with Ketch's tray icon under the pointer.
@Composable
private fun Taskbar(dark: Boolean) {
  val color = if (dark) Color(0xFF202020) else Color(0xFFF3F3F3)
  val sail = rememberVectorPainter(KetchIcon.Sail.imageVector)
  val icon = TrayIconPainter(sail, TrayIconLook(progress = 0.42f), TrayIconStyle.AppColors)
  Box(Modifier.fillMaxSize().background(color), contentAlignment = Alignment.CenterStart) {
    Image(icon, contentDescription = null, Modifier.offset(x = 1852.dp).size(16.dp))
  }
}
