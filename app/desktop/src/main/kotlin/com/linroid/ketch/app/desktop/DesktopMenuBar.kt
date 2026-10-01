package com.linroid.ketch.app.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyShortcut
import androidx.compose.ui.window.FrameWindowScope
import androidx.compose.ui.window.MenuBar
import androidx.compose.ui.window.MenuScope
import com.linroid.ketch.api.DownloadState
import com.linroid.ketch.api.DownloadTask
import com.linroid.ketch.api.log.KetchLogger
import com.linroid.ketch.api.log.describeCauses
import com.linroid.ketch.app.feedback.MessageAction
import com.linroid.ketch.app.feedback.MessageLevel
import com.linroid.ketch.app.input.CommandScope
import com.linroid.ketch.app.input.KetchCommand
import com.linroid.ketch.app.input.KetchCommands
import com.linroid.ketch.app.input.KeyChord
import com.linroid.ketch.app.input.KeyboardPlatform
import com.linroid.ketch.app.instance.EmbeddedInstance
import com.linroid.ketch.app.instance.InstanceEntry
import com.linroid.ketch.app.platform.FileActions
import com.linroid.ketch.app.platform.SystemClipboard
import com.linroid.ketch.app.platform.localDeviceNoun
import com.linroid.ketch.app.platform.rememberFileActions
import com.linroid.ketch.app.platform.rememberSystemClipboard
import com.linroid.ketch.app.state.AppController
import com.linroid.ketch.app.state.AppState
import com.linroid.ketch.app.state.IntakeRequest
import com.linroid.ketch.app.state.LOCAL_DEVICE_ID
import com.linroid.ketch.app.state.PulseCounts
import com.linroid.ketch.app.state.SpeedModeController
import com.linroid.ketch.app.state.StatusFilter
import com.linroid.ketch.app.state.TaskKey
import com.linroid.ketch.app.state.deviceId
import com.linroid.ketch.app.state.isSlowLane
import com.linroid.ketch.app.util.LinkParser
import com.linroid.ketch.app.util.displayName
import com.linroid.ketch.app.util.formatBytes
import com.linroid.ketch.app.util.links
import com.linroid.ketch.config.SpeedLimitMode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import java.io.FilenameFilter

private val isMac = System.getProperty("os.name").startsWith("Mac")

/**
 * What the tray and the menu bar ask of the host, which owns the main window and the app's
 * lifetime.
 *
 * @property showWindow shows the main window and brings it to the front.
 * @property closeWindow closes the main window; Ketch keeps running.
 * @property quit quits Ketch, asking first while downloads are active.
 * @property openFiles adds the `.torrent` files picked with "Open torrent file…".
 */
class DesktopActions(
  val showWindow: () -> Unit,
  val closeWindow: () -> Unit,
  val quit: () -> Unit,
  val openFiles: (List<File>) -> Unit,
)

/**
 * The macOS menu bar of the main window, generated from [KetchCommands] and run against the
 * [AppState] of [controller]. Windows and Linux get no in-window menu bar; the same commands are
 * in the palette, tooltips and context menus.
 *
 * The screen menu bar sees key presses before the window does, so only global chords that no
 * text field, list, add sheet or palette uses are menu shortcuts. The others, such as ⌘C, ⌘Z,
 * Space and ↩, show as text in the item's label and stay with the focused control.
 *
 * @param status the Pulse and failures shown by the tray, for which items are enabled.
 * @param actions shows and closes the window.
 * @param speedMode switches the active device's speed mode; `null` leaves out Slow lane.
 */
