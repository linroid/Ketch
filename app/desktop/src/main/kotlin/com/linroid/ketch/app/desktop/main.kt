package com.linroid.ketch.app.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.window.ApplicationScope
import androidx.compose.ui.window.LocalWindowExceptionHandlerFactory
import androidx.compose.ui.window.Notification
import androidx.compose.ui.window.TrayState
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowExceptionHandler
import androidx.compose.ui.window.WindowExceptionHandlerFactory
import androidx.compose.ui.window.application
import androidx.compose.ui.window.isTraySupported
import androidx.compose.ui.window.rememberTrayState
import com.linroid.ketch.api.DownloadState
import com.linroid.ketch.api.KetchApi
import com.linroid.ketch.api.log.KetchLogger
import com.linroid.ketch.api.log.LogLevel
import com.linroid.ketch.api.log.Logger
import com.linroid.ketch.api.log.describeCauses
import com.linroid.ketch.app.App
import com.linroid.ketch.app.feedback.ActivityEvent
import com.linroid.ketch.app.feedback.ActivityMonitor
import com.linroid.ketch.app.feedback.ActivityRouting
import com.linroid.ketch.app.feedback.SuccessFeedback
import com.linroid.ketch.app.feedback.MessageLevel
import com.linroid.ketch.app.feedback.MessageNotifications
import com.linroid.ketch.app.feedback.NotificationCopy
import com.linroid.ketch.app.feedback.SystemNotifier
import com.linroid.ketch.app.feedback.UnreadableFile
import com.linroid.ketch.app.feedback.UnreadableFiles
import com.linroid.ketch.app.feedback.pairingNotificationCopy
import com.linroid.ketch.app.i18n.load
import com.linroid.ketch.app.i18n.resolve
import com.linroid.ketch.app.i18n.text
import com.linroid.ketch.app.i18n.verbatim
import com.linroid.ketch.app.input.KetchCommand
import com.linroid.ketch.app.input.KetchCommands
import com.linroid.ketch.app.input.ShortcutContext
import com.linroid.ketch.app.input.ShortcutMatcher
import com.linroid.ketch.app.instance.EmbeddedInstance
import com.linroid.ketch.app.instance.InstanceFactory
import com.linroid.ketch.app.instance.InstanceManager
import com.linroid.ketch.app.instance.LocalServerHandle
import com.linroid.ketch.app.instance.TrackerListStatus
import com.linroid.ketch.app.instance.displayName
import com.linroid.ketch.app.log.FileLogger
import com.linroid.ketch.app.platform.LocalAppUpdates
import com.linroid.ketch.app.platform.LocalDesktopHooks
import com.linroid.ketch.app.platform.LocalIntegrationStatus
import com.linroid.ketch.app.platform.rememberFileActions
import com.linroid.ketch.app.platform.rememberSystemClipboard
import com.linroid.ketch.app.state.AppController
import com.linroid.ketch.app.state.DiscoverHistoryStore
import com.linroid.ketch.app.state.EmbeddedAiDiscoveryProviderFactory
import com.linroid.ketch.app.state.FileDiscoverHistoryStore
import com.linroid.ketch.app.state.IncomingDownloads
import com.linroid.ketch.app.state.LOCAL_DEVICE_ID
import com.linroid.ketch.app.state.LinkSource
import com.linroid.ketch.app.state.ObservedPeak
import com.linroid.ketch.app.state.PulseCounts
import com.linroid.ketch.app.state.PulseModel
import com.linroid.ketch.app.state.SettingsTarget
import com.linroid.ketch.app.state.SpeedModeController
import com.linroid.ketch.app.state.StatusFilter
import com.linroid.ketch.app.state.isPairingLink
import com.linroid.ketch.app.state.waitsInQueue
import com.linroid.ketch.app.theme.LocalWindowChrome
import com.linroid.ketch.app.theme.isDark
import com.linroid.ketch.app.ui.shell.LocalHostShortcuts
import com.linroid.ketch.app.util.displayName
import com.linroid.ketch.config.ConfigStore
import com.linroid.ketch.config.FileConfigStore
import com.linroid.ketch.config.KetchConfig
import com.linroid.ketch.config.NotificationSettings
import com.linroid.ketch.config.defaultConfigDir
import com.linroid.ketch.core.Ketch
import com.linroid.ketch.engine.KtorHttpEngine
import com.linroid.ketch.engine.withNetworkInterfaces
import com.linroid.ketch.dash.DashDownloadSource
import com.linroid.ketch.ftp.FtpDownloadSource
import com.linroid.ketch.hls.HlsDownloadSource
import com.linroid.ketch.server.KetchServer
import com.linroid.ketch.sqlite.DriverFactory
import com.linroid.ketch.sqlite.createSqliteTaskStore
import com.linroid.ketch.torrent.TorrentConfig
import com.linroid.ketch.torrent.TorrentDownloadSource
import com.linroid.ketch.updater.GitHubReleases
import com.linroid.ketch.updater.ReleaseDownloader
import com.linroid.ketch.updater.ReleasePlatform
import com.linroid.ketch.updater.ReleaseVersion
import ketch.app.desktop.generated.resources.Res
import ketch.app.desktop.generated.resources.message_window_error
import ketch.app.desktop.generated.resources.notify_added
import ketch.app.desktop.generated.resources.notify_names_more
import ketch.app.desktop.generated.resources.notify_names_three
import ketch.app.desktop.generated.resources.notify_names_two
import ketch.app.desktop.generated.resources.notify_on_device
import ketch.app.desktop.generated.resources.notify_still_running
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okio.FileSystem
import okio.Path.Companion.toOkioPath
import java.awt.Desktop
import java.awt.Dimension
import java.awt.EventQueue
import java.awt.desktop.QuitResponse
import java.io.File
import java.util.concurrent.Executors
import kotlin.concurrent.thread
import kotlin.system.exitProcess
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

