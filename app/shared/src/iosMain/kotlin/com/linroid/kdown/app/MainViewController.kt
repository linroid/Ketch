package com.linroid.kdown.app

import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.window.ComposeUIViewController
import com.linroid.kdown.app.backend.BackendFactory
import com.linroid.kdown.app.backend.BackendManager
import com.linroid.kdown.sqlite.DriverFactory
import com.linroid.kdown.sqlite.createSqliteTaskStore

@Suppress("unused", "FunctionName")
fun MainViewController() = ComposeUIViewController {
  val backendManager = remember {
    val taskStore = createSqliteTaskStore(DriverFactory())
    BackendManager(BackendFactory(taskStore = taskStore))
  }
  DisposableEffect(Unit) {
    onDispose { backendManager.close() }
  }
  App(backendManager)
}
