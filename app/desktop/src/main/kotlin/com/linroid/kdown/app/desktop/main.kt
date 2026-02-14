package com.linroid.kdown.app.desktop

import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import com.linroid.kdown.app.App
import com.linroid.kdown.app.instance.InstanceFactory
import com.linroid.kdown.app.instance.InstanceManager
import com.linroid.kdown.app.instance.LocalServerHandle
import com.linroid.kdown.server.KDownServer
import com.linroid.kdown.server.KDownServerConfig
import com.linroid.kdown.sqlite.DriverFactory
import com.linroid.kdown.sqlite.createSqliteTaskStore
import java.io.File
import java.net.InetAddress

fun main() = application {
  val instanceManager = remember {
    val dbPath = appConfigDir() + File.separator + "kdown.db"
    val taskStore = createSqliteTaskStore(DriverFactory(dbPath))
    val downloadsDir = System.getProperty("user.home") +
      File.separator + "Downloads"
    InstanceManager(
      InstanceFactory(
        taskStore = taskStore,
        defaultDirectory = downloadsDir,
        deviceName = InetAddress.getLocalHost().hostName,
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
    onDispose { instanceManager.close() }
  }
  Window(
    onCloseRequest = ::exitApplication,
    title = "KDown",
  ) {
    App(instanceManager)
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
