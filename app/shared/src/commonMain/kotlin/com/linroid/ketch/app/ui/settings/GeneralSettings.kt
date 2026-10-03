package com.linroid.ketch.app.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.linroid.ketch.api.log.KetchLogger
import com.linroid.ketch.api.log.describeCauses
import com.linroid.ketch.app.components.focusRing
import com.linroid.ketch.app.components.rememberFocusVisibility
import com.linroid.ketch.app.components.trackFocusVisibility
import com.linroid.ketch.app.i18n.UiText
import com.linroid.ketch.app.i18n.resolve
import com.linroid.ketch.app.i18n.text
import com.linroid.ketch.app.icons.KetchIcon
import com.linroid.ketch.app.icons.KetchIconImage
import com.linroid.ketch.app.input.KetchCommands
import com.linroid.ketch.app.input.KeyboardPlatform
import com.linroid.ketch.app.platform.LocalDesktopHooks
import com.linroid.ketch.app.platform.isMobilePlatform
import com.linroid.ketch.app.platform.rememberReduceMotion
import com.linroid.ketch.app.state.AppSettingsController
import com.linroid.ketch.app.state.AppState
import com.linroid.ketch.app.theme.KetchAccent
import com.linroid.ketch.app.theme.KetchTheme
import com.linroid.ketch.app.theme.darkKetchColors
import com.linroid.ketch.app.theme.lightKetchColors
import com.linroid.ketch.config.CloseAction
import com.linroid.ketch.config.DensityMode
import com.linroid.ketch.config.DockBadgeMode
import com.linroid.ketch.config.ThemeMode
import ketch.app.shared.generated.resources.Res
import ketch.app.shared.generated.resources.settings_choice_auto
import ketch.app.shared.generated.resources.settings_choice_on
import ketch.app.shared.generated.resources.settings_density_auto
import ketch.app.shared.generated.resources.settings_density_comfortable
import ketch.app.shared.generated.resources.settings_density_compact
import ketch.app.shared.generated.resources.settings_general_accent
import ketch.app.shared.generated.resources.settings_general_appearance
import ketch.app.shared.generated.resources.settings_general_badge
import ketch.app.shared.generated.resources.settings_general_badge_active
import ketch.app.shared.generated.resources.settings_general_badge_failures
import ketch.app.shared.generated.resources.settings_general_badge_off
import ketch.app.shared.generated.resources.settings_general_close
import ketch.app.shared.generated.resources.settings_general_close_ask
import ketch.app.shared.generated.resources.settings_general_close_ask_hint
import ketch.app.shared.generated.resources.settings_general_close_menu_bar
import ketch.app.shared.generated.resources.settings_general_close_menu_bar_hint
import ketch.app.shared.generated.resources.settings_general_close_quit
import ketch.app.shared.generated.resources.settings_general_close_quit_hint
import ketch.app.shared.generated.resources.settings_general_close_tray
import ketch.app.shared.generated.resources.settings_general_close_tray_hint
import ketch.app.shared.generated.resources.settings_general_density
import ketch.app.shared.generated.resources.settings_general_density_hint
import ketch.app.shared.generated.resources.settings_general_device_name
import ketch.app.shared.generated.resources.settings_general_device_name_hint
import ketch.app.shared.generated.resources.settings_general_language
import ketch.app.shared.generated.resources.settings_general_language_current
import ketch.app.shared.generated.resources.settings_general_login_add_failed
import ketch.app.shared.generated.resources.settings_general_login_remove_failed
import ketch.app.shared.generated.resources.settings_general_login_update_failed
import ketch.app.shared.generated.resources.settings_general_open_at_login
import ketch.app.shared.generated.resources.settings_general_reduce_motion
import ketch.app.shared.generated.resources.settings_general_reduce_motion_auto
import ketch.app.shared.generated.resources.settings_general_reduce_motion_on
import ketch.app.shared.generated.resources.settings_general_reduce_motion_system
import ketch.app.shared.generated.resources.settings_general_shortcuts
import ketch.app.shared.generated.resources.settings_general_start_hidden
import ketch.app.shared.generated.resources.settings_general_start_hidden_menu_bar_hint
import ketch.app.shared.generated.resources.settings_general_start_hidden_tray_hint
import ketch.app.shared.generated.resources.settings_general_startup
import ketch.app.shared.generated.resources.settings_general_theme
import ketch.app.shared.generated.resources.settings_general_this_device
import kotlinx.coroutines.CancellationException
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

private val log = KetchLogger("GeneralSettings")

/**
 * The name of this device, how the app looks and moves, and on desktop what happens when its
 * window closes and whether it opens at login.
 *
 * @param systemDeviceName name the device goes by when none is set, or `null` when there is no
 *   device of this app's own to name (the web app).
 */
