package com.linroid.ketch.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.linroid.ketch.app.instance.InstanceManager
import com.linroid.ketch.app.state.AiDiscoveryProviderFactory
import com.linroid.ketch.app.state.AiSettingsController
import com.linroid.ketch.app.state.AppSettingsController
import com.linroid.ketch.app.theme.KetchTheme
import com.linroid.ketch.app.ui.AppShell

@Composable
fun App(
  instanceManager: InstanceManager,
  aiProviderFactory: AiDiscoveryProviderFactory? = null,
) {
  // The controllers are created here because the theme needs the saved
  // accent before the shell composes.
  val appSettings = remember(instanceManager) {
    AppSettingsController(instanceManager.configStore)
  }
  val aiSettings = remember(instanceManager) {
    AiSettingsController(instanceManager.configStore, aiProviderFactory)
  }
  KetchTheme(accent = appSettings.accent) {
    AppShell(instanceManager, appSettings, aiSettings)
  }
}
