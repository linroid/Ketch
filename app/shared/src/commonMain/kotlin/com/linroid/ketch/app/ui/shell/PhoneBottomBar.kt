package com.linroid.ketch.app.ui.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.linroid.ketch.app.components.KetchGlyphBadge
import com.linroid.ketch.app.i18n.resolve
import com.linroid.ketch.app.icons.KetchIconImage
import com.linroid.ketch.app.state.AppDestination
import com.linroid.ketch.app.theme.KetchTheme

/** Fewest destinations for which phones show a bottom bar; with fewer the top bar's menu does. */
internal const val BOTTOM_BAR_MIN_DESTINATIONS: Int = 3

/**
 * The phone's bottom bar of [destinations], shown only with [BOTTOM_BAR_MIN_DESTINATIONS] or
 * more. Settings is never a tab; it lives in the top bar's menu.
 *
 * @param selected the destination shown, or `null` while a page such as Settings covers them.
 * @param badges the counts destinations show over their glyphs, such as Discover's requests
 *   that wait for an OK.
 */
@Composable
internal fun PhoneBottomBar(
  destinations: List<AppDestination>,
  selected: AppDestination?,
  onSelect: (AppDestination) -> Unit,
  modifier: Modifier = Modifier,
  badges: Map<AppDestination, NavBadge> = emptyMap(),
) {
  val colors = KetchTheme.colors
  val itemColors = NavigationBarItemDefaults.colors(
    selectedIconColor = colors.accentText,
    selectedTextColor = colors.textPrimary,
    indicatorColor = colors.accentSoft,
    unselectedIconColor = colors.textSecondary,
    unselectedTextColor = colors.textSecondary,
  )
  Column(modifier.fillMaxWidth()) {
    Spacer(Modifier.fillMaxWidth().height(1.dp).background(colors.hairline))
    NavigationBar(
      containerColor = colors.surface,
      contentColor = colors.textPrimary,
      tonalElevation = 0.dp,
    ) {
      for (entry in destinations) {
        val isSelected = entry == selected
        NavigationBarItem(
          selected = isSelected,
          onClick = { onSelect(entry) },
          icon = {
            val count = badges[entry]
            Box {
              KetchIconImage(
                icon = entry.icon,
                size = KetchTheme.density.navGlyph,
                tint = if (isSelected) colors.accentText else colors.textSecondary,
              )
              if (count != null) {
                KetchGlyphBadge(
                  count = count.count,
                  modifier = Modifier
                    .align(Alignment.TopEnd)
                    .offset(x = BadgeShift, y = -BadgeRise)
                    .clearAndSetSemantics { contentDescription = count.countDescription },
                )
              }
            }
          },
          label = { Text(entry.label.resolve(), style = KetchTheme.typography.labelS) },
          colors = itemColors,
        )
      }
    }
  }
}

// Moves a count onto the corner of its destination's glyph.
private val BadgeShift: Dp = 8.dp
private val BadgeRise: Dp = 4.dp