private val log = KetchLogger("DesktopApp")

fun main(args: Array<String>) {
  // A portable copy keeps its data in its own folder, and so does its browser extension host.
  val portable = PortableApp.detect()
  val configDir = portable?.writableDataDir() ?: File(defaultConfigDir())
  if (args.firstOrNull() == NativeMessagingHost.FLAG) {
    // Started by a browser for the Ketch extension, not by the user.
    runNativeMessagingHost(configDir)
    return
  }
  if (DesktopOs.current == DesktopOs.MAC) {
    // Read once AWT starts: menus go to the top of the screen, which also tints the tray icon.
    System.setProperty("apple.laf.useScreenMenuBar", "true")
    System.setProperty("apple.awt.enableTemplateImages", "true")
  }
  val incoming = IncomingDownloads()
  // Asks for the window: a later launch, or a file or link the system opened with Ketch.
  val windowRequests = Channel<Unit>(Channel.CONFLATED)
  // One thread keeps files in the order they were opened, off the UI thread.
  val fileReader = Executors.newSingleThreadExecutor { task ->
    Thread(task, "ketch-open-files").apply { isDaemon = true }
  }
  fun open(opened: OpenedArguments, source: LinkSource) {
    if (opened.files.isNotEmpty()) fileReader.execute { incoming.offerFiles(opened.files) }
    val (pairings, links) = opened.links.partition(::isPairingLink)
    pairings.forEach { incoming.offerLink(it, source) }
    incoming.offerLinks(links, source)
  }

  val launched = fileArguments(args.toList())
  val background = BACKGROUND_FLAG in args
  val registration = NativeHostRegistration(configDir, File(System.getProperty("user.home")))
  val integration = DesktopIntegrationStatus(
    file = File(configDir, "browser-extension.properties"),
    browsers = registration::browsers,
  )
  val extensionServer = BrowserExtensionServer(
    onConnect = { integration.extensionConnected(connectingBrowser()) },
  )
  val singleInstance = SingleInstance.acquire(
    configDir,
    launched.toArguments() + listOfNotNull(BACKGROUND_FLAG.takeIf { background }),
    onRequest = { request ->
      if (request == NativeMessagingHost.CONNECT_REQUEST) extensionServer.connect() else null
    },
  ) { forwarded ->
    open(fileArguments(forwarded), LinkSource.Arguments)
    // The browser extension starts Ketch hidden; any other launch brings the window forward.
    if (BACKGROUND_FLAG !in forwarded) windowRequests.trySend(Unit)
  } ?: return
  val logLevel = logLevel()
  val fileLogger = FileLogger(
    fileSystem = FileSystem.SYSTEM,
    directory = configDir.toOkioPath() / LOGS_DIR,
    dispatcher = Dispatchers.IO,
    minLevel = logLevel,
  )
  val discoverHistory = FileDiscoverHistoryStore(
    fileSystem = FileSystem.SYSTEM,
    path = configDir.toOkioPath() / DISCOVER_HISTORY_FILE,
    dispatcher = Dispatchers.IO,
  )
  // application() ends with exitProcess, which still runs shutdown hooks. The Discover history
  // goes first, so what it logs on the way reaches the log file.
  val closeFiles = Thread {
    runBlocking {
      withTimeoutOrNull(QUIT_COMMIT_TIMEOUT) { discoverHistory.close() }
      withTimeoutOrNull(2.seconds) { fileLogger.close() }
    }
  }
  Runtime.getRuntime().addShutdownHook(closeFiles)
  val logger = Logger.combine(Logger.console(logLevel), fileLogger)
  // Ketch installs it too, but the host name below is resolved before Ketch exists.
  KetchLogger.setLogger(logger)
  // Failures nothing catches, on any thread, go to logs/ketch.log for bug reports.
  Thread.setDefaultUncaughtExceptionHandler { thread, e ->
    log.e(e) { "Uncaught exception in thread ${thread.name}: ${e.describeCauses()}" }
  }
  if (portable != null) {
    if (configDir == portable.dataDir) {
      log.i { "Portable copy: data is kept in $configDir" }
    } else {
      log.w { "Portable copy: can't write to ${portable.dataDir}, so data is kept in $configDir" }
    }
  }
  installOpenFileHandler { files ->
    open(OpenedArguments(files = files), LinkSource.Arguments)
    windowRequests.trySend(Unit)
  }
  installOpenUriHandler { uri ->
    open(fileArguments(listOf(uri)), LinkSource.OpenUrl)
    windowRequests.trySend(Unit)
  }
  open(launched, LinkSource.Arguments)
  registerNativeHost(registration, integration, logger)
  // Before the UI thread starts: this can run a command and, as a last resort, wait on a lookup.
  val hostName = localHostName()

  val launch = LaunchContext(
    configDir = configDir,
    hostName = hostName,
    incoming = incoming,
    windowRequests = windowRequests.receiveAsFlow(),
    background = background,
    singleInstance = singleInstance,
    extensionServer = extensionServer,
    integration = integration,
    logger = logger,
    fileLogger = fileLogger,
    discoverHistory = discoverHistory,
    openFiles = { files -> open(OpenedArguments(files = files), LinkSource.Arguments) },
  )
  // The UI shows the language of the JVM's default locale, which the JDK takes from the system:
  // the first of the user's preferred languages on macOS, which includes a language picked for
  // Ketch in System Settings, the display language on Windows, and LANG or LC_MESSAGES on Linux.
  try {
    application { KetchApp(launch) }
  } catch (e: Throwable) {
    // Quit rather than linger without a window, holding the single-instance lock that would
    // hand every later launch to this process. The shutdown hook flushes the log file.
    log.e(e) { "The app failed: ${e.describeCauses()}" }
    exitProcess(1)
  }
}

