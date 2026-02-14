package com.linroid.kdown.app

import androidx.compose.runtime.Composable
import com.linroid.kdown.app.backend.BackendManager
import com.linroid.kdown.app.theme.KDownTheme
import com.linroid.kdown.app.ui.AppShell

@Composable
fun App(backendManager: BackendManager) {
  KDownTheme {
    AppShell(backendManager)
  }
}
