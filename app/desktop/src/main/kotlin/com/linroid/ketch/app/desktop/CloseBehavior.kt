package com.linroid.ketch.app.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.nativeKeyCode
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindow
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.WindowState
import androidx.compose.ui.window.rememberDialogState
import com.linroid.ketch.app.components.KetchButton
import com.linroid.ketch.app.components.KetchButtonVariant
import com.linroid.ketch.app.components.KetchCheckbox
import com.linroid.ketch.app.input.KetchCommand
import com.linroid.ketch.app.input.KetchCommands
import com.linroid.ketch.app.input.KeyChord
import com.linroid.ketch.app.input.KeyboardPlatform
import com.linroid.ketch.app.state.AppSettingsController
import com.linroid.ketch.app.state.PulseCounts
import com.linroid.ketch.app.theme.KetchTheme
import com.linroid.ketch.app.theme.isDark
import com.linroid.ketch.config.CloseAction
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.awt.Desktop
import java.awt.EventQueue
import java.awt.MenuItem
import java.awt.PopupMenu
import java.awt.Taskbar
import java.awt.Window
import java.awt.desktop.AppReopenedListener
import java.awt.desktop.QuitResponse
import java.awt.event.InputEvent
import javax.swing.JCheckBoxMenuItem
import javax.swing.JMenu
import javax.swing.JMenuBar
import javax.swing.JMenuItem
import javax.swing.KeyStroke

/** What closing the main window does this time. */
internal enum class CloseOutcome {
  /** Hide the window; Ketch keeps running in the menu bar or notification area. */
  Hide,

  /** Minimize the window, where there is no tray icon to bring it back from. */
  Minimize,

  /** Ask whether to keep downloading in the background. */
  Ask,

  /** Quit Ketch. */
  Quit,
}

/**
 * What closing the main window does under [action] while [activeDownloads] run on this computer.
 *
 * [CloseAction.Ask] only asks while downloads are active and until the user answers in this run
 * ([answer]); otherwise the window goes away and Ketch keeps running, as ⌘W promises. Without a
 * tray, as on GNOME without AppIndicator, the window minimizes instead of hiding.
 */
internal fun closeOutcome(
  action: CloseAction,
  activeDownloads: Int,
  traySupported: Boolean,
  answer: CloseAction? = null,
): CloseOutcome {
  val effective = if (action == CloseAction.Ask) answer ?: action else action
  return when {
    effective == CloseAction.Quit -> CloseOutcome.Quit
    effective == CloseAction.Ask && activeDownloads > 0 -> CloseOutcome.Ask
    traySupported -> CloseOutcome.Hide
    else -> CloseOutcome.Minimize
  }
}

/** A question [CloseBehavior] asks in a dialog window. */
internal sealed interface LifecycleDialog {
  /** Number of downloads the answer affects. */
  val downloads: Int

  /** Whether to keep downloading in the background when the window closes. */
  data class KeepRunning(override val downloads: Int) : LifecycleDialog

  /** Whether to quit, pausing the active downloads. */
  data class ConfirmQuit(override val downloads: Int) : LifecycleDialog
}

/**
 * What a [LifecycleDialog] says.
 *
 * @property confirm label of the main button.
 * @property dismiss label of the other button.
 * @property dontAskAgain whether it offers "Don't ask again".
 */
internal data class DialogCopy(
  val title: String,
  val body: String,
  val confirm: String,
  val dismiss: String,
  val dontAskAgain: Boolean = false,
)

/** The copy of [dialog] on [os], whose tray Ketch stays in when there is one. */
internal fun dialogCopy(
  dialog: LifecycleDialog,
  os: DesktopOs,
  traySupported: Boolean,
): DialogCopy {
  val downloads = if (dialog.downloads == 1) "1 download" else "${dialog.downloads} downloads"
  return when (dialog) {
    is LifecycleDialog.KeepRunning -> {
      val platform = if (os == DesktopOs.MAC) KeyboardPlatform.Mac else KeyboardPlatform.Pc
      val quit = KetchCommands.Quit.shortcutLabel(platform)
      val tray = when {
        !traySupported -> null
        os == DesktopOs.MAC -> "the menu bar"
        os == DesktopOs.WINDOWS -> "the notification area"
        else -> "the system tray"
      }
      val stays = if (tray == null) "stays minimized" else "stays in $tray"
      val from = tray?.let { "from the ${it.removePrefix("the ")} icon" }
      val how = listOfNotNull(from, quit?.let { "with $it" }).joinToString(" or ")
      val body = "Ketch $stays and finishes $downloads." +
        if (how.isEmpty()) "" else " Quit any time $how."
      DialogCopy(
        title = "Keep downloading in the background?",
        body = body,
        confirm = "Keep Running",
        dismiss = "Quit Ketch",
        dontAskAgain = true,
      )
    }
    is LifecycleDialog.ConfirmQuit -> DialogCopy(
      title = "Quit Ketch?",
      body = "$downloads will pause and resume next time you open Ketch.",
      confirm = "Quit",
      dismiss = "Cancel",
    )
  }
}