/**
 * What [main] hands the app once this process is the running instance.
 *
 * @property windowRequests emits when the window should come forward.
 * @property background whether the app starts hidden ([BACKGROUND_FLAG]).
 * @property integration how Ketch is wired into the browsers and the system, for Settings.
 * @property discoverHistory the Discover sessions, kept in `discover-history.json`.
 * @property openFiles adds `.torrent` files, as if opened from the file manager.
 */
private class LaunchContext(
  val configDir: File,
  val hostName: String,
  val incoming: IncomingDownloads,
  val windowRequests: Flow<Unit>,
  val background: Boolean,
  val singleInstance: SingleInstance,
  val extensionServer: BrowserExtensionServer,
  val integration: DesktopIntegrationStatus,
  val logger: Logger,
  val fileLogger: FileLogger,
  val discoverHistory: DiscoverHistoryStore,
  val openFiles: (List<File>) -> Unit,
)

/**
 * Lets the browser extension find this app, see [NativeHostRegistration], then looks for the
 * browsers and default apps [integration] reports.
 */
private fun registerNativeHost(
  registration: NativeHostRegistration,
  integration: DesktopIntegrationStatus,
  logger: Logger,
) {
  thread(isDaemon = true, name = "ketch-native-host-registration") {
    try {
      val registered = registration.register()
      logger.d("[NativeHost] Registered for the browser extension: $registered")
    } catch (e: Exception) {
      logger.w("[NativeHost] Couldn't register for the browser extension", e)
    }
    integration.refresh()
  }
}