@Composable
fun FrameWindowScope.KetchMenuBar(
  controller: AppController,
  status: DesktopStatus,
  actions: DesktopActions,
  speedMode: SpeedModeController? = null,
) {
  if (!isMac) return
  val state = controller.state
  val files = rememberFileActions()
  val clipboard = rememberSystemClipboard()
  val commands = remember(controller, actions, speedMode, files, clipboard) {
    DesktopCommands(controller, actions, speedMode, files, clipboard) {
      window.extendedState = window.extendedState or Frame.ICONIFIED
    }
  }
  val instances by state.instances.collectAsState()
  val active by state.activeInstance.collectAsState()
  val ops by state.pendingOps.ops.collectAsState()
  val mode = speedMode?.mode?.collectAsState()?.value
  val selectedKeys = state.selectedKeys
  val selection by remember(selectedKeys, instances) {
    selectionFlow(selectedKeys, instances)
  }.collectAsState(emptyList())
  val platform = KeyboardPlatform.current
  val menus = menuBar(
    MenuBarContext(
      counts = status.pulse.counts,
      failures = status.pulse.failures,
      filter = state.statusFilter,
      devices = instances.map { if (it is EmbeddedInstance) localDeviceNoun() else it.label },
      activeDevice = instances.indexOf(active).takeIf { it >= 0 },
      selection = selection,
      undoLabel = ops.lastOrNull()?.label,
      inspectorOpen = state.inspectorOpen,
      slowLane = mode?.isSlowLane,
      revealLabel = files?.revealLabel,
      platform = platform,
    ),
  )
  MenuBar {
    for (menu in menus) {
      Menu(menu.title) { MenuEntries(menu.entries, platform, commands::perform) }
    }
  }
}

/** One entry of a menu in the menu bar or the tray. */
internal sealed interface MenuEntry {
  /** A line between groups. */
  data object Separator : MenuEntry

  /** A line of text that cannot be clicked, such as the tray's status sentence. */
  data class Header(val text: String) : MenuEntry

  /**
   * A clickable item.
   *
   * @property checked whether it shows a check mark; `null` for an item that never has one.
   * @property shortcut the chord the menu owns, shown at the item's end.
   */
  data class Item(
    val action: MenuAction,
    val label: String,
    val enabled: Boolean = true,
    val checked: Boolean? = null,
    val shortcut: KeyChord? = null,
  ) : MenuEntry

  /** A submenu. */
  data class Submenu(
    val label: String,
    val entries: List<MenuEntry>,
    val enabled: Boolean = true,
  ) : MenuEntry
}

/** What clicking a [MenuEntry.Item] does. */
internal sealed interface MenuAction {
  /** Runs [command]. */
  data class Run(val command: KetchCommand) : MenuAction

  /** Switches the active device to [mode]. */
  data class SetSpeedMode(val mode: SpeedLimitMode) : MenuAction

  /** Opens the downloaded file at [path], named [name] in messages. */
  data class OpenFile(val path: String, val name: String) : MenuAction

  /** Shows the main window. */
  data object ShowWindow : MenuAction
}

/** A menu of the menu bar. */
internal data class MenuBarMenu(val title: String, val entries: List<MenuEntry>)

/** What a selected task allows, as far as the menus need it. */
internal enum class TaskPhase {
  /** Downloading, queued or scheduled. */
  Running,
  Paused,
  Failed,
  Canceled,
  Completed,
  ;

  companion object {
    /** The phase of a task in [state]. */
    fun of(state: DownloadState): TaskPhase = when (state) {
      is DownloadState.Downloading, DownloadState.Queued, is DownloadState.Scheduled -> Running
      is DownloadState.Paused -> Paused
      is DownloadState.Failed -> Failed
      DownloadState.Canceled -> Canceled
      is DownloadState.Completed -> Completed
    }
  }
}

/**
 * A selected task.
 *
 * @property local whether it runs on the embedded device, whose files this app can open.
 */
internal data class SelectedTask(val phase: TaskPhase, val local: Boolean)

