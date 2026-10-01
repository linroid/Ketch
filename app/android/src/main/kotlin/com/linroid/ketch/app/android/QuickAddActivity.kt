package com.linroid.ketch.app.android

import android.app.Application
import android.content.ComponentName
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.platform.LocalConfiguration
import androidx.core.net.toUri
import androidx.lifecycle.AndroidViewModel
import com.linroid.ketch.app.feedback.AppMessage
import com.linroid.ketch.app.feedback.MessageLevel
import com.linroid.ketch.app.feedback.MessagePlacement
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
  private var service: KetchService? by mutableStateOf(null)
  private var bound = false

  private val connection = object : ServiceConnection {
    override fun onServiceConnected(name: ComponentName, binder: IBinder) {
      service = (binder as KetchService.LocalBinder).service
    }

    override fun onServiceDisconnected(name: ComponentName) {
      service = null
    }
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
        if (!offered) Toast.makeText(this, "No link to download", Toast.LENGTH_SHORT).show()
        finish()
        return
      }
    }
    bound = bindService(Intent(this, KetchService::class.java), connection, BIND_AUTO_CREATE)
    setContent {
      val svc = service ?: return@setContent
      val controller = remember(svc) { model.controllerFor(svc) }
      QuickAddSheet(controller, onClosed = ::finish)
    }
  }

  override fun onDestroy() {
    super.onDestroy()
    if (bound) unbindService(connection)
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
  KetchTheme(
    darkTheme = darkTheme,
    accent = settings.accent,
    density = settings.ui.density,
    reduceMotion = settings.ui.reduceMotion || rememberReduceMotion(),
  ) {
    CompositionLocalProvider(LocalAppState provides state) {
      IntakeHost(state)
    }
  }
}

/**
 * What a [QuickAddActivity] keeps while it is recreated, such as on rotation: the shared links
 * and the controller that adds them.
 *
 * The controller outlives the window by [ADD_GRACE], so an add the sheet sent as it closed can
 * finish. Meanwhile its toasts show as system toasts, since no window shows them.
 */
internal class QuickAddModel(application: Application) : AndroidViewModel(application) {
  /** The links shared with the window, for its controller's add sheet. */
  val incoming = IncomingDownloads()

  /** Whether the window's intent has been offered to [incoming]. */
  var offered = false

  private var controller: AppController? = null

  /** The controller for [service]'s devices, built once and kept across recreation. */
  fun controllerFor(service: KetchService): AppController {
    val current = controller
    if (current?.instanceManager === service.instanceManager) return current
    current?.let(::retire)
    return AppController(
      instanceManager = service.instanceManager,
      incoming = incoming,
      speedMode = service.speedMode,
    ).also {
      controller = it
      relayToasts(it)
    }
  }

  override fun onCleared() {
    controller?.let(::retire)
    controller = null
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

  private fun retire(controller: AppController) {
    lingering.launch {
      delay(ADD_GRACE)
      controller.close()
    }
  }

  private fun AppMessage.toastText(): String =
    if (level == MessageLevel.Error) listOfNotNull(title, detail).joinToString("\n") else title

  private fun AppMessage.toastLength(): Int =
    if (level == MessageLevel.Error) Toast.LENGTH_LONG else Toast.LENGTH_SHORT

  private companion object {
    /** How long a closed window's controller keeps running for the adds it sent. */
    val ADD_GRACE = 30.seconds

    /** Runs the closing of controllers whose window is gone. */
    val lingering = CoroutineScope(SupervisorJob() + Dispatchers.Main)
  }
}
