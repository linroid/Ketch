package com.linroid.ketch.app.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.constrainWidth
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.linroid.ketch.app.theme.KetchTheme
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max

/**
 * How one cell of a [KetchCellGrid] is drawn, worked out once per change rather than per frame.
 *
 * @property fill the cell's color.
 * @property outline a ring drawn inside the cell's edge; `null` for none.
 * @property outlineWidth width of [outline].
 * @property split the color of the cell's upper-right half, over [fill] in the lower-left one;
 *   `null` for a cell of one color.
 */
@Immutable
data class CellPaint(
  val fill: Color,
  val outline: Color? = null,
  val outlineWidth: Dp = 1.dp,
  val split: Color? = null,
)

/**
 * A grid of small square cells drawn on one canvas, like a contributions graph, so hundreds of
 * them cost one draw and no composable each.
 *
 * With [rows] it is that many rows tall and as wide as its cells need, filled column by column;
 * without, it fills the width it is given and wraps row by row. [onHover] reports the cell under
 * a mouse pointer, or the one the arrow keys reach while it has keyboard focus, and `null` when
 * there is none; [onSelect], when set, reports a tapped or clicked cell and Enter on the
 * keyboard's cell, and makes the grid focusable. [selected] is outlined.
 *
 * @param description what a screen reader says for the whole grid; `null` leaves the grid out,
 *   as when its parent describes it.
 * @param selectedDescription what a screen reader announces for [selected] as it changes.
 */
@Composable
fun KetchCellGrid(
  cells: List<CellPaint>,
  cellSize: Dp,
  gap: Dp,
  modifier: Modifier = Modifier,
  rows: Int? = null,
  selected: Int? = null,
  onHover: (Int?) -> Unit = {},
  onSelect: ((Int) -> Unit)? = null,
  description: String? = null,
  selectedDescription: String? = null,
) {
  val hover by rememberUpdatedState(onHover)
  val select by rememberUpdatedState(onSelect)
  val count by rememberUpdatedState(cells.size)
  val colors = KetchTheme.colors
  val interactions = remember { MutableInteractionSource() }
  val focused by interactions.collectIsFocusedAsState()
  var cursor by remember { mutableStateOf<Int?>(null) }
  LaunchedEffect(focused) {
    if (focused) {
      val start = (selected ?: 0).takeIf { it < count }
      cursor = start
      hover(start)
    } else if (cursor != null) {
      cursor = null
      hover(null)
    }
  }
  val focusable = onSelect != null
  val density = LocalDensity.current
  var width by remember { mutableIntStateOf(0) }
  Box(modifier) {
    Canvas(
      modifier = Modifier
        .layout { measurable, constraints ->
          val size = cellGridSize(
            count = cells.size,
            rows = rows,
            cell = cellSize.toPx(),
            gap = gap.toPx(),
            maxWidth = if (constraints.hasBoundedWidth) constraints.maxWidth.toFloat() else null,
          )
          val w = constraints.constrainWidth(ceil(size.width).toInt())
          val h = constraints.constrainHeight(ceil(size.height).toInt())
          val placeable = measurable.measure(Constraints.fixed(w, h))
          layout(w, h) { placeable.place(0, 0) }
        }
        .onSizeChanged { width = it.width }
        .pointerInput(rows, cellSize, gap) {
          awaitPointerEventScope {
            while (true) {
              val event = awaitPointerEvent()
              val change = event.changes.firstOrNull() ?: continue
              if (change.type != PointerType.Mouse) continue
              when (event.type) {
                PointerEventType.Enter, PointerEventType.Move -> {
                  val layout = cellGridLayout(
                    count,
                    rows,
                    cellSize.toPx(),
                    gap.toPx(),
                    size.width.toFloat(),
                  )
                  hover(
                    cellIndexAt(change.position, layout, cellSize.toPx(), gap.toPx(), count),
                  )
                }
                PointerEventType.Exit -> hover(null)
              }
            }
          }
        }
        .then(
          if (focusable) {
            Modifier
              .pointerInput(rows, cellSize, gap) {
                detectTapGestures { position ->
                  val layout = cellGridLayout(
                    count,
                    rows,
                    cellSize.toPx(),
                    gap.toPx(),
                    size.width.toFloat(),
                  )
                  val index = cellIndexAt(position, layout, cellSize.toPx(), gap.toPx(), count)
                  if (index != null) select?.invoke(index)
                }
              }
              .onKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                val at = cursor ?: return@onKeyEvent false
                val layout = with(density) {
                  cellGridLayout(count, rows, cellSize.toPx(), gap.toPx(), width.toFloat())
                }
                val move = when (event.key) {
                  Key.DirectionLeft -> CellMove.Left
                  Key.DirectionRight -> CellMove.Right
                  Key.DirectionUp -> CellMove.Up
                  Key.DirectionDown -> CellMove.Down
                  Key.Enter, Key.NumPadEnter, Key.Spacebar -> {
                    select?.invoke(at)
                    return@onKeyEvent true
                  }
                  else -> return@onKeyEvent false
                }
                val next = moveCell(at, move, layout, count)
                if (next != at) {
                  cursor = next
                  hover(next)
                }
                true
              }
              .focusable(interactionSource = interactions)
          } else {
            Modifier
          },
        )
        .then(
          if (description != null) {
            Modifier.semantics { contentDescription = description }
          } else {
            Modifier.clearAndSetSemantics {}
          },
        ),
    ) {
      val cell = cellSize.toPx()
      val space = gap.toPx()
      val layout = cellGridLayout(cells.size, rows, cell, space, size.width)
      val radius = CornerRadius(cell * CORNER_FRACTION)
      cells.forEachIndexed { index, paint ->
        val origin = cellOrigin(index, layout, cell, space)
        drawCell(paint, origin, cell, radius)
      }
      val ring = SELECTION_RING.toPx()
      selected?.takeIf { it < cells.size }?.let { index ->
        drawRing(cellOrigin(index, layout, cell, space), cell, ring, colors.textPrimary)
      }
      val keyboard = cursor
      if (focused && keyboard != null && keyboard < cells.size) {
        drawRing(cellOrigin(keyboard, layout, cell, space), cell, ring, colors.focusRing)
      }
    }
    if (selectedDescription != null) {
      Box(
        Modifier.matchParentSize().clearAndSetSemantics {
          contentDescription = selectedDescription
          liveRegion = LiveRegionMode.Polite
        },
      )
    }
  }
}

