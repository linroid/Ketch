package com.linroid.ketch.app.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
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
import com.linroid.ketch.api.log.KetchLogger
import com.linroid.ketch.api.log.LogLevel
import com.linroid.ketch.api.log.Logger
import com.linroid.ketch.api.log.describeCauses
import com.linroid.ketch.app.App
import com.linroid.ketch.app.feedback.ActivityEvent
import com.linroid.ketch.app.feedback.ActivityMonitor
import com.linroid.ketch.app.feedback.ActivityRouting
import com.linroid.ketch.app.feedback.MessageLevel
import com.linroid.ketch.app.feedback.NotificationCopy
import com.linroid.ketch.app.feedback.SystemNotifier
import com.linroid.ketch.app.input.KetchCommands
import com.linroid.ketch.app.input.KeyboardPlatform
import com.linroid.ketch.app.input.ShortcutContext
import com.linroid.ketch.app.input.ShortcutMatcher
import com.linroid.ketch.app.instance.EmbeddedInstance
import com.linroid.ketch.app.instance.InstanceFactory
import com.linroid.ketch.app.instance.InstanceManager
import com.linroid.ketch.app.instance.LocalServerHandle
import com.linroid.ketch.app.log.FileLogger
import com.linroid.ketch.app.platform.FileActions
import com.linroid.ketch.app.platform.LocalDesktopHooks
import com.linroid.ketch.app.platform.localDeviceNoun
import com.linroid.ketch.app.platform.rememberFileActions
import com.linroid.ketch.app.platform.rememberSystemClipboard
import com.linroid.ketch.app.state.AppController
import com.linroid.ketch.app.state.EmbeddedAiDiscoveryProviderFactory
import com.linroid.ketch.app.state.IncomingDownloads
import com.linroid.ketch.app.state.LOCAL_DEVICE_ID
import com.linroid.ketch.app.state.LinkSource
import com.linroid.ketch.app.state.ObservedPeak
import com.linroid.ketch.app.state.PulseModel
import com.linroid.ketch.app.state.SpeedModeController
import com.linroid.ketch.app.state.isSlowLane
import com.linroid.ketch.app.util.displayName
import com.linroid.ketch.config.ConfigStore
import com.linroid.ketch.config.FileConfigStore
import com.linroid.ketch.config.KetchConfig
import com.linroid.ketch.config.NotificationSettings
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
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

private val log = KetchLogger("DesktopApp")

fun main(args: Array<String>) {
  val configDir = File(defaultConfigDir())
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
    incoming.offerLinks(opened.links, source)
  }

  val launched = fileArguments(args.toList())
  val background = BACKGROUND_FLAG in args
  val extensionServer = BrowserExtensionServer()
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
  // application() ends with exitProcess, which still runs shutdown hooks.
  val closeLog = Thread { runBlocking { withTimeoutOrNull(2.seconds) { fileLogger.close() } } }
  Runtime.getRuntime().addShutdownHook(closeLog)
  val logger = Logger.combine(Logger.console(logLevel), fileLogger)
  // Ketch installs it too, but the host name below is resolved before Ketch exists.
  KetchLogger.setLogger(logger)
  installOpenFileHandler { files ->
    open(OpenedArguments(files = files), LinkSource.Arguments)
    windowRequests.trySend(Unit)
  }
  installOpenUriHandler { uri ->
    open(fileArguments(listOf(uri)), LinkSource.OpenUrl)
    windowRequests.trySend(Unit)
  }
  open(launched, LinkSource.Arguments)
  registerNativeHost(configDir, logger)
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
    logger = logger,
    fileLogger = fileLogger,
    openFiles = { files -> open(OpenedArguments(files = files), LinkSource.Arguments) },
  )
  application { KetchApp(launch) }
}

/**
 * What [main] hands the app once this process is the running instance.
 *
 * @property windowRequests emits when the window should come forward.
 * @property background whether the app starts hidden ([BACKGROUND_FLAG]).
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
  val logger: Logger,
  val fileLogger: FileLogger,
  val openFiles: (List<File>) -> Unit,
)

/** Lets the browser extension find this app; see [NativeHostRegistration]. */
private fun registerNativeHost(configDir: File, logger: Logger) {
  thread(isDaemon = true, name = "ketch-native-host-registration") {
    try {
      val home = File(System.getProperty("user.home"))
      val registered = NativeHostRegistration(configDir, home).register()
      logger.d("[NativeHost] Registered for the browser extension: $registered")
    } catch (e: Exception) {
      logger.w("[NativeHost] Couldn't register for the browser extension", e)
    }
  }
}

