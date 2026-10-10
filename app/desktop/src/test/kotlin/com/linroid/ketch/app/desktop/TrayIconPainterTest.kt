package com.linroid.ketch.app.desktop

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import java.awt.image.BufferedImage
import java.awt.image.MultiResolutionImage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TrayIconPainterTest {
  @Test
  fun progress_fillsTheSailFromItsFoot() {
    val image = render(TrayIconLook(progress = 0.5f), TrayIconStyle.Template)

    // The sails run from 2.25 to 14.32 of the 20 units, so half fills them up to 8.3.
    assertEquals(77, image.alpha(10, 4), 2)
    assertEquals(255, image.alpha(10, 12))
    assertEquals(255, image.alpha(10, 18))
  }

  @Test
  fun noProgress_showsTheWholeSail() {
    val image = render(TrayIconLook(), TrayIconStyle.Template)

    assertEquals(255, image.alpha(10, 4))
  }

  @Test
  fun failed_dotsTheTopRightCornerWithAGap() {
    val image = render(TrayIconLook(failed = true), TrayIconStyle.AppColors)

    assertEquals(FAILURE_RED, image.getRGB(18, 2))
    // Between the dot and the sail, the gap.
    assertEquals(0, image.alpha(14, 3))
  }

  @Test
  fun appColors_paintTheSailInTheAppIconsGradient() {
    val image = render(TrayIconLook(), TrayIconStyle.AppColors)
    val top = image.getRGB(3, 3)
    val bottom = image.getRGB(17, 17)

    // Light orange at the top left, red orange at the bottom right, fully opaque.
    assertEquals(255, top ushr 24)
    assertTrue(top.green > bottom.green, "top=${top.hex} bottom=${bottom.hex}")
    assertTrue(top.red >= 0xF0 && top.blue < 0x80, "top=${top.hex}")
    assertTrue(bottom.red >= 0xD0 && bottom.blue < 0x50, "bottom=${bottom.hex}")
  }

  @Test
  fun appColors_paused_graysTheSailOut() {
    val image = render(TrayIconLook(dimmed = true), TrayIconStyle.AppColors)
    val pixel = image.getRGB(10, 10)

    assertEquals(255, pixel ushr 24)
    assertEquals(pixel.red, pixel.green)
    assertEquals(pixel.green, pixel.blue)
  }

  @Test
  fun template_paused_fadesTheSail() {
    val image = render(TrayIconLook(dimmed = true), TrayIconStyle.Template)

    assertEquals(115, image.alpha(10, 10), 2)
  }

  // [look] in [style] at 20 by 20 pixels, the sail standing in as a square filling the icon.
  private fun render(look: TrayIconLook, style: TrayIconStyle): BufferedImage {
    val painter = TrayIconPainter(ColorPainter(Color.Black), look, style)
    val image = painter.toAwtImage(Density(1f), LayoutDirection.Ltr, Size(SIZE, SIZE))
    return (image as MultiResolutionImage).getResolutionVariant(20.0, 20.0) as BufferedImage
  }

  private fun BufferedImage.alpha(x: Int, y: Int): Int = getRGB(x, y) ushr 24

  private fun assertEquals(expected: Int, actual: Int, tolerance: Int) {
    assertTrue(actual in expected - tolerance..expected + tolerance, "$actual is not $expected")
  }

  private val Int.red: Int get() = (this shr 16) and 0xFF
  private val Int.green: Int get() = (this shr 8) and 0xFF
  private val Int.blue: Int get() = this and 0xFF
  private val Int.hex: String get() = Integer.toHexString(this)

  private companion object {
    const val SIZE = 20f
    const val FAILURE_RED = 0xFFE5202E.toInt()
  }
}
