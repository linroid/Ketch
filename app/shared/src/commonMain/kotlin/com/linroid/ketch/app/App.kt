package com.linroid.ketch.app

import androidx.compose.runtime.Composable
import com.linroid.ketch.app.instance.InstanceManager
import com.linroid.ketch.app.theme.KetchTheme
import com.linroid.ketch.app.ui.AppShell

@Composable
fun App(instanceManager: InstanceManager) {
  KetchTheme {
    AppShell(instanceManager)
  }
}
