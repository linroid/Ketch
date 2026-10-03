package com.linroid.ketch.app.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.linroid.ketch.app.i18n.UiText
import com.linroid.ketch.app.i18n.resolve
import com.linroid.ketch.app.icons.KetchIcon
import com.linroid.ketch.app.icons.KetchIconImage
import com.linroid.ketch.app.theme.KetchTheme

/**
 * One button of a [KetchPillGroup].
 *
 * @property label what the button does; its tooltip and accessibility label.
 * @property selected whether the button is toggled on, such as the current view.
 */
@Immutable
class KetchPillItem(
  val icon: KetchIcon,
  val label: UiText,
  val onClick: () -> Unit,
  val selected: Boolean = false,
)

/**
 * Up to four related icon buttons joined in one bordered pill with hairline dividers, such as
 * List | Table.
 */
@Composable
fun KetchPillGroup(
  items: List<KetchPillItem>,
  modifier: Modifier = Modifier,
) {
  require(items.size <= MAX_ITEMS) { "A pill group holds at most $MAX_ITEMS buttons" }
  val colors = KetchTheme.colors
  val shape = KetchTheme.shapes.full
  Row(
    verticalAlignment = Alignment.CenterVertically,
    modifier = modifier
      .height(KetchTheme.density.chip)
      .clip(shape)
      .background(colors.surface)
      .border(1.dp, colors.borderStrong, shape),
  ) {
    items.forEachIndexed { index, item ->
      if (index > 0) {
        Box(Modifier.width(1.dp).fillMaxHeight().background(colors.hairline))
      }
      PillButton(item)
    }
  }
}

@Composable
private fun PillButton(item: KetchPillItem) {
  val colors = KetchTheme.colors
  val density = KetchTheme.density
  val interactions = remember { MutableInteractionSource() }
  val overlay = rememberInteractionOverlay(interactions)
  val focus = rememberFocusVisibility()
  val label = item.label.resolve()
  KetchTooltip(text = label) {
    Box(
      contentAlignment = Alignment.Center,
      modifier = Modifier
        .focusRing(focus.visible, KetchTheme.shapes.full, colors.focusRing, gap = InsetRing)
        .width(density.chip + ButtonGrowth)
        .fillMaxHeight()
        .background(if (item.selected) colors.accentSoft else Color.Transparent)
        .background(overlay)
        .semantics {
          contentDescription = label
          selected = item.selected
        }
        .ketchClickable(interactions, focus, onClick = item.onClick),
    ) {
      KetchIconImage(
        icon = item.icon,
        size = density.controlGlyph,
        tint = if (item.selected) colors.accentText else colors.textSecondary,
      )
    }
  }
}

private const val MAX_ITEMS = 4
private val ButtonGrowth = 4.dp

// The pill clips its buttons, so their focus rings are drawn inside.
private val InsetRing = (-2).dp
