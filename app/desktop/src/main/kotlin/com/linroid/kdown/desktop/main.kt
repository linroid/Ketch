package com.linroid.kdown.desktop

import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import com.linroid.kdown.app.App
import com.linroid.kdown.app.backend.BackendFactory
import com.linroid.kdown.app.backend.BackendManager
import com.linroid.kdown.app.backend.LocalServerHandle
import com.linroid.kdown.server.KDownServer
import com.linroid.kdown.server.KDownServerConfig
import com.linroid.kdown.sqlite.DriverFactory
import com.linroid.kdown.sqlite.createSqliteTaskStore

fun main() = application {
  val backendManager = remember {
    val taskStore = createSqliteTaskStore(DriverFactory())
    BackendManager(
      BackendFactory(
        taskStore = taskStore,
        localServerFactory = { port, apiToken, kdownApi ->
          val server = KDownServer(
            kdownApi,
            KDownServerConfig(
              port = port,
              apiToken = apiToken,
              corsAllowedHosts = listOf("*"),
            )
          )
          server.start(wait = false)
          object : LocalServerHandle {
            override fun stop() {
              server.stop()
            }
          }
        }
      )
    )
  }
  DisposableEffect(Unit) {
    onDispose { backendManager.close() }
  }
  Window(
    onCloseRequest = ::exitApplication,
    title = "KDown",
  ) {
    App(backendManager)
  }
}
