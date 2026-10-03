package com.linroid.ketch.app.android

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.res.Configuration
import android.os.Bundle
import android.os.IBinder
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.platform.LocalConfiguration
import androidx.core.net.toUri
import androidx.lifecycle.AndroidViewModel
import com.linroid.ketch.app.feedback.AppMessage
import com.linroid.ketch.app.feedback.MessageLevel
import com.linroid.ketch.app.feedback.MessagePlacement
import com.linroid.ketch.app.i18n.appLanguageContext
import com.linroid.ketch.app.i18n.load
import com.linroid.ketch.app.platform.SystemAppearance
import com.linroid.ketch.app.platform.rememberReduceMotion
import com.linroid.ketch.app.state.AppController
import com.linroid.ketch.app.state.IncomingDownloads
import com.linroid.ketch.app.state.LinkSource
import com.linroid.ketch.app.state.LocalAppState
import com.linroid.ketch.app.theme.KetchTheme
import com.linroid.ketch.app.ui.intake.IntakeHost
import com.linroid.ketch.config.ThemeMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.dropWhile
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.seconds

/**
 * Adds links shared from another app, or text sent from a selection with "Download with Ketch",
 * without leaving that app: a translucent window that shows only the add sheet and finishes
 * once the sheet closes. The sheet's adds run on after that, and their outcome shows as a system
 * toast.
 */
class QuickAddActivity : ComponentActivity() {

  private val model: QuickAddModel by viewModels()

  override fun attachBaseContext(newBase: Context) {
    super.attachBaseContext(appLanguageContext(newBase))
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()
    // Once per share: a window recreated on rotation keeps the model and its sheet.
    if (!model.offered) {
      model.offered = true
      val incoming = model.incoming
      val offered = sharedText(intent)?.let { incoming.offerText(it, LinkSource.Share) } == true
      // A shared pairing link is confirmed in the app, which is where devices are managed.
      for (pairing in incoming.pendingPairings.value) {
        val open = Intent(Intent.ACTION_VIEW, pairing.link.toUri(), this, MainActivity::class.java)
        startActivity(open)
        incoming.complete(pairing)
      }
      if (incoming.pendingLinks.value.isEmpty()) {
        if (!offered) Toast.makeText(this, R.string.quick_add_no_link, Toast.LENGTH_SHORT).show()
        finish()
        return
      }
    }
    model.connect()
    setContent {
      val controller = model.controller ?: return@setContent
      QuickAddSheet(controller, onClosed = ::finish)
    }
  }

  /** The text of a share, or the selection sent with "Download with Ketch". */
  private fun sharedText(intent: Intent?): String? = when (intent?.action) {
    Intent.ACTION_SEND -> intent.getCharSequenceExtra(Intent.EXTRA_TEXT)
    Intent.ACTION_PROCESS_TEXT -> intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)
    else -> null
  }?.toString()?.takeIf { it.isNotBlank() }
}

/**
 * The add sheet alone, in the app's theme, for the links [controller] was handed. Calls
 * [onClosed] once the sheet has opened and closed again.
 */
@Composable
private fun QuickAddSheet(controller: AppController, onClosed: () -> Unit) {
  val state = controller.state
  val settings = controller.appSettings
  val currentOnClosed by rememberUpdatedState(onClosed)
  LaunchedEffect(state) {
    snapshotFlow { state.showAddDialog }.dropWhile { !it }.first { !it }
    currentOnClosed()
  }
  val nightMode = LocalConfiguration.current.uiMode and Configuration.UI_MODE_NIGHT_MASK
  val darkTheme = when (settings.themeMode) {
    ThemeMode.System -> nightMode == Configuration.UI_MODE_NIGHT_YES
    ThemeMode.Light -> false
    ThemeMode.Dark -> true
  }
  SystemAppearance(darkTheme.takeIf { settings.themeMode != ThemeMode.System })
  KetchTheme(
    darkTheme = darkTheme,
    accent = settings.accent,
    density = settings.ui.density,
    reduceMotion = settings.ui.reduceMotion || rememberReduceMotion(),
  ) {
    CompositionLocalProvider(LocalAppState provides state) {
      // The window and its sheet go together, so nothing is left to finish in the background.
      IntakeHost(state, canFinishInBackground = false)
    }
  }
}

/**
 * What a [QuickAddActivity] keeps while it is recreated, such as on rotation: the shared links,
 * the service binding and the controller that adds them.
 *
 * The controller and the binding outlive the window by [ADD_GRACE]. An add sent as the sheet
 * closes can finish, and the service, which is destroyed once nothing binds it unless it runs
 * downloads in the foreground, stays until it has noticed the new downloads. Meanwhile the
 * controller's toasts show as system toasts, since no window shows them.
 */
internal class QuickAddModel(application: Application) : AndroidViewModel(application) {
  /** The links shared with the window, for its controller's add sheet. */
  val incoming = IncomingDownloads()

  /** Whether the window's intent has been offered to [incoming]. */
  var offered = false

  /** The controller of the service's devices, once [connect] has bound the service. */
  var controller: AppController? by mutableStateOf(null)
    private set

  private var bound = false

  private val connection = object : ServiceConnection {
    override fun onServiceConnected(name: ComponentName, binder: IBinder) {
      if (controller != null) return
      val service = (binder as KetchService.LocalBinder).service
      controller = AppController(
        instanceManager = service.instanceManager,
        incoming = incoming,
        speedMode = service.speedMode,
      ).also(::relayToasts)
    }

    override fun onServiceDisconnected(name: ComponentName) = Unit
  }

  /** Binds the service once; a recreated window reuses the binding. */
  fun connect() {
    if (bound) return
    val app = getApplication<Application>()
    val intent = Intent(app, KetchService::class.java)
    bound = app.bindService(intent, connection, Context.BIND_AUTO_CREATE)
  }

  override fun onCleared() {
    if (!bound) return
    bound = false
    val app = getApplication<Application>()
    val retiring = controller
    if (retiring == null) {
      app.unbindService(connection)
      return
    }
    lingering.launch {
      delay(ADD_GRACE)
      // The service closes its devices once unbound, so the controller goes first.
      retiring.close()
      app.unbindService(connection)
    }
  }

  private fun relayToasts(controller: AppController) {
    controller.scope.launch {
      controller.messages.active.collect { active ->
        for (message in active) {
          if (message.placement != MessagePlacement.Toast) continue
          controller.messages.dismiss(message.id)
          Toast.makeText(getApplication(), message.toastText(), message.toastLength()).show()
        }
      }
    }
  }

  private suspend fun AppMessage.toastText(): String = if (level == MessageLevel.Error) {
    listOfNotNull(title, detail).map { it.load() }.joinToString("\n")
  } else {
    title.load()
  }

  private fun AppMessage.toastLength(): Int =
    if (level == MessageLevel.Error) Toast.LENGTH_LONG else Toast.LENGTH_SHORT

  private companion object {
    /** How long a closed window's controller and binding last, for the adds it sent. */
    val ADD_GRACE = 30.seconds

    /** Runs the release of controllers and bindings whose window is gone. */
    val lingering = CoroutineScope(SupervisorJob() + Dispatchers.Main)
  }
}
