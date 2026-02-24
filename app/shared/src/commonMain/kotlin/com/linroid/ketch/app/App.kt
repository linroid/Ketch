package com.linroid.ketch.app

import androidx.compose.runtime.Composable
import com.linroid.ketch.app.instance.InstanceManager
import com.linroid.ketch.app.state.AiDiscoveryProvider
import com.linroid.ketch.app.theme.KetchTheme
import com.linroid.ketch.app.ui.AppShell

@Composable
fun App(
  instanceManager: InstanceManager,
  embeddedAiProvider: AiDiscoveryProvider? = null,
) {
  KetchTheme {
    AppShell(instanceManager, embeddedAiProvider)
  }
}
