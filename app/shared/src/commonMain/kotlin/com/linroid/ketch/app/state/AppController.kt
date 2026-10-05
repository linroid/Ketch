package com.linroid.ketch.app.state

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import com.linroid.ketch.api.log.KetchLogger
import com.linroid.ketch.api.log.describeCauses
import com.linroid.ketch.app.feedback.MessageCenter
import com.linroid.ketch.app.feedback.UnreadableFiles
import com.linroid.ketch.app.instance.InstanceManager
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlin.coroutines.CoroutineContext
import kotlin.time.Clock
import kotlin.time.TimeSource

/**
 * Owns the app's state outside of any composition, so the window, the tray, the menu bar and
 * the Settings window can share it, and commands survive the screen that started them.
 *
 * The host that creates it closes it with [close]; [rememberAppController] does that for apps
 * that keep it in a composition.
 *
 * @param instanceManager the devices; the host owns it and closes it after this controller.
 * @param aiProviderFactory builds the AI discovery engine; `null` where it cannot run.
 * @param incoming downloads opened from outside the app.
 * @param context dispatcher of [scope]; the main thread by default.
 * @param speedMode speed mode of the embedded device when the host owns one, such as the
 *   service whose notification switches it, so the app and the host never fight over the speed
 *   limit; `null` when the host keeps none. Its mode also feeds the devices' presence.
 * @param clock current time of the task list and the speed history.
 * @param unreadableFiles files the host moved aside because it could not read them, such as
 *   `config.toml`, which the app reports to the user once.
 * @param discoverHistory keeps the Discover sessions between runs; the host owns it, one per
 *   process, and closes it after this controller.
 * @param listDispatcher where the task list samples task changes and builds its rows, off the
 *   main thread by default.
 * @param timeSource clock that measures the rate of each connection.
 */
class AppController(
  val instanceManager: InstanceManager,
  aiProviderFactory: AiDiscoveryProviderFactory? = null,
  incoming: IncomingDownloads = IncomingDownloads(),
  context: CoroutineContext = Dispatchers.Main,
  speedMode: SpeedModeController? = null,
  clock: Clock = Clock.System,
  unreadableFiles: UnreadableFiles = UnreadableFiles(),
  discoverHistory: DiscoverHistoryStore = InMemoryDiscoverHistoryStore(),
  listDispatcher: CoroutineDispatcher = Dispatchers.Default,
  timeSource: TimeSource.WithComparableMarks = TimeSource.Monotonic,
) {
  private val log = KetchLogger("AppController")
  private var closed = false

  /** Runs the app's commands; one failing never cancels the others. */
  val scope: CoroutineScope = CoroutineScope(
    SupervisorJob() + context + CoroutineExceptionHandler { _, e ->
      log.e(e) { "Uncaught failure in the app scope: ${e.describeCauses()}" }
    },
  )

  /** App settings saved in `config.toml`. */
  val appSettings: AppSettingsController = AppSettingsController(instanceManager.configStore)

  /** AI discovery settings and the engine built from them. */
  val aiSettings: AiSettingsController =
    AiSettingsController(instanceManager.configStore, aiProviderFactory)

  /** State and commands of the app. */
  val state: AppState = AppState(
    instanceManager = instanceManager,
    scope = scope,
    appSettings = appSettings,
    aiSettings = aiSettings,
    incoming = incoming,
    speedMode = speedMode,
    clock = clock,
    unreadableFiles = unreadableFiles,
    discoverHistory = discoverHistory,
    listDispatcher = listDispatcher,
    timeSource = timeSource,
  )

  init {
    speedMode?.let { instanceManager.setLocalSpeedMode(it.mode) }
  }

  /** Toasts, banners and the Activity history. */
  val messages: MessageCenter get() = state.messages

  /** Rows of the active device's tasks and the tab, search and order the list shows them in. */
  val taskList: TaskListModel get() = state.taskList

  /** Speed of each task once a second. */
  val speedHistory: SpeedHistoryStore get() = state.speedHistory

  /** Speed, counts, speed limit, free space and health of the active device. */
  val pulse: PulseModel get() = state.pulse

  /** Speed mode of the embedded device, as the host passed it; `null` when it keeps none. */
  val speedMode: SpeedModeController? get() = state.speedMode

  /**
   * Commits pending operations, stops the Discover searches, releases the discovery engine and
   * stops the scope. Commits already started keep running. Does not close [instanceManager] or
   * the Discover history.
   */
  fun close() {
    if (closed) return
    closed = true
    state.close()
    aiSettings.close()
    scope.cancel()
  }
}

/**
 * Creates an [AppController] that lives as long as this composition and is closed with it.
 *
 * A host whose screen is recreated, such as an Android activity on rotation, keeps its own
 * controller across that instead, so adds and commands in flight survive.
 */
@Composable
fun rememberAppController(
  instanceManager: InstanceManager,
  aiProviderFactory: AiDiscoveryProviderFactory? = null,
  incoming: IncomingDownloads? = null,
  speedMode: SpeedModeController? = null,
  unreadableFiles: UnreadableFiles? = null,
  discoverHistory: DiscoverHistoryStore? = null,
): AppController {
  val controller = remember(
    instanceManager,
    aiProviderFactory,
    incoming,
    speedMode,
    unreadableFiles,
    discoverHistory
  ) {
    AppController(
      instanceManager = instanceManager,
      aiProviderFactory = aiProviderFactory,
      incoming = incoming ?: IncomingDownloads(),
      speedMode = speedMode,
      unreadableFiles = unreadableFiles ?: UnreadableFiles(),
      discoverHistory = discoverHistory ?: InMemoryDiscoverHistoryStore(),
    )
  }
  DisposableEffect(controller) {
    onDispose { controller.close() }
  }
  return controller
}
