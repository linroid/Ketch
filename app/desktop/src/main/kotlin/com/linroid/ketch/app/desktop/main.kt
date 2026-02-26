package com.linroid.ketch.app.desktop

import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import com.linroid.ketch.ai.AiConfig
import com.linroid.ketch.ai.AiModule
import com.linroid.ketch.ai.LlmConfig
import com.linroid.ketch.api.log.Logger
import com.linroid.ketch.app.App
import com.linroid.ketch.app.instance.InstanceFactory
import com.linroid.ketch.app.instance.InstanceManager
import com.linroid.ketch.app.instance.LocalServerHandle
import com.linroid.ketch.app.state.EmbeddedAiDiscoveryProvider
import com.linroid.ketch.config.FileConfigStore
import com.linroid.ketch.config.defaultConfigDir
import com.linroid.ketch.core.Ketch
import com.linroid.ketch.engine.KtorHttpEngine
import com.linroid.ketch.ftp.FtpDownloadSource
import com.linroid.ketch.server.KetchServer
import com.linroid.ketch.sqlite.DriverFactory
import com.linroid.ketch.sqlite.createSqliteTaskStore
import com.linroid.ketch.torrent.TorrentDownloadSource
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
        deviceName = instanceName,
        embeddedFactory = {
          Ketch(
            httpEngine = KtorHttpEngine(),
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
  val embeddedAiProvider = remember {
    val apiKey = System.getenv("OPENAI_API_KEY") ?: ""
    if (apiKey.isNotBlank()) {
      val aiModule = AiModule.create(
        AiConfig(
          enabled = true,
          llm = LlmConfig(apiKey = apiKey),
        ),
      )
      EmbeddedAiDiscoveryProvider(aiModule.discoveryService)
    } else {
      null
    }
  }
  DisposableEffect(Unit) {
    onDispose { instanceManager.close() }
  }
  Window(
    onCloseRequest = ::exitApplication,
    title = "Ketch",
    icon = painterResource("icon.svg"),
  ) {
    App(instanceManager, embeddedAiProvider)
  }
}
