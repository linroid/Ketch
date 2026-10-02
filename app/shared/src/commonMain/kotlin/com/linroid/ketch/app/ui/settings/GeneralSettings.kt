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
import kotlinx.coroutines.CancellationException

private val log = KetchLogger("GeneralSettings")

/**
 * The name of this device, how the app looks and moves, and on desktop what happens when its
 * window closes and whether it opens at login.
 *
 * @param systemDeviceName name the device goes by when none is set, or `null` when there is no
 *   device of this app's own to name (the web app).
 */
@Composable
fun GeneralSettings(state: AppState, systemDeviceName: String?) {
  val appSettings = state.appSettings
  val ui = appSettings.ui
  if (systemDeviceName != null) {
    SettingsGroup(title = "This device") {
      SettingsRow(
        title = "Device name",
        description = "Your other devices see this name after a restart.",
      ) {
        SettingsTextInput(
          value = appSettings.config.name.orEmpty(),
          onCommit = { appSettings.saveName(it) },
          placeholder = systemDeviceName,
        )
      }
    }
  }

  SettingsGroup(title = "Appearance") {
    SettingsRow(
      title = "Theme",
      trailing = {
        SettingsSegmented(
          value = appSettings.themeMode,
          options = ThemeMode.entries,
          label = { it.label },
          onSelect = { appSettings.saveThemeMode(it) },
        )
      },
    )
    SettingsRow(
      title = "Accent color",
      trailing = {
        AccentPicker(selected = appSettings.accent, onSelect = { appSettings.saveAccent(it) })
      },
    )
    SettingsRow(
      title = "Density",
      description = "Auto: compact with a mouse, roomier with touch.",
      trailing = {
        SettingsSegmented(
          value = ui.density,
          options = DensityMode.entries,
          label = { it.label },
          onSelect = { mode -> appSettings.saveUi { it.copy(density = mode) } },
        )
      },
    )
    val systemReduces = rememberReduceMotion()
    SettingsRow(
      title = "Reduce motion",
      description = when {
        ui.reduceMotion -> "Lanes and panels change without animating."
        systemReduces -> "Your system reduces motion, so Ketch does too."
        else -> "Auto follows your system's setting."
      },
      trailing = {
        SettingsSegmented(
          value = ui.reduceMotion,
          options = listOf(false, true),
          label = { if (it) "On" else "Auto" },
          onSelect = { on -> appSettings.saveUi { it.copy(reduceMotion = on) } },
        )
      },
    )
    SettingsRow(title = "Language", trailing = { SettingsValue("English") })
  }

  if (LocalDesktopHooks.current.isSupported) StartupGroup(state, appSettings)
  // Phones have no keyboard to speak of.
  if (!isMobilePlatform) {
    val chord = KetchCommands.Shortcuts.shortcutLabel(KeyboardPlatform.current)
    SettingsGroup {
      SettingsRow(
        title = "Keyboard shortcuts…",
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
  val apple = KeyboardPlatform.current.isApple
  val trayName = if (apple) "menu bar" else "notification area"
  var failure by remember { mutableStateOf<String?>(null) }
  // Saves a change, then asks the operating system to follow it; when it refuses, the saved
  // setting goes back, so the switch keeps saying what the system does.
  val apply = { what: String, change: (Boolean) -> Unit, on: Boolean, hook: suspend () -> Unit ->
    failure = null
    change(on)
    state.launchCommand {
      try {
        hook()
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        log.w { "Couldn't $what: ${e.describeCauses()}" }
        failure = "Couldn't $what: ${e.message ?: e::class.simpleName}"
        change(!on)
      }
    }
  }
  SettingsGroup(
    title = "Startup and window",
    footer = failure,
  ) {
    SettingsSelectRow(
      title = "When I close the window",
      description = when (desktop.closeAction) {
        CloseAction.Ask -> "Asks only while downloads are running."
        CloseAction.Background -> "Quit from the $trayName icon."
        CloseAction.Quit -> "Downloads pause until you open Ketch again."
      },
      value = desktop.closeAction,
      options = CloseAction.entries,
      label = { action ->
        when (action) {
          CloseAction.Ask -> "Ask each time"
          CloseAction.Background -> "Keep downloading in the $trayName"
          CloseAction.Quit -> "Quit Ketch"
        }
      },
      onSelect = { action ->
        appSettings.saveDesktop { it.copy(closeAction = action) }
        hooks.setCloseAction(action)
      },
    )
    SettingsSwitchRow(
      title = "Open Ketch at login",
      checked = desktop.openAtLogin,
      onCheckedChange = { on ->
        val save = { value: Boolean -> appSettings.saveDesktop { it.copy(openAtLogin = value) } }
        val what = if (on) "add the login item" else "remove the login item"
        apply(what, save, on) { hooks.setOpenAtLogin(on) }
      },
    )
    SettingsSwitchRow(
      title = "Start hidden at login",
      description = "Opens in the $trayName, without a window.",
      checked = desktop.startHidden,
      enabled = desktop.openAtLogin,
      onCheckedChange = { on ->
        val save = { value: Boolean -> appSettings.saveDesktop { it.copy(startHidden = value) } }
        apply("update the login item", save, on) { hooks.setStartHidden(on) }
      },
    )
    SettingsSelectRow(
      title = "App icon badge",
      value = desktop.dockBadge,
      options = DockBadgeMode.entries,
      label = { mode ->
        when (mode) {
          DockBadgeMode.ActiveCount -> "Active downloads"
          DockBadgeMode.FailuresOnly -> "Failures only"
          DockBadgeMode.Off -> "Nothing"
        }
      },
      onSelect = { mode ->
        appSettings.saveDesktop { it.copy(dockBadge = mode) }
        hooks.setDockBadgeMode(mode)
      },
    )
  }
}

private val DensityMode.label: String
  get() = when (this) {
    DensityMode.Auto -> "Auto"
    DensityMode.Compact -> "Compact"
    DensityMode.Comfortable -> "Comfortable"
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
          text = accent.displayName,
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
