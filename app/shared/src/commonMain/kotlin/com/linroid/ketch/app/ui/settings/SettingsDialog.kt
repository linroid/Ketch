package com.linroid.ketch.app.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.linroid.ketch.app.components.KetchIconButton
import com.linroid.ketch.app.icons.KetchIcon
import com.linroid.ketch.app.state.SettingsCategory
import com.linroid.ketch.app.theme.KetchTheme

/**
 * Settings as a panel over the app, for wide windows: the categories on
 * the left and the selected one on the right. Narrow windows use
 * [SettingsPage] instead.
 *
 * Changes apply as they are made, so closing — the close button, Esc or
 * a click outside — never loses anything; a field being edited saves as
 * the panel closes.
 *
 * @param categories categories to list, in order.
 * @param onDismiss close the settings.
 * @param content renders one category; see [SettingsCategoryContent].
 */
@Composable
fun SettingsDialog(
  categories: List<SettingsCategory>,
  onDismiss: () -> Unit,
  content: @Composable (SettingsCategory) -> Unit,
) {
  val colors = KetchTheme.colors
  var selectedName by rememberSaveable { mutableStateOf(categories.first().name) }
  val selected = categories.firstOrNull { it.name == selectedName } ?: categories.first()

  Dialog(
    onDismissRequest = onDismiss,
    properties = DialogProperties(usePlatformDefaultWidth = false),
  ) {
    val shape = RoundedCornerShape(16.dp)
    Row(
      modifier = Modifier
        .padding(32.dp)
        .widthIn(max = 960.dp)
        .heightIn(max = 760.dp)
        .fillMaxSize()
        .clip(shape)
        .background(colors.background)
        .border(1.dp, colors.outline, shape),
    ) {
      SettingsCategoryNav(
        categories = categories,
        selected = selected,
        onSelect = { selectedName = it.name },
        background = colors.surfaceVariant,
        footer = {
          Text(
            text = "Changes apply as you make them.",
            style = KetchTheme.typography.bodySmall,
            color = colors.onSurfaceDim,
            modifier = Modifier.padding(horizontal = 22.dp),
          )
        },
      )
      VerticalDivider(color = colors.outlineVariant)
      Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
        Column(Modifier.fillMaxSize()) {
          SettingsCategoryPage(
            category = selected,
            inset = 32.dp,
            onBack = null,
            content = content,
          )
        }
        // KetchIconButton wraps its modifier in a tooltip, so align a box.
        Box(Modifier.align(Alignment.TopEnd).padding(12.dp)) {
          KetchIconButton(
            icon = KetchIcon.Close,
            onClick = onDismiss,
            contentDescription = "Close settings",
          )
        }
      }
    }
  }
}
