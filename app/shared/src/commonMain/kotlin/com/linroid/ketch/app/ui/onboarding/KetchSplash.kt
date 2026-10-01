package com.linroid.ketch.app.ui.onboarding

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.linroid.ketch.app.components.SailLanesIllustration
import com.linroid.ketch.app.components.SailLanesIllustrationDefaults
import com.linroid.ketch.app.state.toKetchAccent
import com.linroid.ketch.app.theme.KetchTheme
import com.linroid.ketch.app.ui.shell.canvasWash
import com.linroid.ketch.config.AppearanceConfig
import com.linroid.ketch.config.ThemeMode

/**
 * What a host shows while the app gets ready, such as while the Android app binds its download
 * service: the sail lanes, still, on the canvas wash, in the saved [appearance].
 */
@Composable
fun KetchSplash(
  appearance: AppearanceConfig = AppearanceConfig(),
  modifier: Modifier = Modifier,
) {
  val dark = when (appearance.theme) {
    ThemeMode.System -> isSystemInDarkTheme()
    ThemeMode.Light -> false
    ThemeMode.Dark -> true
  }
  KetchTheme(darkTheme = dark, accent = appearance.accent.toKetchAccent()) {
    Box(
      contentAlignment = Alignment.Center,
      modifier = modifier
        .fillMaxSize()
        .canvasWash(KetchTheme.colors, EmberRadius)
        .semantics { contentDescription = "Loading Ketch" },
    ) {
      // The host may hold the main thread while it starts, so the lanes do not animate.
      SailLanesIllustration(animate = false, width = SailLanesIllustrationDefaults.CompactWidth)
    }
  }
}

private val EmberRadius = 520.dp
