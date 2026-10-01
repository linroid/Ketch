package com.linroid.ketch.app.ui.list

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints

/**
 * A file name on one line that, when it does not fit, gives up characters from its middle
 * rather than its end, so the extension and the name's start stay readable:
 * "android-studio-2024…mac_arm.dmg".
 */
@Composable
internal fun FileNameText(
  text: String,
  style: TextStyle,
  color: Color,
  modifier: Modifier = Modifier,
) {
  val measurer = rememberTextMeasurer(cacheSize = MEASURE_CACHE)
  val resolved = style.merge(TextStyle(color = color))
  var laidOut by remember { mutableStateOf<TextLayoutResult?>(null) }
  Layout(
    modifier = modifier
      .semantics { this.text = AnnotatedString(text) }
      .drawBehind { laidOut?.let { drawText(it) } },
  ) { _, constraints ->
    val result = fitMiddle(measurer, text, resolved, constraints)
    laidOut = result
    layout(
      result.size.width.coerceIn(constraints.minWidth, constraints.maxWidth),
      result.size.height.coerceIn(constraints.minHeight, constraints.maxHeight)
    ) {}
  }
}

/**
 * [text] laid out on one line within [constraints]: whole when it fits, otherwise with the most
 * characters that fit kept from both ends around an ellipsis, a third of them from the end at
 * least as many as the extension needs.
 */
internal fun fitMiddle(
  measurer: TextMeasurer,
  text: String,
  style: TextStyle,
  constraints: Constraints,
): TextLayoutResult {
  fun measure(value: String) = measurer.measure(
    text = value,
    style = style,
    maxLines = 1,
    softWrap = false,
    constraints = Constraints(maxHeight = constraints.maxHeight),
  )
  val full = measure(text)
  val max = constraints.maxWidth
  if (!constraints.hasBoundedWidth || full.size.width <= max || text.length < 2) return full
  var best = measure(ELLIPSIS)
  var low = 1
  var high = text.length - 1
  while (low <= high) {
    val kept = (low + high) / 2
    val candidate = measure(middleEllipsis(text, kept))
    if (candidate.size.width <= max) {
      best = candidate
      low = kept + 1
    } else {
      high = kept - 1
    }
  }
  return best
}

/**
 * [text] cut to [kept] characters around an ellipsis, keeping its extension when it can.
 * Separators next to the ellipsis are dropped, so "2024.2.1.…" reads "2024.2.1…".
 */
internal fun middleEllipsis(text: String, kept: Int): String {
  if (kept >= text.length) return text
  val dot = text.lastIndexOf('.')
  val extension = if (dot > 0 && text.length - dot <= MAX_EXTENSION) text.length - dot else 0
  val tail = minOf(kept, maxOf(kept / 3, extension + TAIL_CONTEXT))
  val head = text.take(kept - tail).trimEnd { it in SEPARATORS }
  // A leading dot may be the extension's own, so the tail keeps it.
  val end = text.takeLast(tail).trimStart { it != '.' && it in SEPARATORS }
  return head + ELLIPSIS + end
}

private const val ELLIPSIS = "…"
private const val SEPARATORS = ".-_ "
private const val MAX_EXTENSION = 8
private const val TAIL_CONTEXT = 4
private const val MEASURE_CACHE = 16