@Composable
fun GeneralSettings(
  state: AppState,
  appSettings: AppSettingsController,
  systemDeviceName: String?,
) {
  val ui = appSettings.ui
  if (systemDeviceName != null) {
    SettingsGroup(title = stringResource(Res.string.settings_general_this_device)) {
      SettingsRow(
        title = stringResource(Res.string.settings_general_device_name),
        description = stringResource(Res.string.settings_general_device_name_hint),
      ) {
        SettingsTextInput(
          value = appSettings.config.name.orEmpty(),
          onCommit = { appSettings.saveName(it) },
          placeholder = systemDeviceName,
        )
      }
    }
  }

  SettingsGroup(title = stringResource(Res.string.settings_general_appearance)) {
    SettingsRow(
      title = stringResource(Res.string.settings_general_theme),
      trailing = {
        SettingsSegmented(
          value = appSettings.themeMode,
          options = ThemeMode.entries,
          label = { it.label.resolve() },
          onSelect = { appSettings.saveThemeMode(it) },
        )
      },
    )
    SettingsRow(
      title = stringResource(Res.string.settings_general_accent),
      trailing = {
        AccentPicker(selected = appSettings.accent, onSelect = { appSettings.saveAccent(it) })
      },
    )
    SettingsRow(
      title = stringResource(Res.string.settings_general_density),
      description = stringResource(Res.string.settings_general_density_hint),
      trailing = {
        SettingsSegmented(
          value = ui.density,
          options = DensityMode.entries,
          label = { stringResource(it.label) },
          onSelect = { mode -> appSettings.saveUi { it.copy(density = mode) } },
        )
      },
    )
    val systemReduces = rememberReduceMotion()
    SettingsRow(
      title = stringResource(Res.string.settings_general_reduce_motion),
      description = stringResource(
        when {
          ui.reduceMotion -> Res.string.settings_general_reduce_motion_on
          systemReduces -> Res.string.settings_general_reduce_motion_system
          else -> Res.string.settings_general_reduce_motion_auto
        },
      ),
      trailing = {
        SettingsSegmented(
          value = ui.reduceMotion,
          options = listOf(false, true),
          label = { on ->
            stringResource(
              if (on) Res.string.settings_choice_on else Res.string.settings_choice_auto,
            )
          },
          onSelect = { on -> appSettings.saveUi { it.copy(reduceMotion = on) } },
        )
      },
    )
    SettingsRow(
      title = stringResource(Res.string.settings_general_language),
      trailing = { SettingsValue(stringResource(Res.string.settings_general_language_current)) },
    )
  }

  if (LocalDesktopHooks.current.isSupported) StartupGroup(state, appSettings)
  // Phones have no keyboard to speak of.
  if (!isMobilePlatform) {
    val chord = KetchCommands.Shortcuts.shortcutLabel(KeyboardPlatform.current)
    SettingsGroup {
      SettingsRow(
        title = stringResource(Res.string.settings_general_shortcuts),
        modifier = Modifier.clickable(role = Role.Button) { state.showShortcuts() },
        trailing = {
          Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(KetchTheme.spacing.s2),
          ) {
            if (chord != null) SettingsValue(chord)
            Chevron()
          }
        },
      )
    }
  }
}

