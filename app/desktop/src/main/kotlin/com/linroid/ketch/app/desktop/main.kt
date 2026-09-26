package com.linroid.ketch.app.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.window.ApplicationScope
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import com.linroid.ketch.api.log.Logger
import com.linroid.ketch.app.App
import com.linroid.ketch.app.instance.InstanceFactory
import com.linroid.ketch.app.instance.InstanceManager
import com.linroid.ketch.app.instance.LocalServerHandle
import com.linroid.ketch.app.state.EmbeddedAiDiscoveryProviderFactory
import com.linroid.ketch.app.state.IncomingDownloads
import com.linroid.ketch.config.FileConfigStore
import com.linroid.ketch.config.defaultConfigDir
import com.linroid.ketch.core.Ketch
import com.linroid.ketch.engine.KtorHttpEngine
import com.linroid.ketch.engine.withNetworkInterfaces
import com.linroid.ketch.ftp.FtpDownloadSource
import com.linroid.ketch.server.KetchServer
import com.linroid.ketch.sqlite.DriverFactory
import com.linroid.ketch.sqlite.createSqliteTaskStore
import com.linroid.ketch.torrent.TorrentConfig
import com.linroid.ketch.torrent.TorrentDownloadSource
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import java.awt.Desktop
import java.io.File
import java.net.InetAddress
import java.util.concurrent.Executors

private val isMac = System.getProperty("os.name").startsWith("Mac")

fun main(args: Array<String>) {
  val configDir = defaultConfigDir()
  val incoming = IncomingDownloads()
  // Raised when a later launch hands over its files, to bring the window forward.
  val focusRequests = MutableSharedFlow<Unit>(
    extraBufferCapacity = 1,
    onBufferOverflow = BufferOverflow.DROP_OLDEST,
  )
  // One thread keeps files in the order they were opened, off the UI thread.
  val fileReader = Executors.newSingleThreadExecutor { task ->
    Thread(task, "ketch-open-files").apply { isDaemon = true }
  }
  fun open(files: List<File>) {
    if (files.isNotEmpty()) fileReader.execute { incoming.offerFiles(files) }
  }

  val launchFiles = fileArguments(args.toList())
  val singleInstance = SingleInstance.acquire(
    File(configDir),
    launchFiles.map { it.path },
  ) { forwarded ->
    open(fileArguments(forwarded))
    focusRequests.tryEmit(Unit)
  } ?: return
  installOpenFileHandler(::open)
  open(launchFiles)

  application {
    KetchWindow(configDir, incoming, focusRequests, singleInstance)
  }
}

@Composable
private fun ApplicationScope.KetchWindow(
  configDir: String,
  incoming: IncomingDownloads,
  focusRequests: Flow<Unit>,
  singleInstance: SingleInstance,
) {
  val instanceManager = remember {
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
              TorrentDownloadSource(
                TorrentConfig(
                  stateDirectory = configDir + File.separator + "torrent-state",
                  additionalTrackers = configStore.load().torrent.trackers,
                ),
              ),
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
  val openSettings = remember { MutableSharedFlow<Unit>(extraBufferCapacity = 1) }
  DisposableEffect(Unit) {
    // Enables the native "Settings…" item (⌘,) in the macOS app menu.
    if (Desktop.isDesktopSupported()) {
      val desktop = Desktop.getDesktop()
      if (desktop.isSupported(Desktop.Action.APP_PREFERENCES)) {
        desktop.setPreferencesHandler { openSettings.tryEmit(Unit) }
      }
    }
    onDispose {
      instanceManager.close()
      singleInstance.close()
    }
  }
  val windowStateStore = remember {
    WindowStateStore(File(configDir, "window.properties"))
  }
  val savedBounds = remember { windowStateStore.load() }
  val windowState = remember { initialWindowState(savedBounds) }
  LaunchedEffect(windowState) {
    windowStateStore.saveChanges(windowState, savedBounds)
  }
  Window(
    onCloseRequest = ::exitApplication,
    state = windowState,
    title = "Ketch",
    icon = painterResource("icon.svg"),
    onPreviewKeyEvent = { event ->
      if (event.isOpenSettingsShortcut()) openSettings.tryEmit(Unit) else false
    },
  ) {
    LaunchedEffect(Unit) {
      focusRequests.collect {
        windowState.isMinimized = false
        window.toFront()
      }
    }
    App(instanceManager, aiProviderFactory, openSettings, incoming)
  }
}

/** ⌘, on macOS and Ctrl+, elsewhere, the usual shortcut for settings. */
private fun KeyEvent.isOpenSettingsShortcut(): Boolean =
  type == KeyEventType.KeyDown && key == Key.Comma &&
    (if (isMac) isMetaPressed else isCtrlPressed) &&
    !isAltPressed && !isShiftPressed