/**
 * What the menu bar reflects.
 *
 * @property counts tasks per status tab of the active scope.
 * @property failures failed tasks of the active scope, not counting canceled ones.
 * @property filter the status tab shown.
 * @property devices names of the devices, in the order ⌥⌘1 to ⌥⌘9 switch to them.
 * @property activeDevice index of the active device in [devices], or `null`.
 * @property selection the selected tasks.
 * @property undoLabel label of the operation ⌘Z undoes, or `null` when there is none.
 * @property inspectorOpen whether the inspector is shown.
 * @property slowLane whether the slow lane is on; `null` when speed modes are unavailable.
 * @property revealLabel label of Reveal, such as "Show in Finder"; `null` keeps the command's.
 */
internal data class MenuBarContext(
  val counts: PulseCounts,
  val failures: Int,
  val filter: StatusFilter,
  val devices: List<String>,
  val activeDevice: Int?,
  val selection: List<SelectedTask>,
  val undoLabel: String?,
  val inspectorOpen: Boolean,
  val slowLane: Boolean?,
  val revealLabel: String? = null,
  val platform: KeyboardPlatform = KeyboardPlatform.Mac,
)

/** The menus of the menu bar in [context]. */
internal fun menuBar(context: MenuBarContext): List<MenuBarMenu> {
  val platform = context.platform
  fun item(
    command: KetchCommand,
    label: String = command.label,
    enabled: Boolean = true,
    checked: Boolean? = null,
  ): MenuEntry.Item = commandItem(command, platform, label, enabled, checked)

  val selection = context.selection
  val running = selection.count { it.phase == TaskPhase.Running }
  val resumable = selection.count {
    it.phase != TaskPhase.Running && it.phase != TaskPhase.Completed
  }
  val localDone = selection.count { it.local && it.phase == TaskPhase.Completed }
  val retryable = selection.count {
    it.phase == TaskPhase.Failed || it.phase == TaskPhase.Paused || it.phase == TaskPhase.Canceled
  }
  val togglePause = when {
    running > 0 -> "Pause"
    resumable > 0 -> "Resume"
    else -> KetchCommands.TogglePause.label
  }
  val counts = context.counts
  return listOfNotNull(
    MenuBarMenu(
      "File",
      listOf(
        item(KetchCommands.Add),
        item(KetchCommands.AddClipboardLink),
        item(KetchCommands.OpenTorrent),
        MenuEntry.Separator,
        item(KetchCommands.CloseWindow),
      ),
    ),
    MenuBarMenu(
      "Edit",
      listOf(
        item(
          KetchCommands.Undo,
          label = context.undoLabel?.let { "Undo $it" } ?: KetchCommands.Undo.label,
          enabled = context.undoLabel != null,
        ),
        MenuEntry.Separator,
        item(KetchCommands.PasteLinks),
        item(KetchCommands.SelectAll, enabled = counts.count(context.filter) > 0),
        MenuEntry.Separator,
        item(KetchCommands.Search),
      ),
    ),
    MenuBarMenu(
      "View",
      buildList {
        for (filter in StatusFilter.entries) {
          add(item(KetchCommands.tab(filter), checked = filter == context.filter))
        }
        add(MenuEntry.Separator)
        add(item(KetchCommands.ToggleInspector, checked = context.inspectorOpen))
      },
    ),
    MenuBarMenu(
      "Downloads",
      listOfNotNull(
        item(KetchCommands.TogglePause, label = togglePause, enabled = running + resumable > 0),
        item(KetchCommands.Open, enabled = selection.isNotEmpty()),
        item(
          KetchCommands.Reveal,
          label = context.revealLabel ?: KetchCommands.Reveal.label,
          enabled = localDone > 0,
        ),
        item(KetchCommands.CopyLink, enabled = selection.isNotEmpty()),
        item(KetchCommands.Retry, enabled = retryable > 0),
        item(KetchCommands.Remove, enabled = selection.isNotEmpty()),
        MenuEntry.Separator,
        item(KetchCommands.PauseAll, enabled = counts.downloading + counts.waiting > 0),
        item(KetchCommands.ResumeAll, enabled = counts.paused > 0),
        item(KetchCommands.RetryFailed, enabled = context.failures > 0),
        context.slowLane?.let { item(KetchCommands.SlowLane, checked = it) },
      ),
    ),
    context.devices.take(MAX_DEVICE_ITEMS).takeIf { it.isNotEmpty() }?.let { devices ->
      MenuBarMenu(
        "Device",
        devices.mapIndexed { index, name ->
          val command = KetchCommands.device(index + 1)
          item(command, label = name, checked = index == context.activeDevice)
        },
      )
    },
    MenuBarMenu("Window", listOf(item(KetchCommands.Minimize))),
  )
}

