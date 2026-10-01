package com.linroid.ketch.app.desktop

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.linroid.ketch.api.log.KetchLogger
import com.linroid.ketch.api.log.describeCauses
import com.linroid.ketch.app.platform.DesktopHooks
import com.linroid.ketch.app.platform.FileActions
import com.linroid.ketch.config.CloseAction
import com.linroid.ketch.config.DesktopSettings
import com.linroid.ketch.config.DockBadgeMode
import com.linroid.ketch.config.IntegrationSettings
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/**
 * The desktop app's [DesktopHooks]: closing follows [closeBehavior], the login item and the link
 * and file handlers are written for the operating system, and the Dock or taskbar badge follows
 * [dockBadge].
 *
 * @param settings the saved `[desktop]` settings to start from.
 * @param logsDir folder of the log files, which [openLogsFolder] opens with [files].
 */
@Stable
internal class DesktopHooksImpl(
  private val closeBehavior: CloseBehavior,
  settings: DesktopSettings,
  private val logsDir: File,
  private val files: FileActions?,
  private val loginItem: LoginItem = LoginItem(),
  private val handlers: MagnetHandler = MagnetHandler(),
  private val io: CoroutineDispatcher = Dispatchers.IO,
) : DesktopHooks {
  private val log = KetchLogger("DesktopHooks")
  private var openAtLogin = settings.openAtLogin
  private var startHidden = settings.startHidden

  /** What the Dock or taskbar badge shows. */
  var dockBadge: DockBadgeMode by mutableStateOf(settings.dockBadge)
    private set

  override val isSupported: Boolean get() = true

  override fun setCloseAction(action: CloseAction) {
    closeBehavior.closeAction = action
  }

  override suspend fun setOpenAtLogin(enabled: Boolean) {
    openAtLogin = enabled
    withContext(io) { loginItem.set(enabled, hidden = startHidden) }
  }

  override suspend fun setStartHidden(enabled: Boolean) {
    startHidden = enabled
    if (openAtLogin) withContext(io) { loginItem.set(enabled = true, hidden = enabled) }
  }

  override fun setDockBadgeMode(mode: DockBadgeMode) {
    dockBadge = mode
  }

  override suspend fun registerMagnetHandler(): Boolean {
    withContext(io) { handlers.registerMagnet() }
    return true
  }

  override suspend fun registerTorrentFileHandler(): Boolean {
    withContext(io) { handlers.registerTorrentFiles() }
    return true
  }

  override suspend fun openLogsFolder() {
    val actions = files ?: throw IOException("This computer can't open folders")
    withContext(io) { logsDir.mkdirs() }
    actions.open(logsDir.path)
  }

  /**
   * Writes the login item and the handlers the user turned on again, so they start the app where
   * it now lives after an update or a move. A login item removed in the system settings stays
   * removed. Blocks, so call it off the main thread; failures are logged.
   */
  fun refresh(integration: IntegrationSettings) {
    attempt("update the login item") {
      if (openAtLogin && loginItem.isEnabled()) loginItem.set(enabled = true, hidden = startHidden)
    }
    // macOS keeps the default handlers by bundle, which updates and moves keep.
    if (DesktopOs.current == DesktopOs.MAC) return
    if (integration.magnetHandler) attempt("register for magnet links", handlers::registerMagnet)
    if (integration.torrentFileHandler) {
      attempt("register for .torrent files", handlers::registerTorrentFiles)
    }
  }

  private fun attempt(what: String, block: () -> Unit) {
    try {
      block()
    } catch (e: Exception) {
      log.w { "Couldn't $what: ${e.describeCauses()}" }
    }
  }
}
