package com.linroid.ketch.app.desktop

import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.linroid.ketch.api.log.Logger
import com.linroid.ketch.app.App
import com.linroid.ketch.app.instance.InstanceFactory
import com.linroid.ketch.app.instance.InstanceManager
import com.linroid.ketch.app.instance.LocalServerHandle
import com.linroid.ketch.app.state.EmbeddedAiDiscoveryProviderFactory
import com.linroid.ketch.config.FileConfigStore
import com.linroid.ketch.config.defaultConfigDir
import com.linroid.ketch.core.Ketch
import com.linroid.ketch.engine.KtorHttpEngine
import com.linroid.ketch.engine.withNetworkInterfaces
import com.linroid.ketch.ftp.FtpDownloadSource
import com.linroid.ketch.server.KetchServer
import com.linroid.ketch.sqlite.DriverFactory
import com.linroid.ketch.sqlite.createSqliteTaskStore
import com.linroid.ketch.torrent.TorrentDownloadSource
import java.awt.GraphicsEnvironment
import java.io.File
import java.net.InetAddress
import kotlin.math.min

// Wide enough for the sidebar layout, which the shared UI switches to at the
// 840dp expanded breakpoint, and tall enough to show the whole sidebar.
private const val DEFAULT_WINDOW_WIDTH = 1024
private const val DEFAULT_WINDOW_HEIGHT = 720

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
        deviceName = instanceName,
        embeddedFactory = {
          Ketch(
            httpEngine = KtorHttpEngine.withNetworkInterfaces(),
            taskStore = taskStore,
            config = downloadConfig,
            name = instanceName,
            logger = Logger.console(),
            additionalSources = listOf(
              FtpDownloadSource(),
              TorrentDownloadSource(),
            ),
          )
        },
        localServerFactory = { ketchApi ->
          // Reloaded here so a restart from Settings picks up the
          // saved port, token and mDNS choice.
          val saved = configStore.load()
          val serverConfig = saved.server
          val server = KetchServer(
            ketch = ketchApi,
            host = serverConfig.host,
            port = serverConfig.port,
            apiToken = serverConfig.apiToken,
            name = saved.name?.ifEmpty { null } ?: instanceName,
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
  val aiProviderFactory = remember { EmbeddedAiDiscoveryProviderFactory() }
  DisposableEffect(Unit) {
    onDispose { instanceManager.close() }
  }
  val windowState = rememberWindowState(
    size = defaultWindowSize(),
    position = WindowPosition(Alignment.Center),
  )
  Window(
    onCloseRequest = ::exitApplication,
    state = windowState,
    title = "Ketch",
    icon = painterResource("icon.svg"),
  ) {
    App(instanceManager, aiProviderFactory)
  }
}

// Shrinks to fit screens smaller than the preferred size.
private fun defaultWindowSize(): DpSize {
  val bounds = GraphicsEnvironment.getLocalGraphicsEnvironment()
    .maximumWindowBounds
  return DpSize(
    width = min(DEFAULT_WINDOW_WIDTH, bounds.width).dp,
    height = min(DEFAULT_WINDOW_HEIGHT, bounds.height).dp,
  )
}