/** Closing the window, opening at login and the app icon badge, on desktop. */
@Composable
private fun StartupGroup(state: AppState, appSettings: AppSettingsController) {
  val hooks = LocalDesktopHooks.current
  val desktop = appSettings.config.desktop
  // The menu bar on macOS, the notification area elsewhere.
  val menuBar = KeyboardPlatform.current.isApple
  var failure by remember { mutableStateOf<UiText?>(null) }
  // Saves a change, then asks the operating system to follow it; when it refuses, the saved
  // setting goes back, so the switch keeps saying what the system does; `failed` says so.
  fun apply(
    failed: StringResource,
    change: (Boolean) -> Unit,
    on: Boolean,
    hook: suspend () -> Unit,
  ) {
    failure = null
    change(on)
    state.launchCommand {
      try {
        hook()
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        log.w { "Couldn't change the login item to on=$on: ${e.describeCauses()}" }
        failure = failed.text(e.message ?: e::class.simpleName.orEmpty())
        change(!on)
      }
    }
  }
  SettingsGroup(
    title = stringResource(Res.string.settings_general_startup),
    footer = failure?.resolve(),
  ) {
    SettingsSelectRow(
      title = stringResource(Res.string.settings_general_close),
      description = stringResource(
        when (desktop.closeAction) {
          CloseAction.Ask -> Res.string.settings_general_close_ask_hint
          CloseAction.Background -> if (menuBar) {
            Res.string.settings_general_close_menu_bar_hint
          } else {
            Res.string.settings_general_close_tray_hint
          }
          CloseAction.Quit -> Res.string.settings_general_close_quit_hint
        },
      ),
      value = desktop.closeAction,
      options = CloseAction.entries,
      label = { action ->
        when (action) {
          CloseAction.Ask -> Res.string.settings_general_close_ask
          CloseAction.Background -> if (menuBar) {
            Res.string.settings_general_close_menu_bar
          } else {
            Res.string.settings_general_close_tray
          }
          CloseAction.Quit -> Res.string.settings_general_close_quit
        }.text()
      },
      onSelect = { action ->
        appSettings.saveDesktop { it.copy(closeAction = action) }
        hooks.setCloseAction(action)
      },
    )
    SettingsSwitchRow(
      title = stringResource(Res.string.settings_general_open_at_login),
      checked = desktop.openAtLogin,
      onCheckedChange = { on ->
        val save = { value: Boolean -> appSettings.saveDesktop { it.copy(openAtLogin = value) } }
        val failed = if (on) {
          Res.string.settings_general_login_add_failed
        } else {
          Res.string.settings_general_login_remove_failed
        }
        apply(failed, save, on) { hooks.setOpenAtLogin(on) }
      },
    )
    SettingsSwitchRow(
      title = stringResource(Res.string.settings_general_start_hidden),
      description = stringResource(
        if (menuBar) {
          Res.string.settings_general_start_hidden_menu_bar_hint
        } else {
          Res.string.settings_general_start_hidden_tray_hint
        },
      ),
      checked = desktop.startHidden,
      enabled = desktop.openAtLogin,
      onCheckedChange = { on ->
        val save = { value: Boolean -> appSettings.saveDesktop { it.copy(startHidden = value) } }
        apply(Res.string.settings_general_login_update_failed, save, on) {
          hooks.setStartHidden(on)
        }
      },
    )
    SettingsSelectRow(
      title = stringResource(Res.string.settings_general_badge),
      value = desktop.dockBadge,
      options = DockBadgeMode.entries,
      label = { mode ->
        when (mode) {
          DockBadgeMode.ActiveCount -> Res.string.settings_general_badge_active
          DockBadgeMode.FailuresOnly -> Res.string.settings_general_badge_failures
          DockBadgeMode.Off -> Res.string.settings_general_badge_off
        }.text()
      },
      onSelect = { mode ->
        appSettings.saveDesktop { it.copy(dockBadge = mode) }
        hooks.setDockBadgeMode(mode)
      },
    )
  }
}

private val DensityMode.label: StringResource
  get() = when (this) {
    DensityMode.Auto -> Res.string.settings_density_auto
    DensityMode.Compact -> Res.string.settings_density_compact
    DensityMode.Comfortable -> Res.string.settings_density_comfortable
  }

/** The four accents as swatches, each named under it, the selected one ringed and checked. */
@Composable
private fun AccentPicker(selected: KetchAccent, onSelect: (KetchAccent) -> Unit) {
  val colors = KetchTheme.colors
  val spacing = KetchTheme.spacing
  Row(
    horizontalArrangement = Arrangement.spacedBy(spacing.s1),
    modifier = Modifier.selectableGroup(),
  ) {
    KetchAccent.entries.forEach { accent ->
      val palette = if (colors.isDark) darkKetchColors(accent) else lightKetchColors(accent)
      val swatch = palette.accent
      val isSelected = accent == selected
      val focus = rememberFocusVisibility()
      val shape = KetchTheme.shapes.sm
      Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(spacing.s1),
        modifier = Modifier
          .focusRing(focus.visible, shape, colors.focusRing)
          .clip(shape)
          .trackFocusVisibility(focus)
          .selectable(selected = isSelected, role = Role.RadioButton) { onSelect(accent) }
          .widthIn(min = SwatchItemWidth)
          .padding(horizontal = spacing.s1, vertical = spacing.s1),
      ) {
        Box(
          contentAlignment = Alignment.Center,
          modifier = Modifier.size(SwatchRing)
            .border(spacing.s0_5, if (isSelected) swatch else Color.Transparent, CircleShape),
        ) {
          Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.size(SwatchSize).clip(CircleShape).background(swatch),
          ) {
            if (isSelected) {
              KetchIconImage(
                icon = KetchIcon.Check,
                size = KetchTheme.density.controlGlyph,
                tint = palette.onAccent,
              )
            }
          }
        }
        Text(
          text = accent.displayName.resolve(),
          style = KetchTheme.typography.labelS,
          fontWeight = if (isSelected) FontWeight.SemiBold else null,
          color = if (isSelected) colors.textPrimary else colors.textSecondary,
          textAlign = TextAlign.Center,
          maxLines = 1,
        )
      }
    }
  }
}

private val SwatchSize = 22.dp
private val SwatchRing = 30.dp
private val SwatchItemWidth = 52.dp