/**
 * The app: the devices, the controller and the activity monitor live here, outside the window,
 * so closing the window keeps downloads running and the tray, the menu bar and the Dock working.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun ApplicationScope.KetchApp(launch: LaunchContext) {
  val unreadableFiles = remember { UnreadableFiles() }
  val configStore = remember {
    FileConfigStore(File(launch.configDir, "config.toml").path) { unreadable ->
      unreadableFiles.report(UnreadableFile(UnreadableFile.Kind.Settings, unreadable.movedTo))
    }
  }
  val localSpeed = remember { LocalSpeedMode(configStore) }
  val controller = remember {
    val manager = createInstanceManager(launch, configStore, unreadableFiles)
    AppController(
      instanceManager = manager,
      aiProviderFactory = EmbeddedAiDiscoveryProviderFactory(),
      incoming = launch.incoming,
      speedMode = localSpeed.attach(manager),
      unreadableFiles = unreadableFiles,
      discoverHistory = launch.discoverHistory,
    ).also { localSpeed.follow(it.pulse) }
  }
  val resources = remember { AppResources(controller, localSpeed, launch) }
  DisposableEffect(resources) {
    onDispose { resources.close() }
  }
  val desktopSettings = remember { controller.appSettings.config.desktop }
  val trayState = rememberTrayState()
  val notifier = remember { TrayNotifier(trayState) }
  val files = rememberFileActions()
  val clipboard = rememberSystemClipboard()
  val scope = rememberCoroutineScope()

  val windowStateStore = remember {
    WindowStateStore(File(launch.configDir, "window.properties"))
  }
  val savedBounds = remember { windowStateStore.load() }
  val windowState = remember { initialWindowState(savedBounds) }
  LaunchedEffect(windowState) {
    windowStateStore.saveChanges(windowState, savedBounds)
  }
  val behavior = remember {
    CloseBehavior(
      windowState = windowState,
      closeAction = desktopSettings.closeAction,
      startHidden = launch.background,
      traySupported = isTraySupported,
      activeDownloads = { activeDownloads(controller.instanceManager) },
      saveCloseAction = { action ->
        saveConfig(configStore) { it.copy(desktop = it.desktop.copy(closeAction = action)) }
      },
      onFirstHide = { scope.launch { announceBackground(trayState) } },
      quit = { response ->
        scope.launch {
          quit(controller, resources, launch.discoverHistory, response) { exitApplication() }
        }
      },
    )
  }
  val hooks = remember {
    DesktopHooksImpl(behavior, desktopSettings, File(launch.configDir, LOGS_DIR), files)
  }
  LaunchedEffect(hooks) {
    val integration = controller.appSettings.config.integration
    withContext(Dispatchers.IO) { hooks.refresh(integration) }
  }
  val providedHooks = remember(hooks) { hooks.refreshingDefaults(launch.integration) }
  val fullWindowContent = remember { usesFullWindowContent() }

  val settingsWindow = remember { SettingsWindowState(controller.state) }
  DisposableEffect(settingsWindow) {
    val claim = settingsWindow.claimRequests()
    onDispose { claim.dispose() }
  }
  val settingsStateStore = remember {
    WindowStateStore(File(launch.configDir, "settings-window.properties"))
  }
  val savedSettingsBounds = remember { settingsStateStore.load() }
  val settingsWindowState = remember {
    initialWindowState(
      saved = savedSettingsBounds,
      defaultSize = SettingsWindowSize,
      minimumSize = MinSettingsWindowSize,
    )
  }
  LaunchedEffect(settingsWindowState) {
    settingsStateStore.saveChanges(settingsWindowState, savedSettingsBounds)
  }
  val updater = remember { createUpdater(launch, controller, behavior, scope) }
  LaunchedEffect(updater) { updater.start(desktopSettings.checkForUpdates) }
  // The Settings window closes and minimizes itself, from its own menu bar or keys.
  val actions = remember {
    DesktopActions(
      showWindow = behavior::showWindow,
      closeWindow = behavior::closeWindow,
      quit = { behavior.requestQuit() },
      openFiles = launch.openFiles,
      checkForUpdates = updater::check,
    )
  }
  val active by controller.state.activeInstance.collectAsState()
  // Speed modes only switch this computer for now.
  val speedMode = controller.speedMode.takeIf { active == null || active is EmbeddedInstance }
  val commands = remember(speedMode, files, clipboard) {
    DesktopCommands(controller, actions, speedMode, files, clipboard) {
      windowState.isMinimized = true
    }
  }
  LaunchedEffect(behavior) {
    launch.windowRequests.collect { behavior.showWindow() }
  }
  LaunchedEffect(behavior) {
    // The shortcut sheet shows in the main window, also when the Settings window asks for it.
    snapshotFlow { controller.state.shortcutsRequested }
      .filter { it }
      .collect { behavior.showWindow() }
  }
  DisposableEffect(behavior) {
    val remove = installAppHandlers(behavior)
    onDispose { remove() }
  }
  DisposableEffect(settingsWindow) {
    val remove = installSettingsHandlers(
      onSettings = settingsWindow::show,
      onAbout = { settingsWindow.open(SettingsTarget(SettingsTarget.Page.About)) },
    )
    onDispose { remove() }
  }

  // The window exists only while it shows, so what must work while it is hidden lives out here.
  // Disposing it returns the GPU memory and UI it would otherwise keep in the background; what
  // its content saves, such as the page, the filter and scroll positions, is kept here meanwhile.
  val windowStates = rememberSaveableStateHolder()
  var mainWindow by remember { mutableStateOf<ComposeWindow?>(null) }
  var windowFocused by remember { mutableStateOf(false) }
  // The focus the window last reported only counts while it shows.
  fun mainInFront() = behavior.windowVisible && windowFocused && !windowState.isMinimized
  // Toasts show in the main window, which stays in view beside the Settings window.
  fun inFront() = behavior.windowVisible && !windowState.isMinimized &&
    (windowFocused || settingsWindow.focused)
  val status = rememberDesktopStatus(controller, controller.pulse.state, mainInFront())
  val successFeedback = remember { SuccessFeedback(DesktopFeedbackPlayer()) }
  val activityEvents = remember {
    reportDesktopActivity(controller, notifier, successFeedback, ::inFront)
  }
  // Pairing requests ask in the main window; from the tray while it is not in front.
  remember { notifyPairingRequests(controller, notifier, ::mainInFront) }
  LaunchedEffect(controller, notifier) {
    // Such as Discover waiting for an OK, which would wait unseen while the window is away.
    MessageNotifications.follow(
      messages = controller.messages,
      notifier = notifier,
      settings = { controller.appSettings.config.notifications },
      inFront = snapshotFlow { inFront() },
    )
  }
  LaunchedEffect(controller, successFeedback) {
    successFeedback.follow(controller.state.aiDiscover) {
      controller.appSettings.config.notifications
    }
  }
  KetchTray(controller, status, actions, speedMode, trayState)
  TaskbarFeedback(status, hooks.dockBadge, mainWindow)
  // Text resolves as it composes, so the menus compose again in a new language.
  val language = controller.appSettings.language
  if (DesktopOs.current == DesktopOs.MAC) {
    key(language) {
      DockMenu(commands, status.pulse.counts)
      val instances by controller.state.instances.collectAsState()
      val context = menuBarContext(controller, status, speedMode, files, instances, updates = true)
      val menus = menuBar(context)
      DefaultMenuBar(menus, commands::perform)
    }
  }
  CloseDialogs(behavior, controller.appSettings)
  // Closed to the menu bar, Ketch leaves the Dock until a window opens again.
  MacDockPresence(shown = behavior.windowVisible || settingsWindow.isOpen)

  val icon = painterResource("icon.svg")
  val exceptionHandlers = remember { windowExceptionHandlers(controller) }
  CompositionLocalProvider(LocalWindowExceptionHandlerFactory provides exceptionHandlers) {
    if (behavior.windowVisible) {
      Window(
        onCloseRequest = behavior::closeWindow,
        state = windowState,
        title = windowTitle(status.pulse).resolve(),
        icon = icon,
        onPreviewKeyEvent = { event ->
          val command = windowShortcuts.match(event, ShortcutContext())
          when (command) {
            null -> Unit
            // Settings has a window of its own, which opens without bringing this one forward.
            KetchCommands.Settings -> settingsWindow.show()
            else -> commands.run(command)
          }
          command != null
        },
      ) {
        LaunchedEffect(Unit) {
          window.minimumSize = Dimension(MIN_WINDOW_WIDTH, MIN_WINDOW_HEIGHT)
          bringToFront(window)
        }
        DisposableEffect(window) {
          mainWindow = window
          onDispose {
            mainWindow = null
            windowFocused = false
          }
        }
        LaunchedEffect(behavior) {
          behavior.frontRequests.collect { bringToFront(window) }
        }
        val focused = LocalWindowInfo.current.isWindowFocused
        SideEffect { windowFocused = focused }
        MacTitleBar(fullWindowContent, darkTheme = controller.appSettings.themeMode.isDark())
        key(language) { KetchMenuBar(controller, status, actions, speedMode) }
        val shellSkips = remember(speedMode) {
          hostShortcuts(DesktopOs.current, slowLane = speedMode != null)
        }
        CompositionLocalProvider(
          LocalDesktopHooks provides providedHooks,
          LocalIntegrationStatus provides launch.integration.status,
          LocalWindowChrome provides windowChrome(fullWindowContent, windowState.placement),
          LocalHostShortcuts provides shellSkips,
          LocalAppUpdates provides updater,
        ) {
          val app = @Composable {
            windowStates.SaveableStateProvider(MAIN_WINDOW_STATE) {
              App(controller, activityEvents = activityEvents, fileLogger = launch.fileLogger)
            }
          }
          if (fullWindowContent) TitleBarArea(windowState, app) else app()
        }
      }
    }
    SettingsWindow(
      settings = settingsWindow,
      windowState = settingsWindowState,
      icon = icon,
      controller = controller,
      hooks = providedHooks,
      integration = launch.integration,
      fileLogger = launch.fileLogger,
      updates = updater,
      onQuit = { behavior.requestQuit() },
    )
  }
}

private fun createInstanceManager(
  launch: LaunchContext,
  configStore: ConfigStore,
  unreadableFiles: UnreadableFiles,
): InstanceManager {
  val configDir = launch.configDir
  val config = configStore.load()
  val driverFactory = DriverFactory(File(configDir, "ketch.db").path) { unreadable ->
    unreadableFiles.report(UnreadableFile(UnreadableFile.Kind.Downloads, unreadable.movedTo))
  }
  val taskStore = createSqliteTaskStore(driverFactory)
  val instanceName = config.name?.ifEmpty { null } ?: launch.hostName
  val torrentSource = TorrentDownloadSource(
    TorrentConfig(
      stateDirectory = File(configDir, "torrent-state").path,
      additionalTrackers = config.torrent.trackers,
      trackerListUrl = config.torrent.subscribedTrackerList,
    ),
  )
  return InstanceManager(
    factory = InstanceFactory(
      deviceName = instanceName,
      embeddedFactory = {
        val httpEngine = KtorHttpEngine.withNetworkInterfaces()
        Ketch(
          httpEngine = httpEngine,
          taskStore = taskStore,
          config = config.download,
          name = instanceName,
          logger = launch.logger,
          additionalSources = listOf(
            FtpDownloadSource(), torrentSource,
            HlsDownloadSource(httpEngine), DashDownloadSource(httpEngine)
          ),
        ).also(launch.extensionServer::attach)
      },
      localServerFactory = { ketchApi, pairingRequests ->
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
          // With a token, pages on any site, such as the web app, may call the API, as
          // they still need the token. Without one, KetchServer refuses them.
          corsAllowedHosts = serverConfig.corsAllowedHosts.ifEmpty {
            if (serverConfig.apiToken == null) emptyList() else listOf("*")
          },
          allowedHosts = serverConfig.allowedHosts,
          allowedDirectories = serverConfig.allowedDirectories,
          mdnsEnabled = serverConfig.mdnsEnabled,
          pairingApprover = { request, address ->
            pairingRequests.ask(request.name, request.code, request.os, address)
          },
        )
        server.start(wait = false)
        object : LocalServerHandle {
          override fun stop() {
            server.stop()
          }
        }
      },
      applyTorrentSettings = {
        torrentSource.setAdditionalTrackers(it.trackers)
        torrentSource.setTrackerList(it.subscribedTrackerList)
      },
      trackerList = torrentSource.trackerList.map {
        TrackerListStatus(it.trackers, it.updatedAt, it.failed, it.updating)
      },
      refreshTrackerList = torrentSource::refreshTrackerList,
    ),
    initialRemotes = config.remotes,
    configStore = configStore,
  )
}

/**
 * The app's [DesktopUpdater], which keeps its files in the `updates` folder, logs its
 * installers to `logs/update.log`, quits through [behavior] to install and tells the user what
 * it finds in toasts.
 */