/**
 * The app: the devices, the controller and the activity monitor live here, outside the window,
 * so closing the window keeps downloads running and the tray, the menu bar and the Dock working.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun ApplicationScope.KetchApp(launch: LaunchContext) {
  val configStore = remember {
    FileConfigStore(File(launch.configDir, "config.toml").path)
  }
  val localSpeed = remember { LocalSpeedMode(configStore) }
  val controller = remember {
    val manager = createInstanceManager(launch, configStore)
    AppController(
      instanceManager = manager,
      aiProviderFactory = EmbeddedAiDiscoveryProviderFactory(),
      incoming = launch.incoming,
      speedMode = localSpeed.attach(manager),
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
      onFirstHide = { announceBackground(trayState) },
      quit = { response ->
        scope.launch { quit(controller, resources, response) { exitApplication() } }
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
  val actions = remember {
    DesktopActions(
      showWindow = behavior::showWindow,
      closeWindow = behavior::closeWindow,
      quit = { behavior.requestQuit() },
      openFiles = launch.openFiles,
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
  DisposableEffect(behavior) {
    val remove = installAppHandlers(behavior)
    onDispose { remove() }
  }
  DisposableEffect(commands) {
    val remove = installPreferencesHandler { commands.run(KetchCommands.Settings) }
    onDispose { remove() }
  }

  // The window composes its content once it is first shown, so what must work while it is
  // hidden lives out here.
  var mainWindow by remember { mutableStateOf<ComposeWindow?>(null) }
  var windowFocused by remember { mutableStateOf(false) }
  // A hidden window stops composing, so the focus it last reported only counts while it shows.
  fun inFront() = behavior.windowVisible && windowFocused && !windowState.isMinimized
  val status = rememberDesktopStatus(controller, controller.pulse.state, inFront())
  val activityEvents = remember {
    reportDesktopActivity(controller, notifier, ::inFront)
  }
  KetchTray(controller, status, actions, speedMode, trayState)
  TaskbarFeedback(status, hooks.dockBadge, mainWindow)
  if (DesktopOs.current == DesktopOs.MAC) {
    DockMenu(commands, status.pulse.counts)
    DefaultMenuBar(defaultMenus(controller, status, speedMode, files), commands::perform)
  }
  CloseDialogs(behavior, controller.appSettings)

  val exceptionHandlers = remember { windowExceptionHandlers(controller) }
  CompositionLocalProvider(LocalWindowExceptionHandlerFactory provides exceptionHandlers) {
    Window(
      onCloseRequest = behavior::closeWindow,
      state = windowState,
      visible = behavior.windowVisible,
      title = "Ketch",
      icon = painterResource("icon.svg"),
      onPreviewKeyEvent = { event ->
        val command = windowShortcuts.match(event, ShortcutContext())
        if (command != null) commands.run(command)
        command != null
      },
    ) {
      LaunchedEffect(Unit) {
        window.minimumSize = Dimension(MIN_WINDOW_WIDTH, MIN_WINDOW_HEIGHT)
        mainWindow = window
      }
      LaunchedEffect(behavior.windowVisible) {
        if (behavior.windowVisible) bringToFront(window)
      }
      LaunchedEffect(behavior) {
        behavior.frontRequests.collect { bringToFront(window) }
      }
      val focused = LocalWindowInfo.current.isWindowFocused
      SideEffect { windowFocused = focused }
      KetchMenuBar(controller, status, actions, speedMode)
      CompositionLocalProvider(LocalDesktopHooks provides hooks) {
        App(controller, activityEvents = activityEvents, fileLogger = launch.fileLogger)
      }
    }
  }
}

private fun createInstanceManager(
  launch: LaunchContext,
  configStore: ConfigStore,
): InstanceManager {
  val configDir = launch.configDir
  val config = configStore.load()
  val taskStore = createSqliteTaskStore(DriverFactory(File(configDir, "ketch.db").path))
  val instanceName = config.name?.ifEmpty { null } ?: launch.hostName
  val torrentSource = TorrentDownloadSource(
    TorrentConfig(
      stateDirectory = File(configDir, "torrent-state").path,
      additionalTrackers = config.torrent.trackers,
    ),
  )
  return InstanceManager(
    factory = InstanceFactory(
      deviceName = instanceName,
      embeddedFactory = {
        Ketch(
          httpEngine = KtorHttpEngine.withNetworkInterfaces(),
          taskStore = taskStore,
          config = config.download,
          name = instanceName,
          logger = launch.logger,
          additionalSources = listOf(FtpDownloadSource(), torrentSource),
        ).also(launch.extensionServer::attach)
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
          // With a token, pages on any site, such as the web app, may call the API, as
          // they still need the token. Without one, KetchServer refuses them.
          corsAllowedHosts = serverConfig.corsAllowedHosts.ifEmpty {
            if (serverConfig.apiToken == null) emptyList() else listOf("*")
          },
          allowedHosts = serverConfig.allowedHosts,
          mdnsEnabled = serverConfig.mdnsEnabled,
        )
        server.start(wait = false)
        object : LocalServerHandle {
          override fun stop() {
            server.stop()
          }
        }
      },
      applyTorrentSettings = { torrentSource.setAdditionalTrackers(it.trackers) },
    ),
    initialRemotes = config.remotes,
    configStore = configStore,
  )
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
 * commit, closes the engine, then exits, through [response] when macOS asked to quit.
 */
