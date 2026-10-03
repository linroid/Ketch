package com.linroid.ketch.app.ui.shell

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.linroid.ketch.app.components.KetchButtonSize
import com.linroid.ketch.app.components.KetchIconButton
import com.linroid.ketch.app.icons.KetchIcon
import com.linroid.ketch.app.state.AppSettingsController
import com.linroid.ketch.app.theme.KetchTheme
import com.linroid.ketch.config.ThemeMode
import ketch.app.shared.generated.resources.Res
import ketch.app.shared.generated.resources.shell_appearance_dark
import ketch.app.shared.generated.resources.shell_appearance_light
import org.jetbrains.compose.resources.stringResource

/**
 * The moon or sun in the sidebar's title zone and at the top of the rail, which switches the app
 * to the dark or light appearance. It shows the appearance a click switches to.
 */
@Composable
internal fun AppearanceToggle(settings: AppSettingsController, modifier: Modifier = Modifier) {
  val dark = KetchTheme.colors.isDark
  val systemDark = isSystemInDarkTheme()
  KetchIconButton(
    icon = if (dark) KetchIcon.Sun else KetchIcon.Moon,
    onClick = { settings.saveThemeMode(themeModeFor(dark = !dark, systemDark = systemDark)) },
    size = KetchButtonSize.Small,
    contentDescription = stringResource(
      if (dark) Res.string.shell_appearance_light else Res.string.shell_appearance_dark
    ),
    modifier = modifier,
  )
}

/**
 * The mode that shows the dark appearance when [dark] is set, or the light one: [ThemeMode.System]
 * when the system shows it too, so the app keeps following the system's changes.
 */
internal fun themeModeFor(dark: Boolean, systemDark: Boolean): ThemeMode = when {
  dark == systemDark -> ThemeMode.System
  dark -> ThemeMode.Dark
  else -> ThemeMode.Light
}