/**
 * What closing and quitting do. Closing hides the main window, so downloads keep running and the
 * tray icon brings it back; the first close while downloads are active asks first, unless the
 * `[desktop]` close action says otherwise. Quitting asks first while downloads are active.
 *
 * Meant to be used from the main thread.
 *
 * @param windowState state of the main window, which minimizes where there is no tray.
 * @param closeAction what closing does, from the `[desktop]` settings.
 * @param startHidden whether the window starts hidden, as a [BACKGROUND_FLAG] launch does. Without
 *   a tray it starts minimized instead.
 * @param traySupported whether the system has a tray to come back from.
 * @param activeDownloads counts the downloads on this computer that are downloading or queued.
 * @param saveCloseAction saves the answer given with "Don't ask again".
 * @param onFirstHide runs the first time the window hides in this run.
 * @param quit commits pending operations, closes the engine and exits; it tells macOS to go on
 *   with the quit through the [QuitResponse] when macOS asked for it.
 */
@Stable
internal class CloseBehavior(
  private val windowState: WindowState,
  closeAction: CloseAction,
  startHidden: Boolean,
  val traySupported: Boolean,
  private val activeDownloads: () -> Int,
  private val saveCloseAction: (CloseAction) -> Unit,
  private val onFirstHide: () -> Unit,
  private val quit: (QuitResponse?) -> Unit,
) {
  /** Whether the main window is shown; it stays alive, and keeps its state, while hidden. */
  var windowVisible: Boolean by mutableStateOf(!startHidden || !traySupported)
    private set

  /**
   * What closing the main window does, following the `[desktop]` settings. Setting it forgets the
   * answer given in this run, so choosing Ask in Settings asks again.
   */
  var closeAction: CloseAction = closeAction
    set(value) {
      field = value
      answer = null
    }

  /** The question shown, or `null`. */
  var dialog: LifecycleDialog? by mutableStateOf(null)
    private set

  private var answer: CloseAction? = null
  private var hiddenBefore = false
  private var quitting = false
  private var quitResponse: QuitResponse? = null
  private val fronts = MutableSharedFlow<Unit>(
    extraBufferCapacity = 1,
    onBufferOverflow = BufferOverflow.DROP_OLDEST,
  )

  /** Emits when the main window should come to the front. */
  val frontRequests: SharedFlow<Unit> = fronts.asSharedFlow()

  init {
    if (startHidden && !traySupported) windowState.isMinimized = true
  }

  /** Shows the main window and brings it to the front. */
  fun showWindow() {
    if (quitting) return
    windowVisible = true
    windowState.isMinimized = false
    fronts.tryEmit(Unit)
  }

  /** Closes the main window, as its close button and ⌘W do. */
  fun closeWindow() {
    if (quitting || dialog != null) return
    val downloads = activeDownloads()
    when (closeOutcome(closeAction, downloads, traySupported, answer)) {
      CloseOutcome.Hide -> hide()
      CloseOutcome.Minimize -> windowState.isMinimized = true
      CloseOutcome.Ask -> dialog = LifecycleDialog.KeepRunning(downloads)
      CloseOutcome.Quit -> requestQuit()
    }
  }

  /**
   * Quits, asking first while downloads are active. Asked again while the question shows, it
   * quits at once.
   *
   * @param response macOS's request to quit, answered once the app has closed or the user
   *   cancelled.
   */
  fun requestQuit(response: QuitResponse? = null) {
    if (quitting) return
    val downloads = activeDownloads()
    if (downloads == 0 || dialog is LifecycleDialog.ConfirmQuit) {
      if (response != null) quitResponse?.cancelQuit()
      quitNow(response ?: quitResponse)
      return
    }
    quitResponse?.cancelQuit()
    quitResponse = response
    dialog = LifecycleDialog.ConfirmQuit(downloads)
  }

  /** Answers the dialog's main button. */
  fun confirm(dontAskAgain: Boolean = false) {
    when (dialog) {
      is LifecycleDialog.KeepRunning -> answer(CloseAction.Background, dontAskAgain)
      is LifecycleDialog.ConfirmQuit -> {
        dialog = null
        quitNow(quitResponse)
      }
      null -> {}
    }
  }

  /** Answers the dialog's other button. */
  fun dismiss(dontAskAgain: Boolean = false) {
    when (dialog) {
      is LifecycleDialog.KeepRunning -> answer(CloseAction.Quit, dontAskAgain)
      is LifecycleDialog.ConfirmQuit -> cancel()
      null -> {}
    }
  }

  /** Closes the dialog without an answer; the window stays as it was. */
  fun cancel() {
    dialog = null
    quitResponse?.cancelQuit()
    quitResponse = null
  }

  private fun answer(action: CloseAction, dontAskAgain: Boolean) {
    dialog = null
    if (dontAskAgain) {
      closeAction = action
      saveCloseAction(action)
    }
    answer = action
    when {
      action == CloseAction.Quit -> quitNow(null)
      traySupported -> hide()
      // Hidden, the window could only come back with another launch.
      else -> windowState.isMinimized = true
    }
  }

  private fun hide() {
    windowVisible = false
    if (!hiddenBefore) {
      hiddenBefore = true
      onFirstHide()
    }
  }

  private fun quitNow(response: QuitResponse?) {
    if (quitting) return
    quitting = true
    quitResponse = null
    dialog = null
    windowVisible = false
    quit(response)
  }
}