private fun createUpdater(
  launch: LaunchContext,
  controller: AppController,
  behavior: CloseBehavior,
  scope: CoroutineScope,
): DesktopUpdater {
  val workDir = File(launch.configDir, UPDATES_DIR)
  val log = File(File(launch.configDir, LOGS_DIR), UPDATE_LOG)
  lateinit var updater: DesktopUpdater
  updater = DesktopUpdater(
    scope = scope,
    feed = GitHubReleases({ KtorHttpEngine() }),
    current = ReleaseVersion.parse(KetchApi.VERSION),
    platform = ReleasePlatform.current(),
    installer = UpdateInstaller.forThisApp(workDir, log),
    workDir = workDir,
    download = ReleaseDownloader({ KtorHttpEngine() }, launch.logger)::download,
    quit = behavior::quitWithoutAsking,
    onEvent = { event -> postUpdateNotice(event, controller.messages, updater) },
  )
  return updater
}

/** What the app closes when it quits: the controller, then the engine; once. */
private class AppResources(
  private val controller: AppController,
  private val localSpeed: LocalSpeedMode,
  private val launch: LaunchContext,
) {
  private var closed = false

  fun close() {
    if (closed) return
    closed = true
    controller.close()
    localSpeed.close()
    launch.extensionServer.close()
    controller.instanceManager.close()
    launch.singleInstance.close()
  }
}

