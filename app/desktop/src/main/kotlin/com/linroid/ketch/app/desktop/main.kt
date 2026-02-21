package com.linroid.ketch.app.desktop

import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import com.linroid.ketch.app.App
import com.linroid.ketch.app.config.FileConfigStore
import com.linroid.ketch.app.instance.InstanceFactory
import com.linroid.ketch.app.instance.InstanceManager
import com.linroid.ketch.app.instance.LocalServerHandle
import com.linroid.ketch.api.config.ServerConfig
import com.linroid.ketch.server.KetchServer
import com.linroid.ketch.sqlite.DriverFactory
import com.linroid.ketch.sqlite.createSqliteTaskStore
import java.io.File
import java.net.InetAddress

fun main() = application {
  val instanceManager = remember {
    val configDir = appConfigDir()
    val configStore = FileConfigStore(
      configDir + File.separator + "config.toml",
    )
    val config = configStore.load()
    val dbPath = configDir + File.separator + "ketch.db"
    val taskStore = createSqliteTaskStore(DriverFactory(dbPath))
    val defaultDownloadsDir = System.getProperty("user.home") +
      File.separator + "Downloads"
    val downloadConfig = config.download.copy(
      defaultDirectory = config.download.defaultDirectory
        .takeIf { it != "downloads" }
        ?: defaultDownloadsDir,
    )
    val instanceName = config.name
      ?: InetAddress.getLocalHost().hostName
    InstanceManager(
      factory = InstanceFactory(
        taskStore = taskStore,
        downloadConfig = downloadConfig,
        deviceName = instanceName,
        localServerFactory = { port, apiToken, ketchApi ->
          val server = KetchServer(
            ketchApi,
            ServerConfig(
              port = port,
              apiToken = apiToken,
              corsAllowedHosts = listOf("*"),
            ),
            mdnsServiceName = instanceName,
          )
          server.start(wait = false)
          object : LocalServerHandle {
            override fun stop() {
              server.stop()
            }
          }
        }
      ),
      initialRemotes = config.remote,
      configStore = configStore,
    )
  }
  DisposableEffect(Unit) {
    onDispose { instanceManager.close() }
  }
  Window(
    onCloseRequest = ::exitApplication,
    title = "Ketch",
    icon = painterResource("icon.svg"),
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
        "Application Support${File.separator}ketch"
    os.contains("win") -> {
      val appData = System.getenv("APPDATA")
        ?: "$home${File.separator}AppData${File.separator}Roaming"
      "$appData${File.separator}ketch"
    }
    else -> {
      val xdg = System.getenv("XDG_CONFIG_HOME")
        ?: "$home${File.separator}.config"
      "$xdg${File.separator}ketch"
    }
  }
}