/**
 * An item that runs [command]. A chord the menu may own becomes its shortcut; any other chord is
 * appended to [label] as text.
 */
internal fun commandItem(
  command: KetchCommand,
  platform: KeyboardPlatform,
  label: String = command.label,
  enabled: Boolean = true,
  checked: Boolean? = null,
): MenuEntry.Item {
  val chord = command.chords(platform).firstOrNull()
  val shortcut = menuShortcut(command, platform)
  val text = if (chord != null && shortcut == null) "$label (${chord.label(platform)})" else label
  return MenuEntry.Item(MenuAction.Run(command), text, enabled, checked, shortcut)
}

/**
 * The chord of [command] the menu bar may own on [platform], or `null` when it must stay with the
 * focused control: chords of list, add sheet and palette commands and any chord they share, chords
 * a text field keeps (⌘V, ⌘Z), the text editing chords ⌘A, ⌘C and ⌘X, and keys without ⌘ or
 * Control.
 */
internal fun menuShortcut(command: KetchCommand, platform: KeyboardPlatform): KeyChord? {
  val chord = command.chords(platform).firstOrNull() ?: return null
  val owned = command.scope == CommandScope.Global &&
    !command.yieldsToTextField &&
    (chord.primary || chord.ctrl) &&
    !chord.isTextEditing() &&
    chord.resolve(platform) !in focusedChords(platform)
  return chord.takeIf { owned }
}

private fun KeyChord.isTextEditing(): Boolean {
  if (!primary || alt || ctrl) return false
  return if (shift) key == TEXT_REDO_KEY else key in TEXT_EDITING_KEYS
}

private fun focusedChords(platform: KeyboardPlatform) = KetchCommands.all
  .filter { it.scope != CommandScope.Global }
  .flatMap { it.chords(platform) }
  .map { it.resolve(platform) }
  .toSet()

private val TEXT_EDITING_KEYS = setOf(Key.A, Key.C, Key.V, Key.X, Key.Z)
private val TEXT_REDO_KEY = Key.Z
private const val MAX_DEVICE_ITEMS = 9

/** Renders [entries] in a tray or menu bar menu; clicks call [onAction]. */
@Composable
internal fun MenuScope.MenuEntries(
  entries: List<MenuEntry>,
  platform: KeyboardPlatform,
  onAction: (MenuAction) -> Unit,
) {
  for (entry in entries) {
    when (entry) {
      MenuEntry.Separator -> Separator()
      is MenuEntry.Header -> Item(entry.text, enabled = false, onClick = {})
      is MenuEntry.Item -> {
        val shortcut = entry.shortcut?.toKeyShortcut(platform)
        val checked = entry.checked
        if (checked == null) {
          Item(entry.label, enabled = entry.enabled, shortcut = shortcut) { onAction(entry.action) }
        } else {
          CheckboxItem(entry.label, checked, enabled = entry.enabled, shortcut = shortcut) {
            onAction(entry.action)
          }
        }
      }
      is MenuEntry.Submenu -> Menu(entry.label, enabled = entry.enabled) {
        MenuEntries(entry.entries, platform, onAction)
      }
    }
  }
}

private fun KeyChord.toKeyShortcut(platform: KeyboardPlatform): KeyShortcut {
  val press = resolve(platform)
  return KeyShortcut(
    key = press.key,
    ctrl = press.ctrl,
    meta = press.meta,
    alt = press.alt,
    shift = press.shift,
  )
}