private suspend fun quit(
  controller: AppController,
  resources: AppResources,
  response: QuitResponse?,
  exit: () -> Unit,
) {
  val committed = withTimeoutOrNull(QUIT_COMMIT_TIMEOUT) {
    controller.state.pendingOps.flush().join()
  }
  if (committed == null) log.w { "Quitting before every pending operation committed" }
  resources.close()
  if (response != null) response.performQuit() else exit()
}

/** Downloads on this computer that are downloading or queued, which quitting pauses. */
private fun activeDownloads(manager: InstanceManager): Int =
  manager.embedded?.tasks?.value.orEmpty().count {
    val state = it.state.value
    state is DownloadState.Downloading || state is DownloadState.Queued
  }

// Windows users expect the close button to quit, so the first hide says where Ketch went.
private fun announceBackground(trayState: TrayState) {
  if (DesktopOs.current != DesktopOs.WINDOWS || !isTraySupported) return
  val notice = Notification("Ketch", "Ketch is still running in the notification area")
  trayState.sendNotification(notice)
}

/** Opens Settings from the macOS app menu's "Settings…" item; returns what removes it. */
private fun installPreferencesHandler(onOpen: () -> Unit): () -> Unit {
  if (!Desktop.isDesktopSupported()) return {}
  val desktop = Desktop.getDesktop()
  if (!desktop.isSupported(Desktop.Action.APP_PREFERENCES)) return {}
  desktop.setPreferencesHandler { EventQueue.invokeLater(onOpen) }
  return { desktop.setPreferencesHandler(null) }
}

// The window's own shortcuts: Settings everywhere, and on Windows and Linux, which have no menu
// bar to own them, closing the window and quitting.
private val windowShortcuts = ShortcutMatcher(
  commands = if (DesktopOs.current == DesktopOs.MAC) {
    listOf(KetchCommands.Settings)
  } else {
    listOf(KetchCommands.Settings, KetchCommands.CloseWindow, KetchCommands.Quit)
  },
)

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
        title = "Something went wrong",
        detail = e.message,
        cause = e,
      )
    }
  }

/** The menus of the macOS menu bar while no window is open, as [KetchMenuBar] builds them. */
@Composable
private fun defaultMenus(
  controller: AppController,
  status: DesktopStatus,
  speedMode: SpeedModeController?,
  files: FileActions?,
): List<MenuBarMenu> {
  val state = controller.state
  val instances by state.instances.collectAsState()
  val active by state.activeInstance.collectAsState()
  val ops by state.pendingOps.ops.collectAsState()
  val mode = speedMode?.mode?.collectAsState()?.value
  return menuBar(
    MenuBarContext(
      counts = status.pulse.counts,
      failures = status.pulse.failures,
      filter = state.statusFilter,
      devices = instances.map { if (it is EmbeddedInstance) localDeviceNoun() else it.label },
      activeDevice = instances.indexOf(active).takeIf { it >= 0 },
      selection = emptyList(),
      undoLabel = ops.lastOrNull()?.label,
      inspectorOpen = state.inspectorOpen,
      slowLane = mode?.isSlowLane,
      revealLabel = files?.revealLabel,
      platform = KeyboardPlatform.Mac,
    ),
  )
}

/**
 * Reports what happens on the devices the app watches: as toasts while the window is [inFront],
 * otherwise as notifications from the tray, as the notification settings allow. Downloads added
 * while the window is not in front, such as one the browser extension sent, are announced too.
 *
 * @return the events to show as toasts.
 */
private fun reportDesktopActivity(
  controller: AppController,
  notifier: SystemNotifier,
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
      if (event is ActivityEvent.Added && announcesAdded(event, settings, front)) added.add(event)
    }
  }
  return toasts.receiveAsFlow()
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
  private val notify: (List<ActivityEvent.Added>) -> Unit,
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
 * The notification for downloads [added] while the window was not in front.
 *
 * @param deviceName device to name in the title; `null` for the device the app shows.
 */
internal fun addedCopy(added: List<ActivityEvent.Added>, deviceName: String?): NotificationCopy {
  val title = if (added.size == 1) "Download added" else "${added.size} downloads added"
  val names = added.map { displayName(it.request) }
  val shown = names.take(ADDED_NAMES_SHOWN)
  val more = names.size - shown.size
  return NotificationCopy(
    title = if (deviceName == null) title else "On $deviceName: $title",
    body = shown.joinToString(", ") + if (more > 0) " and $more more" else "",
  )
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

private const val LOGS_DIR = "logs"
private const val ADDED_NAMES_SHOWN = 3
private val ADDED_COALESCE_WINDOW = 1.seconds
private val PEAK_SAVE_DELAY = 10.seconds
private val QUIT_COMMIT_TIMEOUT = 5.seconds
private val WINDOW_ERROR_QUIET = 5.seconds
