package com.linroid.ketch.app.state

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
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
 */
class AppController(
  val instanceManager: InstanceManager,
  aiProviderFactory: AiDiscoveryProviderFactory? = null,
  incoming: IncomingDownloads = IncomingDownloads(),
  context: CoroutineContext = Dispatchers.Main,
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
  )

  /** Toasts, banners and the Activity history. */
  val messages: MessageCenter get() = state.messages

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
 * Creates an [AppController] that lives as long as this composition and is closed with it.
 *
 * The instance manager can outlive the composition (Android keeps it in the service across
 * activity recreation), so the controller is released here rather than with the manager.
 */
@Composable
fun rememberAppController(
  instanceManager: InstanceManager,
  aiProviderFactory: AiDiscoveryProviderFactory? = null,
  incoming: IncomingDownloads? = null,
): AppController {
  val controller = remember(instanceManager, aiProviderFactory, incoming) {
    AppController(instanceManager, aiProviderFactory, incoming ?: IncomingDownloads())
  }
  DisposableEffect(controller) {
    onDispose { controller.close() }
  }
  return controller
}
