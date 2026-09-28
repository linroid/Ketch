package com.linroid.ketch.app

import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.window.ComposeUIViewController
import com.linroid.ketch.api.log.LogLevel
import com.linroid.ketch.api.log.Logger
import com.linroid.ketch.app.instance.InstanceFactory
import com.linroid.ketch.app.instance.InstanceManager
import com.linroid.ketch.app.log.FileLogger
import com.linroid.ketch.app.state.IncomingDownloads
import com.linroid.ketch.config.FileConfigStore
import com.linroid.ketch.core.Ketch
import com.linroid.ketch.engine.KtorHttpEngine
import com.linroid.ketch.ftp.FtpDownloadSource
import com.linroid.ketch.sqlite.DriverFactory
import com.linroid.ketch.sqlite.createSqliteTaskStore
import com.linroid.ketch.torrent.TorrentConfig
import com.linroid.ketch.torrent.TorrentDownloadSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import okio.FileSystem
import okio.Path.Companion.toPath
import platform.Foundation.NSApplicationSupportDirectory
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSSearchPathDirectory
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSUserDomainMask
import platform.UIKit.UIDevice

/**
 * The app's log files, kept for bug reports and shared from Settings → About. One writer for
 * the process, however many times the view controller is created.
 */
private val fileLogger: FileLogger by lazy {
  FileLogger(
    fileSystem = FileSystem.SYSTEM,
    directory = userDirectory(NSApplicationSupportDirectory).toPath() / "logs",
    dispatcher = Dispatchers.IO,
    minLevel = LogLevel.DEBUG,
  )
}

/**
 * @param incoming files opened in Ketch; the Swift app offers them from `onOpenURL`.
 */
@Suppress("unused", "FunctionName")
fun MainViewController(incoming: IncomingDownloads) = ComposeUIViewController {
  val instanceManager = remember {
    val docsDir = userDirectory(NSDocumentDirectory)
    // Internal state stays out of Documents, which the Files app shows.
    val supportDir = userDirectory(NSApplicationSupportDirectory)
    val configStore = FileConfigStore("$docsDir/config.toml")
    val config = configStore.load()
    val taskStore = createSqliteTaskStore(DriverFactory())
    val instanceName = config.name
      ?: UIDevice.currentDevice.name
    val torrentSource = TorrentDownloadSource(
      TorrentConfig(
        stateDirectory = "$supportDir/torrent-state",
        additionalTrackers = config.torrent.trackers,
      ),
    )
    InstanceManager(
      factory = InstanceFactory(
        deviceName = instanceName,
        embeddedFactory = {
          Ketch(
            httpEngine = KtorHttpEngine(),
            taskStore = taskStore,
            config = config.download,
            name = instanceName,
            logger = Logger.combine(Logger.console(LogLevel.DEBUG), fileLogger),
            additionalSources = listOf(FtpDownloadSource(), torrentSource),
          )
        },
        applyTorrentSettings = { torrentSource.setAdditionalTrackers(it.trackers) },
      ),
      initialRemotes = config.remotes,
      configStore = configStore,
    )
  }
  DisposableEffect(Unit) {
    onDispose { instanceManager.close() }
  }
  App(instanceManager, incoming = incoming, fileLogger = fileLogger)
}

@Suppress("UNCHECKED_CAST")
private fun userDirectory(directory: NSSearchPathDirectory): String =
  (NSSearchPathForDirectoriesInDomains(directory, NSUserDomainMask, true) as List<String>).first()