/** One cell drawn alone, [size] square, such as a legend's swatch. */
@Composable
fun KetchCell(paint: CellPaint, size: Dp, modifier: Modifier = Modifier) {
  KetchCellGrid(cells = listOf(paint), cellSize = size, gap = 0.dp, rows = 1, modifier = modifier)
}

private fun DrawScope.drawCell(
  paint: CellPaint,
  origin: Offset,
  cell: Float,
  radius: CornerRadius,
) {
  val box = Size(cell, cell)
  drawRoundRect(paint.fill, origin, box, radius)
  val split = paint.split
  if (split != null) {
    val half = Path().apply {
      moveTo(origin.x, origin.y)
      lineTo(origin.x + cell, origin.y)
      lineTo(origin.x + cell, origin.y + cell)
      close()
    }
    clipPath(half) { drawRoundRect(split, origin, box, radius) }
  }
  val outline = paint.outline
  if (outline != null) {
    val stroke = paint.outlineWidth.toPx().coerceAtMost(cell / 2)
    val inset = stroke / 2
    drawRoundRect(
      color = outline,
      topLeft = Offset(origin.x + inset, origin.y + inset),
      size = Size(cell - stroke, cell - stroke),
      cornerRadius = CornerRadius(max(radius.x - inset, 0f)),
      style = Stroke(stroke),
    )
  }
}

private fun DrawScope.drawRing(origin: Offset, cell: Float, stroke: Float, color: Color) {
  // The ring's inner edge sits half a stroke outside the cell.
  val outset = stroke
  drawRoundRect(
    color = color,
    topLeft = Offset(origin.x - outset, origin.y - outset),
    size = Size(cell + outset * 2, cell + outset * 2),
    cornerRadius = CornerRadius(cell * CORNER_FRACTION + outset),
    style = Stroke(stroke),
  )
}