/**
 * The window of [behavior]'s question, if one is asked, themed like the app with [settings].
 * Return confirms, unless a focused button or checkbox takes it, and Escape closes it without an
 * answer.
 */
@Composable
internal fun CloseDialogs(behavior: CloseBehavior, settings: AppSettingsController) {
  val dialog = behavior.dialog ?: return
  val darkTheme = settings.themeMode.isDark()
  val copy = dialogCopy(dialog, DesktopOs.current, behavior.traySupported)
  var dontAskAgain by remember(dialog) { mutableStateOf(false) }
  DialogWindow(
    onCloseRequest = behavior::cancel,
    state = rememberDialogState(
      position = WindowPosition(Alignment.Center),
      size = DpSize(DIALOG_WIDTH, Dp.Unspecified),
    ),
    title = "Ketch",
    resizable = false,
    alwaysOnTop = true,
    onKeyEvent = { event ->
      if (event.type != KeyEventType.KeyDown) return@DialogWindow false
      when (event.key) {
        Key.Enter -> behavior.confirm(dontAskAgain)
        Key.Escape -> behavior.cancel()
        else -> return@DialogWindow false
      }
      true
    },
  ) {
    // Asked from the tray or the Dock, Ketch is not the active app, so the keys would go elsewhere.
    LaunchedEffect(Unit) { bringToFront(window) }
    KetchTheme(darkTheme = darkTheme, accent = settings.accent) {
      val spacing = KetchTheme.spacing
      Column(
        modifier = Modifier
          .fillMaxWidth()
          .background(KetchTheme.colors.surfaceRaised)
          .padding(spacing.s6),
        verticalArrangement = Arrangement.spacedBy(spacing.s4),
      ) {
        val colors = KetchTheme.colors
        val typography = KetchTheme.typography
        BasicText(copy.title, style = typography.titleL.copy(color = colors.textPrimary))
        BasicText(copy.body, style = typography.body.copy(color = colors.textSecondary))
        if (copy.dontAskAgain) {
          KetchCheckbox(
            checked = dontAskAgain,
            onCheckedChange = { dontAskAgain = it },
            label = "Don't ask again",
          )
        }
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.spacedBy(spacing.s2, Alignment.End),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          KetchButton(
            text = copy.dismiss,
            onClick = { behavior.dismiss(dontAskAgain) },
            variant = KetchButtonVariant.Secondary,
          )
          KetchButton(text = copy.confirm, onClick = { behavior.confirm(dontAskAgain) })
        }
      }
    }
  }
}

/** Brings [window] to the front; on macOS, which only does that for the active app, Ketch first. */
internal fun bringToFront(window: Window) {
  if (Desktop.isDesktopSupported()) {
    val desktop = Desktop.getDesktop()
    if (desktop.isSupported(Desktop.Action.APP_REQUEST_FOREGROUND)) desktop.requestForeground(true)
  }
  window.toFront()
  window.requestFocus()
}

/**
 * Hooks [behavior] into macOS: ⌘Q and the Dock's Quit ask it to quit, and a click on the Dock icon
 * shows the window. Does nothing where the system lacks these events.
 *
 * @return removes the handlers.
 */
internal fun installAppHandlers(behavior: CloseBehavior): () -> Unit {
  if (!Desktop.isDesktopSupported()) return {}
  val desktop = Desktop.getDesktop()
  val quits = desktop.isSupported(Desktop.Action.APP_QUIT_HANDLER)
  if (quits) {
    desktop.setQuitHandler { _, response ->
      EventQueue.invokeLater { behavior.requestQuit(response) }
    }
  }
  val reopened = AppReopenedListener { EventQueue.invokeLater(behavior::showWindow) }
  val reopens = desktop.isSupported(Desktop.Action.APP_EVENT_REOPENED)
  if (reopens) desktop.addAppEventListener(reopened)
  return {
    if (quits) desktop.setQuitHandler(null)
    if (reopens) desktop.removeAppEventListener(reopened)
  }
}

