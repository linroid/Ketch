package com.linroid.ketch.app

import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.window.ComposeUIViewController
import com.linroid.ketch.api.DownloadConfig
import com.linroid.ketch.api.log.KetchLogger
import com.linroid.ketch.api.log.LogLevel
import com.linroid.ketch.api.log.Logger
import com.linroid.ketch.api.log.describeCauses
import com.linroid.ketch.app.feedback.IosNotifier
import com.linroid.ketch.app.feedback.reportActivity
import com.linroid.ketch.app.instance.InstanceFactory
import com.linroid.ketch.app.instance.InstanceManager
import com.linroid.ketch.app.log.FileLogger
import com.linroid.ketch.app.platform.rememberFileActions
import com.linroid.ketch.app.state.IncomingDownloads
import com.linroid.ketch.app.state.rememberAppController
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
import okio.IOException
import okio.Path.Companion.toPath
import platform.Foundation.NSApplicationSupportDirectory
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSSearchPathDirectory
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSUserDomainMask
import platform.UIKit.UIDevice

private val log = KetchLogger("MainViewController")

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

private val appLogger: Logger by lazy {
  Logger.combine(Logger.console(LogLevel.DEBUG), fileLogger)
}

/**
 * @param incoming files opened in Ketch; the Swift app offers them from `onOpenURL`.
 */
@Suppress("unused", "FunctionName")
fun MainViewController(incoming: IncomingDownloads) = ComposeUIViewController {
  val instanceManager = remember { createInstanceManager() }
  DisposableEffect(Unit) {
    onDispose { instanceManager.close() }
  }
  val controller = rememberAppController(instanceManager, incoming = incoming)
  val fileActions by rememberUpdatedState(rememberFileActions())
  // This view controller owns the activity monitor: toasts while Ketch is in front,
  // notifications otherwise.
  val activityEvents = remember(controller) {
    IosNotifier.reportActivity(controller) { fileActions }
  }
  DisposableEffect(controller) {
    val background = instanceManager.embedded?.let { embedded ->
      KetchBackground.attach(embedded, controller.messages, controller.state.pendingOps) {
        controller.state.showAddRemoteDialog = true
      }
    }
    onDispose { background?.dispose() }
  }
  App(controller, activityEvents = activityEvents, fileLogger = fileLogger)
}

private fun createInstanceManager(): InstanceManager {
  KetchLogger.setLogger(appLogger)
  val docsDir = userDirectory(NSDocumentDirectory)
  // Internal state stays out of Documents, which the Files app shows.
  val supportDir = userDirectory(NSApplicationSupportDirectory)
  val configPath = "$supportDir/config.toml"
  moveConfig(from = "$docsDir/config.toml", to = configPath)
  val configStore = FileConfigStore(configPath)
  val config = configStore.load()
  val download = config.download.inDownloadsFolder("$docsDir/Downloads")
  val taskStore = createSqliteTaskStore(DriverFactory())
  val instanceName = config.name
    ?: UIDevice.currentDevice.name
  val torrentSource = TorrentDownloadSource(
    TorrentConfig(
      stateDirectory = "$supportDir/torrent-state",
      additionalTrackers = config.torrent.trackers,
    ),
  )
  return InstanceManager(
    factory = InstanceFactory(
      deviceName = instanceName,
      embeddedFactory = {
        Ketch(
          httpEngine = KtorHttpEngine(),
          taskStore = taskStore,
          config = download,
          name = instanceName,
          logger = appLogger,
          additionalSources = listOf(FtpDownloadSource(), torrentSource),
        )
      },
      applyTorrentSettings = { torrentSource.setAdditionalTrackers(it.trackers) },
    ),
    initialRemotes = config.remotes,
    configStore = configStore,
  )
}

// config.toml used to live in Documents, where the Files app shows it to anyone holding the
// phone; it holds API tokens and AI keys, so it moves out once.
private fun moveConfig(from: String, to: String) {
  val fileSystem = FileSystem.SYSTEM
  val target = to.toPath()
  if (fileSystem.exists(target)) return
  // A save interrupted before its rename leaves only the .tmp file.
  val source = listOf(from, "$from.tmp").map { it.toPath() }.firstOrNull(fileSystem::exists)
    ?: return
  try {
    target.parent?.let(fileSystem::createDirectories)
    fileSystem.atomicMove(source, target)
    fileSystem.delete("$from.tmp".toPath(), mustExist = false)
    log.i { "Moved config.toml from Documents to Application Support" }
  } catch (e: IOException) {
    log.w { "Could not move config.toml out of Documents: ${e.describeCauses()}" }
  }
}

// Downloads go to Documents/Downloads, which the Files app shows as On My iPhone › Ketch ›
// Downloads. A saved folder that is gone, such as one in the app's container before an update
// moved it, falls back there too.
private fun DownloadConfig.inDownloadsFolder(folder: String): DownloadConfig {
  val fileSystem = FileSystem.SYSTEM
  val saved = defaultDirectory
  return try {
    if (saved != null && fileSystem.metadataOrNull(saved.toPath())?.isDirectory == true) {
      return this
    }
    fileSystem.createDirectories(folder.toPath())
    copy(defaultDirectory = folder)
  } catch (e: IOException) {
    log.w { "Could not create the Downloads folder: ${e.describeCauses()}" }
    this
  }
}

@Suppress("UNCHECKED_CAST")
private fun userDirectory(directory: NSSearchPathDirectory): String =
  (NSSearchPathForDirectoriesInDomains(directory, NSUserDomainMask, true) as List<String>).first()