/**
 * Quits on the main thread: waits for the pending operations behind Undo, such as removals, to
 * commit, closes the engine, waits for [history] to save the Discover sessions the controller
 * stopped, then exits, through [response] when macOS asked to quit.
 */
private suspend fun quit(
  controller: AppController,
  resources: AppResources,
  history: DiscoverHistoryStore,
  response: QuitResponse?,
  exit: () -> Unit,
) {
  val committed = withTimeoutOrNull(QUIT_COMMIT_TIMEOUT) {
    controller.state.pendingOps.flush().join()
  }
  if (committed == null) log.w { "Quitting before every pending operation committed" }
  resources.close()
  val saved = withTimeoutOrNull(QUIT_COMMIT_TIMEOUT) { history.close() }
  if (saved == null) log.w { "Quitting before the Discover history was saved" }
  if (response != null) response.performQuit() else exit()
}

/**
 * Downloads on this computer that are downloading or waiting in the queue, which quitting
 * pauses.
 */
private fun activeDownloads(manager: InstanceManager): Int =
  manager.embedded?.tasks?.value.orEmpty().count {
    val state = it.state.value
    state is DownloadState.Downloading || state.waitsInQueue
  }

// Windows users expect the close button to quit, so the first hide says where Ketch went.
private suspend fun announceBackground(trayState: TrayState) {
  if (DesktopOs.current != DesktopOs.WINDOWS || !isTraySupported) return
  val notice = Notification(APP_NAME, Res.string.notify_still_running.text().load())
  trayState.sendNotification(notice)
}

/** Opens Settings from the macOS Settings and About items; returns what removes the handlers. */
private fun installSettingsHandlers(onSettings: () -> Unit, onAbout: () -> Unit): () -> Unit {
  if (!Desktop.isDesktopSupported()) return {}
  val desktop = Desktop.getDesktop()
  val preferencesSupported = desktop.isSupported(Desktop.Action.APP_PREFERENCES)
  val aboutSupported = desktop.isSupported(Desktop.Action.APP_ABOUT)
  if (preferencesSupported) {
    desktop.setPreferencesHandler { EventQueue.invokeLater(onSettings) }
  }
  if (aboutSupported) {
    desktop.setAboutHandler { EventQueue.invokeLater(onAbout) }
  }
  return {
    if (preferencesSupported) desktop.setPreferencesHandler(null)
    if (aboutSupported) desktop.setAboutHandler(null)
  }
}