// Phases of the selected tasks, emitted only when one changes phase.
@OptIn(ExperimentalCoroutinesApi::class)
private fun selectionFlow(
  keys: Set<TaskKey>,
  entries: List<InstanceEntry>,
): Flow<List<SelectedTask>> {
  if (keys.isEmpty()) return flowOf(emptyList())
  val devices = entries.mapNotNull { entry ->
    val ids = keys.filter { it.deviceId == entry.deviceId }.mapTo(HashSet()) { it.taskId }
    if (ids.isEmpty()) return@mapNotNull null
    val local = entry.deviceId == LOCAL_DEVICE_ID
    entry.instance.tasks.flatMapLatest { tasks ->
      val chosen = tasks.filter { it.taskId in ids }
      if (chosen.isEmpty()) {
        flowOf(emptyList())
      } else {
        combine(chosen.map { task -> task.state.map { TaskPhase.of(it) }.distinctUntilChanged() }) {
          it.map { phase -> SelectedTask(phase, local) }
        }
      }
    }
  }
  if (devices.isEmpty()) return flowOf(emptyList())
  return combine(devices) { lists -> lists.flatMap { it } }
}

/**
 * Runs the commands of the menu bar and the tray against the [AppState] of [controller].
 *
 * Commands that need the window show it first, so they also work from the tray while the window
 * is hidden.
 *
 * @param files opens and reveals downloaded files; `null` leaves those commands without effect.
 * @param clipboard reads links to add and takes copied links.
 * @param minimizeWindow minimizes the main window.
 */
