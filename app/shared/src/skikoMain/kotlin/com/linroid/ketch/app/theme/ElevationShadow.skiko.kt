package com.linroid.ketch.app.theme

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.isSimple
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.PaintingStyle
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.graphics.skiaPaint
import org.jetbrains.skia.FilterBlurMode
import org.jetbrains.skia.MaskFilter

internal actual fun Modifier.elevationShadow(shape: Shape, shadow: Shadow): Modifier {
  // The elevations only use colors; a brush keeps Compose's own drawing.
  if (shadow.brush != null) return dropShadow(shape, shadow)
  return drawWithCache {
    val radius = shadow.radius.toPx()
    val spread = shadow.spread.toPx()
    val dx = shadow.offset.x.toPx()
    val dy = shadow.offset.y.toPx()
    val fill = shadowPaint(shadow, radius)
    // Like dropShadow, a rectangle or rounded rectangle grows or shrinks by the spread on each
    // side and keeps its corners, and any other path grows by a stroke along its edge.
    var box: RoundRect? = null
    var path: Path? = null
    var stroke: Paint? = null
    when (val outline = shape.createOutline(size, layoutDirection, this)) {
      is Outline.Rectangle -> box = RoundRect(outline.rect.inflate(spread))
      is Outline.Rounded -> {
        val r = outline.roundRect
        val grown = RoundRect(
          left = r.left - spread,
          top = r.top - spread,
          right = r.right + spread,
          bottom = r.bottom + spread,
          topLeftCornerRadius = r.topLeftCornerRadius,
          topRightCornerRadius = r.topRightCornerRadius,
          bottomRightCornerRadius = r.bottomRightCornerRadius,
          bottomLeftCornerRadius = r.bottomLeftCornerRadius,
        )
        if (grown.isSimple) box = grown else path = Path().apply { addRoundRect(grown) }
      }
      is Outline.Generic -> {
        path = outline.path
        if (spread > 0f) {
          stroke = shadowPaint(shadow, radius).apply {
            style = PaintingStyle.Stroke
            strokeWidth = spread * 2
          }
        }
      }
    }
    // A negative spread can leave nothing to draw.
    val shadowBox = box?.takeIf { it.width > 0f && it.height > 0f }
    val shadowPath = path
    val edge = stroke
    onDrawBehind {
      translate(dx, dy) {
        drawIntoCanvas { canvas ->
          if (shadowBox != null) {
            val corner = shadowBox.topLeftCornerRadius
            canvas.drawRoundRect(
              left = shadowBox.left,
              top = shadowBox.top,
              right = shadowBox.right,
              bottom = shadowBox.bottom,
              radiusX = corner.x,
              radiusY = corner.y,
              paint = fill,
            )
          }
          if (shadowPath != null) {
            canvas.drawPath(shadowPath, fill)
            if (edge != null) canvas.drawPath(shadowPath, edge)
          }
        }
      }
    }
  }
}

/** The paint of [shadow]: its color, blurred by [radius] pixels as Compose blurs shadows. */
private fun shadowPaint(shadow: Shadow, radius: Float): Paint = Paint().apply {
  color = shadow.color
  alpha = (shadow.color.alpha * shadow.alpha).coerceIn(0f, 1f)
  blendMode = shadow.blendMode
  if (radius > 0f) {
    skiaPaint.maskFilter =
      MaskFilter.makeBlur(FilterBlurMode.NORMAL, BLUR_SIGMA_SCALE * radius + 0.5f)
  }
}

/** Compose's and Android's conversion from a blur radius to a Gaussian sigma. */
private const val BLUR_SIGMA_SCALE = 0.57735f
