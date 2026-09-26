package com.linroid.ketch.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.linroid.ketch.app.state.SettingsSection
import com.linroid.ketch.app.theme.KetchTheme

/**
 * Settings as a full page with every section stacked, for narrow windows.
 * Wide windows use [SettingsDialog] instead.
 *
 * @param sections sections to show, in order.
 * @param section renders one section; see [SettingsSectionCard].
 */
@Composable
fun SettingsPage(
  sections: List<SettingsSection>,
  section: @Composable (
    section: SettingsSection,
    compact: Boolean,
    onUnsavedChange: (Boolean) -> Unit,
  ) -> Unit,
) {
  BoxWithConstraints(Modifier.fillMaxSize()) {
    val compact = maxWidth < 600.dp
    val inset = if (compact) 16.dp else 32.dp
    Column(
      modifier = Modifier.fillMaxSize()
        .verticalScroll(rememberScrollState())
        .padding(inset),
      horizontalAlignment = Alignment.CenterHorizontally,
    ) {
      Column(
        modifier = Modifier.widthIn(max = 720.dp).fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(20.dp),
      ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
          Text(
            text = "Settings",
            style = KetchTheme.typography.displaySmall,
            color = KetchTheme.colors.onBackground,
          )
          Text(
            text = "Everything here is saved to this device's config file.",
            style = KetchTheme.typography.bodyMedium,
            color = KetchTheme.colors.onSurfaceVariant,
          )
        }
        sections.forEach { entry ->
          key(entry) { section(entry, compact) {} }
        }
      }
    }
  }
}