internal class DesktopCommands(
  private val controller: AppController,
  private val actions: DesktopActions,
  private val speedMode: SpeedModeController?,
  private val files: FileActions?,
  private val clipboard: SystemClipboard,
  private val minimizeWindow: () -> Unit = {},
) {
  private val log = KetchLogger("DesktopCommands")
  private val state: AppState get() = controller.state

  /** Does what [action] stands for. */
  fun perform(action: MenuAction) {
    when (action) {
      is MenuAction.Run -> run(action.command)
      is MenuAction.SetSpeedMode -> setSpeedMode(action.mode)
      is MenuAction.OpenFile -> openFile(action.path, action.name)
      MenuAction.ShowWindow -> actions.showWindow()
    }
  }

  /** Runs [command]; a command the desktop does not bind does nothing. */
  fun run(command: KetchCommand) {
    val filter = StatusFilter.entries.firstOrNull { KetchCommands.tab(it) == command }
    if (filter != null) {
      state.statusFilter = filter
      return
    }
    val device = (1..MAX_DEVICE_ITEMS).firstOrNull { KetchCommands.device(it) == command }
    if (device != null) {
      state.instances.value.getOrNull(device - 1)?.let(state::switchInstance)
      return
    }
    when (command) {
      KetchCommands.Add -> {
        actions.showWindow()
        state.openIntake()
      }
      KetchCommands.PasteLinks -> pasteLinks()
      KetchCommands.AddClipboardLink -> addClipboardLinks()
      KetchCommands.OpenTorrent -> openTorrentFiles()
      KetchCommands.Search -> {
        actions.showWindow()
        state.requestSearchFocus()
      }
      KetchCommands.PauseAll -> state.pauseAll()
      KetchCommands.ResumeAll -> state.resumeAll()
      KetchCommands.RetryFailed -> state.retryFailed()
      KetchCommands.SlowLane -> toggleSlowLane()
      KetchCommands.ToggleInspector -> state.updateInspectorOpen(!state.inspectorOpen)
      KetchCommands.Undo -> state.pendingOps.undoLast()
      KetchCommands.Settings -> {
        actions.showWindow()
        state.openSettings()
      }
      KetchCommands.CloseWindow -> actions.closeWindow()
      KetchCommands.Minimize -> minimizeWindow()
      KetchCommands.Quit -> actions.quit()
      KetchCommands.SelectAll -> {
        val filter = state.statusFilter
        state.selectedKeys = state.tasks.value
          .filter { filter.matches(it.state.value) }
          .mapTo(LinkedHashSet()) { state.keyOf(it) }
      }
      KetchCommands.TogglePause -> togglePause()
      KetchCommands.Open -> openSelected()
      KetchCommands.Reveal -> revealSelected()
      KetchCommands.CopyLink -> copyLinks()
      KetchCommands.Retry -> retrySelected()
      KetchCommands.Remove -> state.remove(selectedTasks())
      else -> log.d { "No desktop binding for ${command.id}" }
    }
  }

  // ⌘V: adds a single link at once, or opens the add sheet with what was copied.
  private fun pasteLinks() {
    controller.scope.launch {
      val text = clipboardText() ?: return@launch
      val link = LinkParser.quickAddLink(text)
      if (link != null && link.headers.isEmpty() && state.appSettings.ui.quickAdd != false) {
        addNow(listOf(link.url))
      } else {
        actions.showWindow()
        state.openIntake(IntakeRequest(text = text))
      }
    }
  }

  // ⇧⌘V: adds every link on the clipboard without the add sheet, even with the window hidden.
  private fun addClipboardLinks() {
    controller.scope.launch {
      val text = clipboardText()
      val links = text?.let { LinkParser.parseIntake(it).links() }.orEmpty()
      when {
        text == null -> {
          actions.showWindow()
          state.messages.post(MessageLevel.Warning, "The clipboard holds no link")
        }
        links.isEmpty() || links.any { it.headers.isNotEmpty() } -> {
          // Text without a link, or a cURL command whose headers the add sheet keeps.
          actions.showWindow()
          state.openIntake(IntakeRequest(text = text))
        }
        else -> addNow(links.map { it.url }.distinct())
      }
    }
  }

  private fun addNow(urls: List<String>) {
    // Without a device the app asks for one, which needs the window.
    if (state.activeInstance.value == null) actions.showWindow()
    state.quickAdd(urls)
  }

  private suspend fun clipboardText(): String? = clipboard.readText()?.trim()?.ifEmpty { null }

  // Picked files go where files opened from the file manager go, so several open one by one.
  private fun openTorrentFiles() {
    actions.showWindow()
    val dialog = FileDialog(null as Frame?, "Open torrent file", FileDialog.LOAD).apply {
      isMultipleMode = true
      filenameFilter = FilenameFilter { _, name -> name.endsWith(".torrent", ignoreCase = true) }
      // Windows ignores the filter but matches the name pattern.
      if (!isMac) file = "*.torrent"
    }
    dialog.isVisible = true
    val picked = dialog.files.toList()
    dialog.dispose()
    if (picked.isNotEmpty()) actions.openFiles(picked)
  }

  private fun toggleSlowLane() {
    val speed = speedMode ?: return
    val next = if (speed.mode.value.isSlowLane) SpeedLimitMode.Full else SpeedLimitMode.SlowLane
    setSpeedMode(next)
  }

  private fun setSpeedMode(mode: SpeedLimitMode, undoable: Boolean = true) {
    val speed = speedMode ?: return
    val previous = speed.settings.value.mode
    if (mode == previous) return
    controller.scope.launch {
      try {
        speed.setMode(mode)
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        log.w { "Couldn't switch the speed mode to $mode: ${e.describeCauses()}" }
        state.messages.post(
          level = MessageLevel.Error,
          title = "Couldn't switch to ${speedModeName(mode)}",
          detail = e.message,
          cause = e,
        )
        return@launch
      }
      val title = when (mode) {
        SpeedLimitMode.SlowLane -> {
          "Slow lane on · ${formatBytes(speed.slowLaneSpeed.bytesPerSecond)}/s"
        }
        SpeedLimitMode.Full -> "Slow lane off"
        SpeedLimitMode.Auto -> "Speed follows your rules"
      }
      val undo = MessageAction("Undo") { setSpeedMode(previous, undoable = false) }
      state.messages.post(
        level = MessageLevel.Success,
        title = title,
        actions = if (undoable) listOf(undo) else emptyList(),
      )
    }
  }

  private fun togglePause() {
    val tasks = selectedTasks()
    val running = tasks.filter { TaskPhase.of(it.state.value) == TaskPhase.Running }
    if (running.isNotEmpty()) {
      running.forEach { state.runTaskCommand(it, "pause") { pause() } }
      return
    }
    for (task in tasks) {
      when (TaskPhase.of(task.state.value)) {
        TaskPhase.Paused, TaskPhase.Failed -> state.runTaskCommand(task, "resume") { resume() }
        TaskPhase.Canceled -> state.redownload(task)
        TaskPhase.Running, TaskPhase.Completed -> {}
      }
    }
  }

  private fun retrySelected() {
    for (task in selectedTasks()) {
      when (TaskPhase.of(task.state.value)) {
        TaskPhase.Paused, TaskPhase.Failed -> state.runTaskCommand(task, "retry") { resume() }
        TaskPhase.Canceled -> state.redownload(task)
        TaskPhase.Running, TaskPhase.Completed -> {}
      }
    }
  }

  // Opens the files of local completed tasks; any other task opens in the inspector.
  private fun openSelected() {
    val tasks = selectedTasks()
    val done = tasks.mapNotNull { task -> localPath(task)?.let { it to task } }
    if (done.isNotEmpty()) {
      done.forEach { (path, task) -> openFile(path, nameOf(task)) }
    } else {
      tasks.firstOrNull()?.let {
        actions.showWindow()
        state.inspect(state.keyOf(it))
      }
    }
  }

  private fun revealSelected() {
    for (task in selectedTasks()) {
      val path = localPath(task) ?: continue
      onFile("show ${nameOf(task)}") { it.reveal(path) }
    }
  }

  private fun openFile(path: String, name: String) {
    onFile("open $name") { it.open(path) }
  }

  private fun onFile(what: String, block: suspend (FileActions) -> Unit) {
    val files = files ?: return
    controller.scope.launch {
      try {
        block(files)
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        log.w { "Couldn't $what: ${e.describeCauses()}" }
        actions.showWindow()
        state.messages.post(MessageLevel.Error, "Couldn't $what", detail = e.message, cause = e)
      }
    }
  }

  private fun copyLinks() {
    val urls = selectedTasks().map { it.request.url }
    if (urls.isEmpty()) return
    controller.scope.launch {
      try {
        clipboard.writeText(urls.joinToString("\n"))
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        log.w { "Couldn't copy links: ${e.describeCauses()}" }
        state.messages.post(MessageLevel.Error, "Couldn't copy the links", cause = e)
        return@launch
      }
      val title = if (urls.size == 1) "Copied link" else "Copied ${urls.size} links"
      state.messages.post(MessageLevel.Success, title)
    }
  }

  private fun localPath(task: DownloadTask): String? {
    val done = task.state.value as? DownloadState.Completed ?: return null
    return done.outputPath.takeIf { state.keyOf(task).deviceId == LOCAL_DEVICE_ID }
  }

  private fun selectedTasks(): List<DownloadTask> {
    val entries = state.instances.value
    return state.selectedKeys.mapNotNull { key ->
      entries.firstOrNull { it.deviceId == key.deviceId }
        ?.instance?.tasks?.value?.firstOrNull { it.taskId == key.taskId }
    }
  }

  private fun nameOf(task: DownloadTask): String = displayName(task.request, task.state.value)
}

/** Name of [mode] in sentence case, as menus and messages show it. */
internal fun speedModeName(mode: SpeedLimitMode): String = when (mode) {
  SpeedLimitMode.Full -> "Full speed"
  SpeedLimitMode.SlowLane -> "Slow lane"
  SpeedLimitMode.Auto -> "Auto"
}
