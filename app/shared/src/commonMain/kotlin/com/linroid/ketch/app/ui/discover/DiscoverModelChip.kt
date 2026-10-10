package com.linroid.ketch.app.ui.discover

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.linroid.ketch.app.components.KetchChip
import com.linroid.ketch.app.components.KetchMenu
import com.linroid.ketch.app.i18n.resolve
import com.linroid.ketch.app.i18n.text
import com.linroid.ketch.app.i18n.verbatim
import com.linroid.ketch.app.icons.KetchIcon
import com.linroid.ketch.app.state.AppState
import com.linroid.ketch.app.state.SettingsTarget
import ketch.app.shared.generated.resources.Res
import ketch.app.shared.generated.resources.discover_model_manage
import ketch.app.shared.generated.resources.discover_model_menu_title
import org.jetbrains.compose.resources.stringResource

/**
 * The composer's chip with the model the next message searches with. It opens a menu of every
 * saved provider that can be called, with its models, to switch to another for the next
 * message, and Manage providers, which opens Discover's settings. Searches that run keep theirs.
 */
@Composable
internal fun DiscoverModelChip(state: AppState) {
  val ai = state.aiSettings
  val effective = ai.withPlatformCredentials(ai.settings)
  val active = effective.llm
  var open by remember { mutableStateOf(false) }
  Box {
    KetchChip(
      label = active.effectiveModel.ifBlank { active.displayName },
      selected = false,
      onClick = { open = true },
      leadingIcon = KetchIcon.Ai,
      trailingIcon = KetchIcon.ChevronDown,
    )
    KetchMenu(
      expanded = open,
      onDismissRequest = { open = false },
      title = stringResource(Res.string.discover_model_menu_title),
    ) {
      effective.entries.filter { it.isComplete }.forEach { entry ->
        header(verbatim(entry.displayName))
        entry.modelChoices.forEach { model ->
          item(
            label = verbatim(model),
            onClick = { ai.use(entry.id, model) },
            checked = entry.id == active.id && model == active.effectiveModel,
          )
        }
      }
      divider()
      item(
        label = Res.string.discover_model_manage.text(),
        onClick = { state.openSettings(SettingsTarget(SettingsTarget.Page.Discover)) },
        icon = KetchIcon.Settings,
      )
    }
  }
}