// The window's own shortcuts: Settings everywhere, and on Windows and Linux, which have no menu
// bar to own them, closing the window and quitting.
private val windowShortcuts = ShortcutMatcher(commands = windowCommands(DesktopOs.current))

private fun windowCommands(os: DesktopOs): List<KetchCommand> = if (os == DesktopOs.MAC) {
  listOf(KetchCommands.Settings)
} else {
  listOf(KetchCommands.Settings, KetchCommands.CloseWindow, KetchCommands.Quit)
}

/**
 * Commands whose keys the window or, on macOS, the menu bar runs before the app's content sees
 * them, so the shell's shortcuts leave them alone and each runs once. [slowLane] says whether
 * the menu bar lists Slow lane.
 */
internal fun hostShortcuts(os: DesktopOs, slowLane: Boolean): Set<KetchCommand> {
  val window = windowCommands(os).toSet()
  if (os != DesktopOs.MAC) return window
  val menus = menuBar(
    MenuBarContext(
      counts = PulseCounts(),
      failures = 0,
      filter = StatusFilter.All,
      devices = emptyList(),
      activeDevice = null,
      selection = emptyList(),
      undoTitle = null,
      slowLane = if (slowLane) false else null,
    ),
  )
  val owned = menus.flatMap { it.entries }
    .filterIsInstance<MenuEntry.Item>()
    .filter { it.shortcut != null }
    .mapNotNull { (it.action as? MenuAction.Run)?.command }
  return window + owned
}

/**
 * An error the window could not handle becomes an error message instead of closing it. A failure
 * while drawing comes back with every frame, and the message draws one more, so failures within
 * [WINDOW_ERROR_QUIET] of the last one reported are left out.
 */
@OptIn(ExperimentalComposeUiApi::class)
private fun windowExceptionHandlers(controller: AppController) =
  WindowExceptionHandlerFactory { _ ->
    var reportedAt: TimeSource.Monotonic.ValueTimeMark? = null
    WindowExceptionHandler { e ->
      val last = reportedAt
      if (last != null && last.elapsedNow() < WINDOW_ERROR_QUIET) return@WindowExceptionHandler
      reportedAt = TimeSource.Monotonic.markNow()
      log.e(e) { "Uncaught failure in the window: ${e.describeCauses()}" }
      controller.messages.post(
        level = MessageLevel.Error,
        title = Res.string.message_window_error.text(),
        detail = e.message?.let(::verbatim),
        cause = e,
      )
    }
  }

/**
 * Reports what happens on the devices the app watches: as toasts while the window is [inFront],
 * otherwise as notifications from the tray, as the notification settings allow. Downloads added
 * while the window is not in front, such as one the browser extension sent, are announced too,
 * and finished downloads get [successFeedback].
 *
 * @return the events to show as toasts.
 */
private fun reportDesktopActivity(
  controller: AppController,
  notifier: SystemNotifier,
  successFeedback: SuccessFeedback,
  inFront: () -> Boolean,
): Flow<ActivityEvent> {
  val manager = controller.instanceManager
  val monitor = ActivityMonitor(ActivityRouting.devices(manager), controller.scope)
  val toasts = Channel<ActivityEvent>(Channel.UNLIMITED)
  val added = AddedNotices(controller.scope) { batch ->
    val deviceName = ActivityRouting.deviceNameOf(batch.first(), manager)
    notifier.notify(batch.first(), addedCopy(batch, deviceName))
  }
  controller.scope.launch {
    monitor.events.collect { event ->
      val front = inFront()
      val settings = controller.appSettings.config.notifications
      val delivery = ActivityRouting.deliveryOf(event, settings, front)
      if (delivery.toast) toasts.send(event)
      if (delivery.notify) {
        ActivityRouting.copyOf(event, ActivityRouting.deviceNameOf(event, manager))
          ?.let { notifier.notify(event, it) }
      }
      successFeedback.reported(event, delivery, settings, notificationsAlert = false)
      if (event is ActivityEvent.Added && announcesAdded(event, settings, front)) added.add(event)
    }
  }
  return toasts.receiveAsFlow()
}

/**
 * Posts a notification from the tray for each pairing request that arrives while the main window
 * is not [inFront], where the app asks about it, so the owner comes to answer.
 */
private fun notifyPairingRequests(
  controller: AppController,
  notifier: TrayNotifier,
  inFront: () -> Boolean,
) {
  controller.scope.launch {
    controller.instanceManager.pairingRequests.watch(
      onArrived = { ask -> if (!inFront()) notifier.post(pairingNotificationCopy(ask)) },
      onLeft = {},
    )
  }
}

/**
 * Whether [event] gets a notification of its own: a download added while the window is not
 * [inFront], on a device whose messages the user has not muted, would otherwise go unseen.
 */
internal fun announcesAdded(
  event: ActivityEvent.Added,
  settings: NotificationSettings,
  inFront: Boolean,
): Boolean = !inFront && ActivityRouting.deliveryOf(event, settings, inFront = true).toast

/**
 * Announces downloads added while the window is not in front, one notification for those added
 * within [window] of the first, such as links the browser extension sends together. Meant to be
 * used from one thread, the one [scope] runs on.
 *
 * @param notify posts the notification for the downloads added together, oldest first.
 */
