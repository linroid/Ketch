package com.linroid.kdown.app

import androidx.compose.runtime.Composable
import com.linroid.kdown.app.instance.InstanceManager
import com.linroid.kdown.app.theme.KDownTheme
import com.linroid.kdown.app.ui.AppShell

@Composable
fun App(instanceManager: InstanceManager) {
  KDownTheme {
    AppShell(instanceManager)
  }
}