/**
 * How a [KetchCellGrid] places its cells.
 *
 * @property columns how many columns it has.
 * @property rows how many rows it has.
 * @property columnMajor whether cells fill each column before the next, rather than each row.
 */
internal data class CellGridLayout(val columns: Int, val rows: Int, val columnMajor: Boolean)

/** Directions the arrow keys move a [KetchCellGrid]'s keyboard cell in. */
internal enum class CellMove { Left, Right, Up, Down }

/**
 * The layout of [count] cells of [cell] px with [gap] px between them: [rows] rows filled column
 * by column, or, without [rows], as many columns as fit in [maxWidth] filled row by row.
 */
internal fun cellGridLayout(
  count: Int,
  rows: Int?,
  cell: Float,
  gap: Float,
  maxWidth: Float?,
): CellGridLayout {
  if (rows != null) {
    val height = rows.coerceAtLeast(1)
    val columns = ((count + height - 1) / height).coerceAtLeast(1)
    return CellGridLayout(columns = columns, rows = height, columnMajor = true)
  }
  val columns = if (maxWidth == null) {
    count.coerceAtLeast(1)
  } else {
    floor((maxWidth + gap) / (cell + gap)).toInt().coerceAtLeast(1)
  }
  val lines = ((count + columns - 1) / columns).coerceAtLeast(1)
  return CellGridLayout(columns = columns, rows = lines, columnMajor = false)
}

/** The size in px a [KetchCellGrid] of these cells takes; see [cellGridLayout]. */
internal fun cellGridSize(count: Int, rows: Int?, cell: Float, gap: Float, maxWidth: Float?): Size {
  val layout = cellGridLayout(count, rows, cell, gap, maxWidth)
  val height = layout.rows * (cell + gap) - gap
  val width = if (rows == null && maxWidth != null) {
    maxWidth
  } else {
    layout.columns * (cell + gap) - gap
  }
  return Size(width.coerceAtLeast(0f), height.coerceAtLeast(0f))
}

/** Top left corner of the cell at [index], in px. */
internal fun cellOrigin(index: Int, layout: CellGridLayout, cell: Float, gap: Float): Offset {
  val column = if (layout.columnMajor) index / layout.rows else index % layout.columns
  val row = if (layout.columnMajor) index % layout.rows else index / layout.columns
  return Offset(column * (cell + gap), row * (cell + gap))
}

/**
 * The index of the cell at [position], or `null` outside the cells. A gap belongs to the cell
 * before it, so a pointer between two small cells still points at one.
 */
internal fun cellIndexAt(
  position: Offset,
  layout: CellGridLayout,
  cell: Float,
  gap: Float,
  count: Int,
): Int? {
  if (position.x < 0 || position.y < 0) return null
  val column = floor(position.x / (cell + gap)).toInt()
  val row = floor(position.y / (cell + gap)).toInt()
  if (column >= layout.columns || row >= layout.rows) return null
  val index = if (layout.columnMajor) column * layout.rows + row else row * layout.columns + column
  return index.takeIf { it < count }
}

/** The cell [move] reaches from [index]; [index] itself at an edge. */
internal fun moveCell(index: Int, move: CellMove, layout: CellGridLayout, count: Int): Int {
  val along = if (layout.columnMajor) layout.rows else layout.columns
  val (step, sameLine) = when (move) {
    CellMove.Left -> if (layout.columnMajor) -along to false else -1 to true
    CellMove.Right -> if (layout.columnMajor) along to false else 1 to true
    CellMove.Up -> if (layout.columnMajor) -1 to true else -along to false
    CellMove.Down -> if (layout.columnMajor) 1 to true else along to false
  }
  val next = index + step
  if (next !in 0 until count) return index
  // Within a column or a row, a step past its end would wrap into the next one.
  if (sameLine && next / along != index / along) return index
  return next
}

private const val CORNER_FRACTION = 0.25f
private val SELECTION_RING = 1.5.dp
