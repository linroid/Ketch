package com.linroid.ketch.app.state

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.linroid.ketch.api.log.KetchLogger
import com.linroid.ketch.api.log.describeCauses
import com.linroid.ketch.app.feedback.MessageCenter
import com.linroid.ketch.app.instance.InstanceManager
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlin.coroutines.CoroutineContext
import kotlin.time.Clock

/**
 * Owns the app's state outside of any composition, so the window, the tray, the menu bar and
 * the Settings window can share it, and commands survive the screen that started them.
 *
 * The host that creates it closes it with [close]; [rememberAppController] does that for apps
 * that keep it in a composition, once the screen that holds it is gone for good.
 *
 * @param instanceManager the devices; the host owns it and closes it after this controller.
 * @param aiProviderFactory builds the AI discovery engine; `null` where it cannot run.
 * @param incoming downloads opened from outside the app.
 * @param context dispatcher of [scope]; the main thread by default.
 * @param speedMode speed mode of the embedded device when the host owns one, such as the
 *   service whose notification switches it, so the app and the host never fight over the speed
 *   limit; `null` when the host keeps none. Its mode also feeds the devices' presence.
 * @param clock current time of the task list and the speed history.
 */
class AppController(
  val instanceManager: InstanceManager,
  aiProviderFactory: AiDiscoveryProviderFactory? = null,
  incoming: IncomingDownloads = IncomingDownloads(),
  context: CoroutineContext = Dispatchers.Main,
  speedMode: SpeedModeController? = null,
  clock: Clock = Clock.System,
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
   * Commits pending operations, releases the discovery engine and stops the scope. Commits
   * already started keep running. Does not close [instanceManager].
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
 * Creates an [AppController] that lives as long as the screen showing this composition, such as
 * an Android activity across rotations, so adds and commands in flight survive the screen being
 * recreated. It is closed once the screen is gone for good, or when it is asked for with another
 * [instanceManager], [aiProviderFactory], [incoming] or [speedMode].
 *
 * The instance manager and [speedMode] can outlive the screen (Android keeps them in the
 * service), so the controller is released here rather than with them. Without a
 * `ViewModelStoreOwner` the controller lives as long as this composition.
 */
@Composable
fun rememberAppController(
  instanceManager: InstanceManager,
  aiProviderFactory: AiDiscoveryProviderFactory? = null,
  incoming: IncomingDownloads? = null,
  speedMode: SpeedModeController? = null,
): AppController {
  val key = AppControllerKey(instanceManager, aiProviderFactory, incoming, speedMode)
  val owner = LocalViewModelStoreOwner.current
  if (owner != null) {
    val holder = viewModel(viewModelStoreOwner = owner) { AppControllerHolder() }
    return remember(holder, key) { holder.controllerFor(key) }
  }
  val controller = remember(key) { key.create() }
  DisposableEffect(controller) {
    onDispose { controller.close() }
  }
  return controller
}

/** What an [AppController] made by [rememberAppController] is built from. */
private data class AppControllerKey(
  val instanceManager: InstanceManager,
  val aiProviderFactory: AiDiscoveryProviderFactory?,
  val incoming: IncomingDownloads?,
  val speedMode: SpeedModeController?,
) {
  fun create(): AppController = AppController(
    instanceManager = instanceManager,
    aiProviderFactory = aiProviderFactory,
    incoming = incoming ?: IncomingDownloads(),
    speedMode = speedMode,
  )
}

/** Keeps the [AppController] of a screen while the screen is recreated, and closes it after. */
private class AppControllerHolder : ViewModel() {
  private var key: AppControllerKey? = null
  private var controller: AppController? = null

  /** The controller built from [key]; one built from another key is closed first. */
  fun controllerFor(key: AppControllerKey): AppController {
    controller?.takeIf { this.key == key }?.let { return it }
    controller?.close()
    return key.create().also {
      this.key = key
      controller = it
    }
  }

  override fun onCleared() {
    controller?.close()
    controller = null
  }
}
