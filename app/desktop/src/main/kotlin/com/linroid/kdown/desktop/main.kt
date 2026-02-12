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
import java.io.File

fun main() = application {
  val backendManager = remember {
    val dbPath = appConfigDir() + File.separator + "kdown.db"
    val taskStore = createSqliteTaskStore(DriverFactory(dbPath))
    val downloadsDir = System.getProperty("user.home") +
      File.separator + "Downloads"
    BackendManager(
      BackendFactory(
        taskStore = taskStore,
        defaultDirectory = downloadsDir,
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

private fun appConfigDir(): String {
  val os = System.getProperty("os.name", "").lowercase()
  val home = System.getProperty("user.home")
  return when {
    os.contains("mac") ->
      "$home${File.separator}Library${File.separator}" +
        "Application Support${File.separator}kdown"
    os.contains("win") -> {
      val appData = System.getenv("APPDATA")
        ?: "$home${File.separator}AppData${File.separator}Roaming"
      "$appData${File.separator}kdown"
    }
    else -> {
      val xdg = System.getenv("XDG_CONFIG_HOME")
        ?: "$home${File.separator}.config"
      "$xdg${File.separator}kdown"
    }
  }
}
