package com.linroid.ketch.app.ui.settings

import androidx.compose.material3.adaptive.currentWindowAdaptiveInfoV2
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.window.core.layout.WindowSizeClass
import com.linroid.ketch.app.instance.EmbeddedInstance
import com.linroid.ketch.app.log.FileLogger
import com.linroid.ketch.app.state.AppState
import com.linroid.ketch.app.state.SettingsCategory
import com.linroid.ketch.app.state.SettingsTarget
import kotlinx.coroutines.launch

/** The app's log files, which the About page opens or shares; `null` when the app keeps none. */
val LocalFileLogger = staticCompositionLocalOf<FileLogger?> { null }

/**
 * Settings: a dialog on wide windows and a full page on narrow ones, which the shell then shows
 * in place of the current destination.
 *
 * @param target page asked for; on a narrow window it opens that page's category, while the
 *   dialog starts at the first one.
 * @param onClose closes Settings.
 */
@Composable
fun SettingsHost(state: AppState, target: SettingsTarget?, onClose: () -> Unit) {
  val scope = rememberCoroutineScope()
  val instances by state.instances.collectAsState()
  val active by state.activeInstance.collectAsState()
  val serverState by state.serverState.collectAsState()
  val fileLogger = LocalFileLogger.current
  val instanceManager = state.instanceManager
  val aiSettings = state.aiSettings
  val categories = SettingsCategory.visible(instanceManager.isLocalServerSupported)
  val content: @Composable (SettingsCategory) -> Unit = { category ->
    SettingsCategoryContent(
      category = category,
      appSettings = state.appSettings,
      aiSettings = aiSettings,
      // Download, network and torrent settings belong to the active device.
      instanceSettings = state.instanceSettings,
      instanceLabel = active?.label ?: "this device",
      systemDeviceName = instances.firstOrNull { it is EmbeddedInstance }?.label,
      serverState = serverState,
      onTestAi = { scope.launch { aiSettings.testConnection(aiSettings.settings) } },
      onStartServer = { instanceManager.startServer() },
      onStopServer = { instanceManager.stopServer() },
      fileLogger = fileLogger,
    )
  }
  val wide = currentWindowAdaptiveInfoV2().windowSizeClass
    .isWidthAtLeastBreakpoint(WindowSizeClass.WIDTH_DP_EXPANDED_LOWER_BOUND)
  if (wide) {
    SettingsDialog(categories = categories, onDismiss = onClose, content = content)
  } else {
    val page = target?.page
    key(page) {
      SettingsPage(
        categories = categories,
        content = content,
        onClose = onClose,
        initialCategory = page?.category()?.takeIf { it in categories },
      )
    }
  }
}

/** Category that holds this page; `null` for pages that open on the category list. */
private fun SettingsTarget.Page.category(): SettingsCategory? = when (this) {
  SettingsTarget.Page.General,
  SettingsTarget.Page.Notifications,
  SettingsTarget.Page.Integration,
  -> null
  SettingsTarget.Page.Discover -> SettingsCategory.Ai
  SettingsTarget.Page.About -> SettingsCategory.About
  SettingsTarget.Page.Downloads, SettingsTarget.Page.Speed -> SettingsCategory.Downloads
  SettingsTarget.Page.Network -> SettingsCategory.Network
  SettingsTarget.Page.BitTorrent -> SettingsCategory.BitTorrent
  SettingsTarget.Page.Sharing -> SettingsCategory.RemoteAccess
}