/**
 * The Dock menu on macOS: adding a download, adding the link on the clipboard, and pausing or
 * resuming everything, enabled as the tray's items are for [counts].
 */
@Composable
internal fun DockMenu(commands: DesktopCommands, counts: PulseCounts) {
  val menu = remember(commands) { DockMenuItems.install(commands) } ?: return
  SideEffect { menu.update(counts) }
  DisposableEffect(menu) {
    onDispose { menu.uninstall() }
  }
}

private class DockMenuItems(private val taskbar: Taskbar, commands: DesktopCommands) {
  private val pauseAll = item(KetchCommands.PauseAll, commands)
  private val resumeAll = item(KetchCommands.ResumeAll, commands)
  val menu = PopupMenu().apply {
    add(item(KetchCommands.Add, commands))
    add(item(KetchCommands.AddClipboardLink, commands))
    addSeparator()
    add(pauseAll)
    add(resumeAll)
  }

  fun update(counts: PulseCounts) {
    pauseAll.isEnabled = counts.downloading + counts.waiting > 0
    resumeAll.isEnabled = counts.paused > 0
  }

  fun uninstall() {
    if (taskbar.menu === menu) taskbar.menu = null
  }

  private fun item(command: KetchCommand, commands: DesktopCommands) =
    MenuItem(command.label).apply { addActionListener { commands.run(command) } }

  companion object {
    fun install(commands: DesktopCommands): DockMenuItems? {
      if (DesktopOs.current != DesktopOs.MAC || !Taskbar.isTaskbarSupported()) return null
      val taskbar = Taskbar.getTaskbar()
      if (!taskbar.isSupported(Taskbar.Feature.MENU)) return null
      return DockMenuItems(taskbar, commands).also { taskbar.menu = it.menu }
    }
  }
}

/**
 * The macOS menu bar while no Ketch window is open, as after ⌘W, so the menu bar's commands and
 * their shortcuts, such as ⌘N, ⇧⌘P and ⇧⌘L, keep working. It shows [menus] and runs their items
 * with [onAction].
 */
@Composable
internal fun DefaultMenuBar(menus: List<MenuBarMenu>, onAction: (MenuAction) -> Unit) {
  val supported = remember {
    Desktop.isDesktopSupported() &&
      Desktop.getDesktop().isSupported(Desktop.Action.APP_MENU_BAR)
  }
  if (!supported) return
  val currentAction by rememberUpdatedState(onAction)
  LaunchedEffect(menus) {
    val bar = JMenuBar()
    for (menu in menus) bar.add(swingMenu(menu.title, menu.entries) { currentAction(it) })
    Desktop.getDesktop().setDefaultMenuBar(bar)
  }
  DisposableEffect(Unit) {
    onDispose { Desktop.getDesktop().setDefaultMenuBar(null) }
  }
}

private fun swingMenu(
  title: String,
  entries: List<MenuEntry>,
  onAction: (MenuAction) -> Unit,
): JMenu = JMenu(title).apply {
  for (entry in entries) {
    when (entry) {
      MenuEntry.Separator -> addSeparator()
      is MenuEntry.Header -> add(JMenuItem(entry.text).apply { isEnabled = false })
      is MenuEntry.Item -> {
        val item = if (entry.checked == null) {
          JMenuItem(entry.label)
        } else {
          JCheckBoxMenuItem(entry.label, entry.checked)
        }
        item.isEnabled = entry.enabled
        item.accelerator = entry.shortcut?.toKeyStroke()
        item.addActionListener { onAction(entry.action) }
        add(item)
      }
      is MenuEntry.Submenu -> {
        val submenu = swingMenu(entry.label, entry.entries, onAction)
        submenu.isEnabled = entry.enabled
        add(submenu)
      }
    }
  }
}

private fun KeyChord.toKeyStroke(): KeyStroke {
  val press = resolve(KeyboardPlatform.Mac)
  var modifiers = 0
  if (press.meta) modifiers = modifiers or InputEvent.META_DOWN_MASK
  if (press.ctrl) modifiers = modifiers or InputEvent.CTRL_DOWN_MASK
  if (press.alt) modifiers = modifiers or InputEvent.ALT_DOWN_MASK
  if (press.shift) modifiers = modifiers or InputEvent.SHIFT_DOWN_MASK
  return KeyStroke.getKeyStroke(press.key.nativeKeyCode, modifiers)
}

// Width of the question windows; their height follows the text.
private val DIALOG_WIDTH = 420.dp
