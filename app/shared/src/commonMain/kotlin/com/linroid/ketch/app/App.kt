package com.linroid.ketch.app

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import com.linroid.ketch.app.feedback.ActivityEvent
import com.linroid.ketch.app.instance.InstanceManager
import com.linroid.ketch.app.log.FileLogger
import com.linroid.ketch.app.platform.rememberReduceMotion
import com.linroid.ketch.app.state.AiDiscoveryProviderFactory
import com.linroid.ketch.app.state.AppController
import com.linroid.ketch.app.state.IncomingDownloads
import com.linroid.ketch.app.state.rememberAppController
import com.linroid.ketch.app.theme.KetchTheme
import com.linroid.ketch.app.ui.AppShell
import com.linroid.ketch.app.ui.onboarding.WelcomeFlow
import com.linroid.ketch.app.ui.onboarding.needsWelcome
import com.linroid.ketch.app.ui.onboarding.welcomePlatform
import com.linroid.ketch.config.ThemeMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first

/**
 * Root of the Ketch app, shared by every platform, with an [AppController] that lives as long
 * as this composition.
 *
 * @param openSettingsRequests emits when the platform asks to open
 *   Settings, e.g. from a keyboard shortcut or the macOS app menu.
 * @param incoming downloads opened from outside the app, such as `.torrent` files opened from
 *   the system file manager; each one is shown in the add dialog.
 * @param fileLogger the app's log files, which Settings → About opens
 *   or shares for bug reports; `null` when the app keeps none.
 * @param activityEvents events from the host's activity monitor while the app is in front.
 */
@Composable
fun App(
  instanceManager: InstanceManager,
  aiProviderFactory: AiDiscoveryProviderFactory? = null,
  openSettingsRequests: Flow<Unit> = emptyFlow(),
  incoming: IncomingDownloads? = null,
  fileLogger: FileLogger? = null,
  activityEvents: Flow<ActivityEvent> = emptyFlow(),
) {
  App(
    controller = rememberAppController(instanceManager, aiProviderFactory, incoming),
    activityEvents = activityEvents,
    openSettingsRequests = openSettingsRequests,
    fileLogger = fileLogger,
  )
}

/**
 * Root of the Ketch app for a host that owns [controller], such as the desktop app, whose tray
 * and menu bar share it with the window. On phones and tablets a new user first sees the
 * welcome flow.
 *
 * @param activityEvents events from the host's activity monitor while the app is in front; they
 *   show as toasts.
 * @param openSettingsRequests emits when the platform asks to open Settings.
 * @param fileLogger the app's log files; `null` when the app keeps none.
 */
@Composable
fun App(
  controller: AppController,
  activityEvents: Flow<ActivityEvent> = emptyFlow(),
  openSettingsRequests: Flow<Unit> = emptyFlow(),
  fileLogger: FileLogger? = null,
) {
  LaunchedEffect(controller, activityEvents) {
    activityEvents.collect { controller.state.report(it) }
  }
  val appSettings = controller.appSettings
  val darkTheme = when (appSettings.themeMode) {
    ThemeMode.System -> isSystemInDarkTheme()
    ThemeMode.Light -> false
    ThemeMode.Dark -> true
  }
  // The preference only adds to the system setting; off follows the system.
  val systemReducesMotion = rememberReduceMotion()
  // Decided once, so "Show welcome again" in Settings waits for the next launch. A launch that
  // opens a pairing link or a download goes straight to it and leaves the welcome for the next.
  var welcome by rememberSaveable {
    mutableStateOf(
      needsWelcome(welcomePlatform, appSettings.ui) && !controller.state.incoming.hasPending
    )
  }
  if (welcome) {
    // A link or file opened while the welcome shows goes first; the welcome waits for the next
    // launch, as when the app starts with one.
    val incoming = controller.state.incoming
    LaunchedEffect(incoming) {
      combine(incoming.pending, incoming.pendingLinks, incoming.pendingPairings) { a, b, c ->
        a.isNotEmpty() || b.isNotEmpty() || c.isNotEmpty()
      }.first { it }
      welcome = false
    }
  }
  KetchTheme(
    darkTheme = darkTheme,
    accent = appSettings.accent,
    density = appSettings.ui.density,
    reduceMotion = appSettings.ui.reduceMotion || systemReducesMotion,
  ) {
    val platform = welcomePlatform
    Crossfade(
      targetState = welcome,
      animationSpec = tween(KetchTheme.motion.long),
      label = "welcome",
    ) { welcoming ->
      if (welcoming && platform != null) {
        WelcomeFlow(controller.state, platform, onDone = { welcome = false })
      } else {
        AppShell(controller.state, openSettingsRequests, fileLogger)
      }
    }
  }
}
