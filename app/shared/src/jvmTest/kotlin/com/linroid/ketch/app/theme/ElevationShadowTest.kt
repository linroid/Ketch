package com.linroid.ketch.app.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.GenericShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

/** [elevationShadow] draws what `Modifier.dropShadow` draws, without its per-size bitmaps. */
class ElevationShadowTest {
  @Test
  fun elevationShadow_everyElevation_matchesDropShadow() {
    for (dark in listOf(false, true)) {
      val elevation = ketchElevation(dark)
      for (level in KetchElevationLevel.entries) {
        assertSameShadows("dark=$dark $level", Card, elevation.shadows(level))
      }
    }
  }

  @Test
  fun elevationShadow_strongShadowOfEachShape_matchesDropShadow() {
    val shapes = mapOf(
      "rectangle" to RectangleShape,
      "circle" to CircleShape,
      "rounded" to Card,
      "path" to GenericShape { size, _ ->
        moveTo(size.width / 2, 0f)
        lineTo(size.width, size.height)
        lineTo(0f, size.height)
        close()
      },
    )
    for ((name, shape) in shapes) {
      for (spread in listOf(-8, 0, 6)) {
        // dropShadow moves a path's shadow by a negative spread and crops it; surfaces only use
        // rounded rectangles, so that case is left out.
        if (name == "path" && spread < 0) continue
        val shadow = Shadow(
          radius = 24.dp,
          color = Color.Black,
          spread = spread.dp,
          offset = DpOffset(4.dp, 12.dp),
        )
        assertSameShadows("$name spread=$spread", shape, listOf(shadow))
      }
    }
  }

  private fun assertSameShadows(label: String, shape: Shape, shadows: List<Shadow>) {
    val expected = render {
      shadows.fold<Shadow, Modifier>(Modifier) { m, s -> m.dropShadow(shape, s) }
    }
    val actual = render {
      shadows.fold<Shadow, Modifier>(Modifier) { m, s -> m.elevationShadow(shape, s) }
    }
    var worst = 0
    for (y in 0 until HEIGHT) {
      for (x in 0 until WIDTH) {
        val a = expected[x, y]
        val b = actual[x, y]
        val diff = maxOf(abs(a.red - b.red), abs(a.green - b.green), abs(a.blue - b.blue))
        worst = maxOf(worst, (diff * 255).toInt())
      }
    }
    assertTrue(worst <= TOLERANCE, "$label: channels differ by up to $worst of 255")
  }

  private fun render(shadows: () -> Modifier): PixelMap {
    val scene = ImageComposeScene(width = WIDTH, height = HEIGHT, density = Density(2f)) {
      Box(Modifier.fillMaxSize().background(Color.White)) {
        Box(Modifier.offset(60.dp, 40.dp).size(120.dp, 70.dp).then(shadows()))
      }
    }
    try {
      return scene.render().toComposeImageBitmap().toPixelMap()
    } finally {
      scene.close()
    }
  }

  private companion object {
    const val WIDTH = 480
    const val HEIGHT = 360

    /**
     * The blur's own approximations: up to 9 of 255 for an opaque black shadow and 4 for the
     * elevations, not a visible change.
     */
    const val TOLERANCE = 10
    val Card = RoundedCornerShape(12.dp)
  }
}
