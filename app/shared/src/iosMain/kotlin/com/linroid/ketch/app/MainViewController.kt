package com.linroid.ketch.app

import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
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
import com.linroid.ketch.app.platform.FolderBookmarks
import com.linroid.ketch.app.platform.rememberFileActions
import com.linroid.ketch.app.state.IncomingDownloads
import com.linroid.ketch.app.state.LOCAL_DEVICE_ID
import com.linroid.ketch.app.state.ObservedPeak
import com.linroid.ketch.app.state.PulseModel
import com.linroid.ketch.app.state.SpeedModeController
import com.linroid.ketch.app.state.rememberAppController
import com.linroid.ketch.config.FileConfigStore
import com.linroid.ketch.config.KetchConfig
import com.linroid.ketch.core.Ketch
import com.linroid.ketch.engine.KtorHttpEngine
import com.linroid.ketch.ftp.FtpDownloadSource
import com.linroid.ketch.sqlite.DriverFactory
import com.linroid.ketch.sqlite.createSqliteTaskStore
import com.linroid.ketch.torrent.TorrentConfig
import com.linroid.ketch.torrent.TorrentDownloadSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.IO
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import okio.FileSystem
import okio.IOException
import okio.Path.Companion.toPath
import platform.Foundation.NSApplicationSupportDirectory
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSSearchPathDirectory
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSUserDomainMask
import platform.UIKit.UIDevice
import kotlin.time.Duration.Companion.seconds

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
  val speedMode = remember(instanceManager) { LocalSpeedMode(instanceManager) }
  DisposableEffect(speedMode) {
    onDispose { speedMode.close() }
  }
  val controller = rememberAppController(
    instanceManager = instanceManager,
    incoming = incoming,
    speedMode = speedMode.controller,
  )
  LaunchedEffect(controller) { speedMode.follow(controller.pulse) }
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

/**
 * Speed mode of this device: full speed, the slow lane or Auto speed rules, which the app
 * switches. It runs in a scope of its own on the main thread, like the app's settings, and saves
 * the speed settings and the observed peak to `config.toml` as they change.
 */
private class LocalSpeedMode(manager: InstanceManager) {
  private val scope = MainScope()
  private val speed = MutableStateFlow(0L)
  private val configStore = manager.configStore

  /** The controller of the embedded device's speed; `null` without one. */
  val controller: SpeedModeController? = manager.embedded?.let { embedded ->
    val config = configStore?.load() ?: KetchConfig()
    SpeedModeController(
      config = { embedded.status().config },
      apply = { embedded.updateConfig(it) },
      scope = scope,
      settings = config.speed,
      observedPeak = ObservedPeak(config.ui.observedPeak, config.ui.observedPeakAt),
      speed = speed,
    ).also(::save)
  }

  /** Records this device's speed from [pulse] until cancelled; it raises the observed peak. */
  suspend fun follow(pulse: PulseModel) {
    pulse.devices.collect { devices ->
      speed.value = devices.firstOrNull { it.deviceId == LOCAL_DEVICE_ID }?.speed ?: 0
    }
  }

  fun close() {
    scope.cancel()
  }

  @OptIn(FlowPreview::class)
  private fun save(speedMode: SpeedModeController) {
    scope.launch {
      speedMode.settings.drop(1).collect { speed -> saveConfig { it.copy(speed = speed) } }
    }
    scope.launch {
      // The peak climbs in steps while a download speeds up.
      speedMode.observedPeak.drop(1).debounce(PEAK_SAVE_DELAY).collect { peak ->
        saveConfig {
          it.copy(
            ui = it.ui.copy(
              observedPeak = peak.bytesPerSecond,
              observedPeakAt = peak.atEpochMillis,
            ),
          )
        }
      }
    }
  }

  private fun saveConfig(transform: (KetchConfig) -> KetchConfig) {
    val store = configStore ?: return
    try {
      store.save(transform(store.load()))
    } catch (e: Exception) {
      log.w { "Couldn't save the speed settings to config.toml: ${e.describeCauses()}" }
    }
  }

  private companion object {
    val PEAK_SAVE_DELAY = 10.seconds
  }
}

private fun createInstanceManager(): InstanceManager {
  KetchLogger.setLogger(appLogger)
  // Before any download resumes into a folder picked outside the app's container.
  FolderBookmarks().restore()
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

// Downloads go to Ketch's default folder, Documents/Downloads, which the Files app shows as On My
// iPhone › Ketch › Downloads; it is created up front so it shows there before the first download.
// A saved folder that is gone, such as one in the app's container before an update moved it,
// falls back there too. The default stays unset, so Settings can tell it from a chosen folder.
private fun DownloadConfig.inDownloadsFolder(folder: String): DownloadConfig {
  val fileSystem = FileSystem.SYSTEM
  val saved = defaultDirectory
  return try {
    if (saved != null && fileSystem.metadataOrNull(saved.toPath())?.isDirectory == true) {
      return this
    }
    fileSystem.createDirectories(folder.toPath())
    copy(defaultDirectory = null)
  } catch (e: IOException) {
    log.w { "Could not create the Downloads folder: ${e.describeCauses()}" }
    this
  }
}

@Suppress("UNCHECKED_CAST")
private fun userDirectory(directory: NSSearchPathDirectory): String =
  (NSSearchPathForDirectoriesInDomains(directory, NSUserDomainMask, true) as List<String>).first()
