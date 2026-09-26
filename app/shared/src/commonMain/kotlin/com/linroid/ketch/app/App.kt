package com.linroid.ketch.app

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import com.linroid.ketch.app.instance.InstanceManager
import com.linroid.ketch.app.state.AiDiscoveryProviderFactory
import com.linroid.ketch.app.state.AiSettingsController
import com.linroid.ketch.app.state.AppSettingsController
import com.linroid.ketch.app.theme.KetchTheme
import com.linroid.ketch.app.ui.AppShell
import com.linroid.ketch.config.ThemeMode

@Composable
fun App(
  instanceManager: InstanceManager,
  aiProviderFactory: AiDiscoveryProviderFactory? = null,
) {
  // The controllers are created here because the theme needs the saved
  // accent and theme mode before the shell composes.
  val appSettings = remember(instanceManager) {
    AppSettingsController(instanceManager.configStore)
  }
  val aiSettings = remember(instanceManager) {
    AiSettingsController(instanceManager.configStore, aiProviderFactory)
  }
  // The instance manager can outlive this composition (Android keeps it
  // in the service across activity recreation), so the discovery engine
  // is released here rather than with the manager.
  DisposableEffect(aiSettings) {
    onDispose { aiSettings.close() }
  }
  val darkTheme = when (appSettings.themeMode) {
    ThemeMode.System -> isSystemInDarkTheme()
    ThemeMode.Light -> false
    ThemeMode.Dark -> true
  }
  KetchTheme(darkTheme = darkTheme, accent = appSettings.accent) {
    AppShell(instanceManager, appSettings, aiSettings)
  }
}
