package com.linroid.ketch.app

import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.window.ComposeUIViewController
import com.linroid.ketch.api.log.Logger
import com.linroid.ketch.app.instance.InstanceFactory
import com.linroid.ketch.app.instance.InstanceManager
import com.linroid.ketch.config.FileConfigStore
import com.linroid.ketch.core.Ketch
import com.linroid.ketch.engine.KtorHttpEngine
import com.linroid.ketch.ftp.FtpDownloadSource
import com.linroid.ketch.sqlite.DriverFactory
import com.linroid.ketch.sqlite.createSqliteTaskStore
import com.linroid.ketch.torrent.TorrentDownloadSource
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSUserDomainMask
import platform.UIKit.UIDevice

@Suppress("unused", "FunctionName")
fun MainViewController() = ComposeUIViewController {
  val instanceManager = remember {
    @Suppress("UNCHECKED_CAST")
    val docsDir = (NSSearchPathForDirectoriesInDomains(
      NSDocumentDirectory, NSUserDomainMask, true,
    ) as List<String>).first()
    val configStore = FileConfigStore("$docsDir/config.toml")
    val config = configStore.load()
    val taskStore = createSqliteTaskStore(DriverFactory())
    val downloadConfig = config.download.copy(
      defaultDirectory = config.download.defaultDirectory
        ?: docsDir,
    )
    val instanceName = config.name
      ?: UIDevice.currentDevice.name
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
      ),
      initialRemotes = config.remotes,
      configStore = configStore,
    )
  }
  DisposableEffect(Unit) {
    onDispose { instanceManager.close() }
  }
  App(instanceManager)
}
