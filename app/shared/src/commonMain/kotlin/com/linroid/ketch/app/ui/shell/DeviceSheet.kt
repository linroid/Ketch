package com.linroid.ketch.app.ui.shell

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.linroid.ketch.app.components.KetchBottomSheet
import com.linroid.ketch.app.i18n.text
import com.linroid.ketch.app.icons.KetchIcon
import com.linroid.ketch.app.state.AppState
import com.linroid.ketch.app.theme.KetchTheme
import ketch.app.shared.generated.resources.Res
import ketch.app.shared.generated.resources.shell_devices
import ketch.app.shared.generated.resources.shell_settings
import ketch.app.shared.generated.resources.switcher_overview
import org.jetbrains.compose.resources.stringResource

/**
 * The phone's device sheet, from the top bar's pennant: All devices, every device with what it
 * is doing, then Overview (the Devices page), the ways to add a device, and Settings. Picking
 * a device switches to it and closes the sheet.
 *
 * @param onShowOverview shows the Devices page.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DeviceSheet(
  state: AppState,
  onDismissRequest: () -> Unit,
  onShowOverview: () -> Unit,
) {
  val colors = KetchTheme.colors
  val spacing = KetchTheme.spacing
  val added = rememberDeviceActions(state, share = false)
  val actions = remember(added, onShowOverview) {
    val overview =
      SwitcherEntry.Action(Res.string.switcher_overview.text(), KetchIcon.Devices, onShowOverview)
    listOf(overview) + added +
      SwitcherEntry.Action(Res.string.shell_settings.text(), KetchIcon.Settings) {
        state.openSettings()
      }
  }
  val entries = rememberSwitcherEntries(state, actions)
  var highlighted by remember { mutableIntStateOf(-1) }
  KetchBottomSheet(onDismissRequest = onDismissRequest) {
    Column(
      Modifier
        .fillMaxWidth()
        .verticalScroll(rememberScrollState())
        .windowInsetsPadding(WindowInsets.navigationBars)
        .padding(bottom = spacing.s2),
    ) {
      Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
          .heightIn(min = spacing.s12)
          .padding(horizontal = KetchTheme.density.pagePadding),
      ) {
        Text(
          text = stringResource(Res.string.shell_devices),
          style = KetchTheme.typography.titleM,
          color = colors.textPrimary,
        )
      }
      SwitcherRows(
        state = state,
        entries = entries,
        highlighted = highlighted,
        onHighlight = { highlighted = it },
        onPick = { entry ->
          onDismissRequest()
          entry.run()
        },
      )
    }
  }
}