internal class AddedNotices(
  private val scope: CoroutineScope,
  private val window: Duration = ADDED_COALESCE_WINDOW,
  private val notify: suspend (List<ActivityEvent.Added>) -> Unit,
) {
  private val held = ArrayList<ActivityEvent.Added>()
  private var announcing: Job? = null

  /** Adds [event] to the next notification. */
  fun add(event: ActivityEvent.Added) {
    held += event
    if (announcing != null) return
    announcing = scope.launch {
      delay(window)
      val batch = held.toList()
      held.clear()
      announcing = null
      notify(batch)
    }
  }
}

/**
 * The notification for downloads [added] while the window was not in front, in the language of
 * the app's window.
 *
 * @param deviceName device to name in the title; `null` for the device the app shows.
 */
internal suspend fun addedCopy(
  added: List<ActivityEvent.Added>,
  deviceName: String?,
): NotificationCopy {
  val title = Res.plurals.notify_added.text(added.size)
  val names = added.map { displayName(it.request) }
  val shown = names.take(ADDED_NAMES_SHOWN)
  val more = names.size - shown.size
  val listed = when (shown.size) {
    0, 1 -> verbatim(shown.firstOrNull().orEmpty())
    2 -> Res.string.notify_names_two.text(shown[0], shown[1])
    else -> Res.string.notify_names_three.text(shown[0], shown[1], shown[2])
  }
  val body = if (more > 0) Res.plurals.notify_names_more.text(more, listed, more) else listed
  val heading = deviceName?.let { Res.string.notify_on_device.text(it, title) } ?: title
  return NotificationCopy(title = heading.load(), body = body.load())
}

/**
 * Speed mode of this computer, which the app, the tray and the menu bar switch. It is made before
 * the controller that shows it, so it runs in a scope of its own on the main thread, and saves
 * the speed settings and the observed peak as they change.
 */
private class LocalSpeedMode(private val configStore: ConfigStore) {
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
  private val speed = MutableStateFlow(0L)

  /** Creates the speed mode of [manager]'s embedded engine; `null` without one. */
  fun attach(manager: InstanceManager): SpeedModeController? {
    val embedded = manager.embedded ?: return null
    val config = configStore.load()
    val speedMode = SpeedModeController(
      config = { embedded.status().config },
      apply = { embedded.updateConfig(it) },
      scope = scope,
      settings = config.speed,
      observedPeak = ObservedPeak(config.ui.observedPeak, config.ui.observedPeakAt),
      speed = speed,
    )
    save(speedMode)
    return speedMode
  }

  /** Records this computer's speed from [pulse], which raises the observed peak. */
  fun follow(pulse: PulseModel) {
    scope.launch {
      pulse.devices.collect { devices ->
        speed.value = devices.firstOrNull { it.deviceId == LOCAL_DEVICE_ID }?.speed ?: 0
      }
    }
  }

  fun close() {
    scope.cancel()
  }

  @OptIn(FlowPreview::class)
  private fun save(speedMode: SpeedModeController) {
    scope.launch {
      speedMode.settings.drop(1).collect { speed ->
        saveConfig(configStore) { it.copy(speed = speed) }
      }
    }
    scope.launch {
      // The peak climbs in steps while a download speeds up.
      speedMode.observedPeak.drop(1).debounce(PEAK_SAVE_DELAY).collect { peak ->
        saveConfig(configStore) {
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
}

// Runs on the main thread, like the app's settings, so loads and saves cannot interleave.
private fun saveConfig(store: ConfigStore, transform: (KetchConfig) -> KetchConfig) {
  try {
    store.save(transform(store.load()))
  } catch (e: Exception) {
    log.w { "Couldn't save config.toml: ${e.describeCauses()}" }
  }
}

/**
 * Level for the console and the log file, from `KETCH_LOG_LEVEL` (`verbose`, `debug`, `info`,
 * `warn` or `error`). Defaults to debug, which includes segment start and completion; verbose
 * adds speed limiter waits, FTP commands and replies, and per-peer torrent detail.
 */
private fun logLevel(): LogLevel {
  val name = System.getenv("KETCH_LOG_LEVEL")?.trim() ?: return LogLevel.DEBUG
  return LogLevel.entries.firstOrNull { it.name.equals(name, ignoreCase = true) }
    ?: LogLevel.DEBUG
}

private const val APP_NAME = "Ketch"
private const val LOGS_DIR = "logs"
private const val UPDATES_DIR = "updates"
private const val UPDATE_LOG = "update.log"
private const val DISCOVER_HISTORY_FILE = "discover-history.json"
private const val MAIN_WINDOW_STATE = "main-window"
private const val ADDED_NAMES_SHOWN = 3
private val ADDED_COALESCE_WINDOW = 1.seconds
private val PEAK_SAVE_DELAY = 10.seconds
private val QUIT_COMMIT_TIMEOUT = 5.seconds
private val WINDOW_ERROR_QUIET = 5.seconds
