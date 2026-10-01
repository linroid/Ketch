package com.linroid.ketch.app.icons

import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.PathNode
import androidx.compose.ui.graphics.vector.VectorPath
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class KetchIconTest {
  @Test
  fun imageVector_everyIcon_keepsEverySegmentWritten() {
    for (icon in KetchIcon.entries) {
      val written = icon.data.strokes + icon.data.fills + icon.data.softFills
      val paths = icon.imageVector.root.map { it as VectorPath }
      assertEquals(written.size, paths.size, "${icon.name} path count")
      for ((d, path) in written.zip(paths)) {
        assertTrue(path.pathData.first() is PathNode.MoveTo, "${icon.name} must start with M: $d")
        assertEquals(writtenSegments(d), path.pathData.size, "${icon.name} lost segments of $d")
      }
    }
  }

  @Test
  fun imageVector_everyIcon_usesTheIconStroke() {
    for (icon in KetchIcon.entries) {
      val vector = icon.imageVector
      assertEquals(ICON_VIEWPORT, vector.viewportWidth)
      assertEquals(ICON_VIEWPORT, vector.viewportHeight)
      val paths = vector.root.map { it as VectorPath }
      val strokes = paths.take(icon.data.strokes.size)
      val fills = paths.drop(strokes.size).take(icon.data.fills.size)
      val softFills = paths.drop(strokes.size + fills.size)
      for (path in strokes) {
        assertNotNull(path.stroke, icon.name)
        assertNull(path.fill, icon.name)
        assertEquals(ICON_STROKE_WIDTH, path.strokeLineWidth, icon.name)
        assertEquals(StrokeCap.Round, path.strokeLineCap, icon.name)
        assertEquals(StrokeJoin.Round, path.strokeLineJoin, icon.name)
      }
      for (path in fills + softFills) {
        assertNotNull(path.fill, icon.name)
        assertNull(path.stroke, icon.name)
      }
      fills.forEach { assertEquals(1f, it.fillAlpha, icon.name) }
      softFills.forEach { assertEquals(ICON_SOFT_FILL_ALPHA, it.fillAlpha, icon.name) }
    }
  }

  @Test
  fun imageVector_repeatedAccess_returnsCachedVector() {
    for (icon in KetchIcon.entries) {
      assertSame(icon.imageVector, icon.imageVector, icon.name)
    }
  }

  /**
   * Counts the segments [d] spells out, failing when a command has a partial argument list,
   * such as an arc written with the compact flag syntax Compose cannot read.
   */
  private fun writtenSegments(d: String): Int {
    return commandPattern.findAll(d).sumOf { match ->
      val command = match.groupValues[1].uppercase().single()
      val arity = commandArity.getValue(command)
      val args = numberPattern.findAll(match.groupValues[2]).count()
      if (arity == 0) {
        assertEquals(0, args, "$command takes no arguments in $d")
        1
      } else {
        assertTrue(args > 0 && args % arity == 0, "$command has $args arguments in $d")
        args / arity
      }
    }
  }

  private companion object {
    val commandPattern = Regex("([A-Za-z])([^A-Za-z]*)")
    val numberPattern = Regex("""[-+]?(?:\d+\.?\d*|\.\d+)""")
    val commandArity = mapOf(
      'M' to 2, 'L' to 2, 'T' to 2, 'H' to 1, 'V' to 1,
      'C' to 6, 'S' to 4, 'Q' to 4, 'A' to 7, 'Z' to 0,
    )
  }
}
