package com.linroid.ketch.app.desktop

import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import com.linroid.ketch.app.App
import com.linroid.ketch.app.instance.InstanceFactory
import com.linroid.ketch.app.instance.InstanceManager
import com.linroid.ketch.app.instance.LocalServerHandle
import com.linroid.ketch.config.FileConfigStore
import com.linroid.ketch.config.defaultConfigDir
import com.linroid.ketch.server.KetchServer
import com.linroid.ketch.sqlite.DriverFactory
import com.linroid.ketch.sqlite.createSqliteTaskStore
import java.io.File
import java.net.InetAddress

fun main() = application {
  val instanceManager = remember {
    val configDir = defaultConfigDir()
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
        ?: defaultDownloadsDir,
    )
    val instanceName = config.name?.ifEmpty { null }
      ?: InetAddress.getLocalHost().hostName.removeSuffix(".local")
    InstanceManager(
      factory = InstanceFactory(
        taskStore = taskStore,
        downloadConfig = downloadConfig,
        deviceName = instanceName,
        localServerFactory = { ketchApi ->
          val serverConfig = config.server
          val server = KetchServer(
            ketch = ketchApi,
            host = serverConfig.host,
            port = serverConfig.port,
            apiToken = serverConfig.apiToken,
            name = instanceName,
            corsAllowedHosts = serverConfig.corsAllowedHosts
              .takeIf { it.isNotEmpty() } ?: listOf("*"),
            mdnsEnabled = serverConfig.mdnsEnabled,
          )
          server.start(wait = false)
          object : LocalServerHandle {
            override fun stop() {
              server.stop()
            }
          }
        },
      ),
      initialRemotes = config.remotes,
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
