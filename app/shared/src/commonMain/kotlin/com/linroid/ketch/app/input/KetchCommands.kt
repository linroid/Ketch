package com.linroid.ketch.app.input

import androidx.compose.ui.input.key.Key
import com.linroid.ketch.app.i18n.UiText
import com.linroid.ketch.app.i18n.text
import com.linroid.ketch.app.icons.KetchIcon
import com.linroid.ketch.app.state.StatusFilter
import ketch.app.shared.generated.resources.Res
import ketch.app.shared.generated.resources.command_activity
import ketch.app.shared.generated.resources.command_add
import ketch.app.shared.generated.resources.command_add_clipboard
import ketch.app.shared.generated.resources.command_add_paste
import ketch.app.shared.generated.resources.command_add_to_device
import ketch.app.shared.generated.resources.command_add_torrent
import ketch.app.shared.generated.resources.command_device_all
import ketch.app.shared.generated.resources.command_device_switcher
import ketch.app.shared.generated.resources.command_devices
import ketch.app.shared.generated.resources.command_discover
import ketch.app.shared.generated.resources.command_inspector_show
import ketch.app.shared.generated.resources.command_intake_add
import ketch.app.shared.generated.resources.command_intake_close
import ketch.app.shared.generated.resources.command_intake_discover
import ketch.app.shared.generated.resources.command_intake_new_line
import ketch.app.shared.generated.resources.command_intake_torrent
import ketch.app.shared.generated.resources.command_list_clear_selection
import ketch.app.shared.generated.resources.command_list_connections_down
import ketch.app.shared.generated.resources.command_list_connections_up
import ketch.app.shared.generated.resources.command_list_copy_link
import ketch.app.shared.generated.resources.command_list_copy_path
import ketch.app.shared.generated.resources.command_list_down
import ketch.app.shared.generated.resources.command_list_extend_down
import ketch.app.shared.generated.resources.command_list_extend_up
import ketch.app.shared.generated.resources.command_list_first
import ketch.app.shared.generated.resources.command_list_from_inspector
import ketch.app.shared.generated.resources.command_list_last
import ketch.app.shared.generated.resources.command_list_open
import ketch.app.shared.generated.resources.command_list_page_down
import ketch.app.shared.generated.resources.command_list_page_up
import ketch.app.shared.generated.resources.command_list_priority_down
import ketch.app.shared.generated.resources.command_list_priority_up
import ketch.app.shared.generated.resources.command_list_remove
import ketch.app.shared.generated.resources.command_list_remove_and_trash
import ketch.app.shared.generated.resources.command_list_retry
import ketch.app.shared.generated.resources.command_list_reveal
import ketch.app.shared.generated.resources.command_list_select_all
import ketch.app.shared.generated.resources.command_list_to_inspector
import ketch.app.shared.generated.resources.command_list_toggle
import ketch.app.shared.generated.resources.command_list_up
import ketch.app.shared.generated.resources.command_palette
import ketch.app.shared.generated.resources.command_palette_alternate
import ketch.app.shared.generated.resources.command_palette_close
import ketch.app.shared.generated.resources.command_palette_discover
import ketch.app.shared.generated.resources.command_palette_down
import ketch.app.shared.generated.resources.command_palette_run
import ketch.app.shared.generated.resources.command_palette_up
import ketch.app.shared.generated.resources.command_pause_all
import ketch.app.shared.generated.resources.command_quit
import ketch.app.shared.generated.resources.command_resume_all
import ketch.app.shared.generated.resources.command_retry_failed
import ketch.app.shared.generated.resources.command_search
import ketch.app.shared.generated.resources.command_settings
import ketch.app.shared.generated.resources.command_shortcuts
import ketch.app.shared.generated.resources.command_sidebar_toggle
import ketch.app.shared.generated.resources.command_slow_lane
import ketch.app.shared.generated.resources.command_switch_to_device
import ketch.app.shared.generated.resources.command_undo
import ketch.app.shared.generated.resources.command_window_close
import ketch.app.shared.generated.resources.command_window_minimize

/**
 * Every command of the app, the one source for shortcuts, the menu bar, the tray, the command
 * palette, tooltips and the shortcut sheet.
 *
 * Chords are written for Apple keyboards. Windows and Linux press Ctrl for ⌘ and Alt for ⌥
 * unless a command gives its own PC chord. The web app uses the chords of the browser's
 * platform, except where the browser keeps a chord for itself (⌘N, ⌘R, ⇧⌘P, ⌘1…); those
 * commands have web keys of their own, such as `n` or ⌥1. A global key that would type a
 * character never runs while a text field has focus.
 */
object KetchCommands {
  /** Opens the add sheet, prefilled from the clipboard. */
  val Add: KetchCommand = command(
    id = "add",
    label = Res.string.command_add.text(),
    icon = KetchIcon.Plus,
    mac = KeyChord(Key.N, primary = true),
    web = listOf(KeyChord(Key.N)),
    inMenus = true,
  )

  /** Adds pasted links at once, or opens the add sheet with them. */
  val PasteLinks: KetchCommand = command(
    id = "add.paste",
    label = Res.string.command_add_paste.text(),
    icon = KetchIcon.Link,
    mac = KeyChord(Key.V, primary = true),
    inMenus = true,
    yieldsToTextField = true,
  )

  /** Adds the link on the clipboard without opening the add sheet. */
  val AddClipboardLink: KetchCommand = command(
    id = "add.clipboard",
    label = Res.string.command_add_clipboard.text(),
    icon = KetchIcon.Link,
    mac = KeyChord(Key.V, primary = true, shift = true),
    inMenus = true,
  )

  /** Picks .torrent files to add. */
  val OpenTorrent: KetchCommand = command(
    id = "add.torrent",
    label = Res.string.command_add_torrent.text(),
    icon = KetchIcon.FileTorrent,
    mac = KeyChord(Key.O, primary = true),
    inMenus = true,
  )

  /** Opens the command palette. */
  val Palette: KetchCommand = command(
    id = "palette",
    label = Res.string.command_palette.text(),
    icon = KetchIcon.Command,
    mac = KeyChord(Key.K, primary = true),
    web = listOf(KeyChord(Key.K, primary = true), KeyChord(Key.K)),
    inMenus = true,
  )

  /** Focuses the search field, switching to Downloads. */
  val Search: KetchCommand = command(
    id = "search",
    label = Res.string.command_search.text(),
    icon = KetchIcon.Search,
    mac = KeyChord(Key.F, primary = true),
    web = listOf(KeyChord(Key.Slash)),
    inMenus = true,
  )

  private val tabs: List<KetchCommand> = StatusFilter.entries.map { filter ->
    val digit = digitKeys[filter.ordinal + 1]
    command(
      id = "tab.${filter.name.lowercase()}",
      label = filter.label,
      icon = tabIcon(filter),
      mac = KeyChord(digit, primary = true),
      web = listOf(KeyChord(digit, alt = true)),
      inMenus = true,
    )
  }

  /** Shows the Discover page. */
  val Discover: KetchCommand = command(
    id = "discover",
    label = Res.string.command_discover.text(),
    icon = KetchIcon.Discover,
    mac = KeyChord(Key.E, primary = true),
    inMenus = true,
  )

  /** Shows the Devices page. */
  val Devices: KetchCommand = command(
    id = "devices",
    label = Res.string.command_devices.text(),
    icon = KetchIcon.Devices,
    mac = KeyChord(Key.Zero, primary = true),
    inMenus = true,
  )

  private val devices: List<KetchCommand> = (1..9).map { number ->
    command(
      id = "device.$number",
      label = Res.string.command_switch_to_device.text(number),
      icon = null,
      mac = KeyChord(digitKeys[number], primary = true, alt = true),
      pc = KeyChord(digitKeys[number], alt = true),
      web = listOf(KeyChord(digitKeys[number], alt = true, shift = true)),
      inMenus = true,
    )
  }

  /** Shows the downloads of every device at once. */
  val AllDevices: KetchCommand = command(
    id = "device.all",
    label = Res.string.command_device_all.text(),
    icon = KetchIcon.Fleet,
    mac = KeyChord(Key.Zero, primary = true, alt = true),
    pc = KeyChord(Key.Zero, alt = true),
    web = listOf(KeyChord(Key.Zero, alt = true, shift = true)),
    inMenus = true,
  )

  /** Opens the device switcher. */
  val SwitchDevice: KetchCommand = command(
    id = "device.switcher",
    label = Res.string.command_device_switcher.text(),
    icon = KetchIcon.Devices,
    mac = KeyChord(Key.D, primary = true, shift = true),
  )

  /** Pauses every task in scope, queued ones included. */
  val PauseAll: KetchCommand = command(
    id = "pauseAll",
    label = Res.string.command_pause_all.text(),
    icon = KetchIcon.Pause,
    mac = KeyChord(Key.P, primary = true, shift = true),
    web = listOf(KeyChord(Key.P, shift = true)),
    inMenus = true,
  )

  /** Resumes every paused task in scope. */
  val ResumeAll: KetchCommand = command(
    id = "resumeAll",
    label = Res.string.command_resume_all.text(),
    icon = KetchIcon.Play,
    mac = KeyChord(Key.R, primary = true, shift = true),
    web = listOf(KeyChord(Key.R, shift = true)),
    inMenus = true,
  )

  /** Retries every failed task in scope. */
  val RetryFailed: KetchCommand = command(
    id = "retryFailed",
    label = Res.string.command_retry_failed.text(),
    icon = KetchIcon.Retry,
    mac = KeyChord(Key.R, primary = true, alt = true),
    inMenus = true,
  )

  /** Turns the Slow lane on or off. */
  val SlowLane: KetchCommand = command(
    id = "slowLane",
    label = Res.string.command_slow_lane.text(),
    icon = KetchIcon.SlowLane,
    mac = KeyChord(Key.L, primary = true, shift = true),
    web = listOf(KeyChord(Key.L, shift = true)),
    inMenus = true,
  )

  /** Shows the selected download in the inspector. */
  val ShowDetails: KetchCommand = command(
    id = "inspector.show",
    label = Res.string.command_inspector_show.text(),
    icon = KetchIcon.Info,
    mac = KeyChord(Key.I, primary = true),
    web = listOf(KeyChord(Key.I)),
    inMenus = true,
  )

  /** Collapses the sidebar to the rail, or expands it. */
  val ToggleSidebar: KetchCommand = command(
    id = "sidebar.toggle",
    label = Res.string.command_sidebar_toggle.text(),
    icon = KetchIcon.Sidebar,
    mac = KeyChord(Key.S, primary = true, ctrl = true),
    pc = KeyChord(Key.S, primary = true, shift = true),
    inMenus = true,
  )

  /** Opens the Activity popover. */
  val Activity: KetchCommand = command(
    id = "activity",
    label = Res.string.command_activity.text(),
    icon = KetchIcon.Bell,
    mac = KeyChord(Key.J, primary = true),
    inMenus = true,
  )

  /** Undoes the last remove, clear, pause or move. */
  val Undo: KetchCommand = command(
    id = "undo",
    label = Res.string.command_undo.text(),
    icon = KetchIcon.Undo,
    mac = KeyChord(Key.Z, primary = true),
    inMenus = true,
    yieldsToTextField = true,
  )

  /** Opens Settings. */
  val Settings: KetchCommand = command(
    id = "settings",
    label = Res.string.command_settings.text(),
    icon = KetchIcon.Settings,
    mac = KeyChord(Key.Comma, primary = true),
    inMenus = true,
  )

  /** Shows the keyboard shortcut sheet. */
  val Shortcuts: KetchCommand = command(
    id = "shortcuts",
    label = Res.string.command_shortcuts.text(),
    icon = KetchIcon.Command,
    mac = KeyChord(Key.Slash, primary = true),
    web = listOf(KeyChord(Key.Slash, shift = true)),
    inMenus = true,
  )

  /** Closes the window; Ketch keeps running. */
  val CloseWindow: KetchCommand = command(
    id = "window.close",
    label = Res.string.command_window_close.text(),
    icon = KetchIcon.Close,
    mac = KeyChord(Key.W, primary = true),
    inMenus = true,
    onWeb = false,
  )

  /** Minimizes the window. Windows and Linux have no chord for it. */
  val Minimize: KetchCommand = command(
    id = "window.minimize",
    label = Res.string.command_window_minimize.text(),
    icon = null,
    mac = KeyChord(Key.M, primary = true),
    pc = null,
    inMenus = true,
    onWeb = false,
  )

  /** Quits Ketch, asking first while downloads are active. */
  val Quit: KetchCommand = command(
    id = "quit",
    label = Res.string.command_quit.text(),
    icon = null,
    mac = KeyChord(Key.Q, primary = true),
    inMenus = true,
    onWeb = false,
  )

  /** Moves the focused row up. */
  val ListUp: KetchCommand = command(
    id = "list.up",
    label = Res.string.command_list_up.text(),
    icon = null,
    scope = CommandScope.List,
    mac = KeyChord(Key.DirectionUp),
  )

  /** Moves the focused row down. */
  val ListDown: KetchCommand = command(
    id = "list.down",
    label = Res.string.command_list_down.text(),
    icon = null,
    scope = CommandScope.List,
    mac = KeyChord(Key.DirectionDown),
  )

  /** Extends the selection one row up. */
  val ExtendSelectionUp: KetchCommand = command(
    id = "list.extendUp",
    label = Res.string.command_list_extend_up.text(),
    icon = null,
    scope = CommandScope.List,
    mac = KeyChord(Key.DirectionUp, shift = true),
  )

  /** Extends the selection one row down. */
  val ExtendSelectionDown: KetchCommand = command(
    id = "list.extendDown",
    label = Res.string.command_list_extend_down.text(),
    icon = null,
    scope = CommandScope.List,
    mac = KeyChord(Key.DirectionDown, shift = true),
  )

  /** Jumps to the first row. */
  val ListFirst: KetchCommand = command(
    id = "list.first",
    label = Res.string.command_list_first.text(),
    icon = null,
    scope = CommandScope.List,
    mac = KeyChord(Key.MoveHome),
  )

  /** Jumps to the last row. */
  val ListLast: KetchCommand = command(
    id = "list.last",
    label = Res.string.command_list_last.text(),
    icon = null,
    scope = CommandScope.List,
    mac = KeyChord(Key.MoveEnd),
  )

  /** Jumps one page up. */
  val ListPageUp: KetchCommand = command(
    id = "list.pageUp",
    label = Res.string.command_list_page_up.text(),
    icon = null,
    scope = CommandScope.List,
    mac = KeyChord(Key.PageUp),
  )

  /** Jumps one page down. */
  val ListPageDown: KetchCommand = command(
    id = "list.pageDown",
    label = Res.string.command_list_page_down.text(),
    icon = null,
    scope = CommandScope.List,
    mac = KeyChord(Key.PageDown),
  )

  /** Selects every visible row, rows in collapsed groups included. */
  val SelectAll: KetchCommand = command(
    id = "list.selectAll",
    label = Res.string.command_list_select_all.text(),
    icon = KetchIcon.Check,
    scope = CommandScope.List,
    mac = KeyChord(Key.A, primary = true),
    inMenus = true,
  )

  /** Clears the selection; pressed again, moves to the search field. */
  val ClearSelection: KetchCommand = command(
    id = "list.clearSelection",
    label = Res.string.command_list_clear_selection.text(),
    icon = null,
    scope = CommandScope.List,
    mac = KeyChord(Key.Escape),
  )

  /** Pauses or resumes; retries a failed task and downloads a canceled one again. */
  val TogglePause: KetchCommand = command(
    id = "list.toggle",
    label = Res.string.command_list_toggle.text(),
    icon = KetchIcon.Pause,
    scope = CommandScope.List,
    mac = KeyChord(Key.Spacebar),
    inMenus = true,
  )

  /** Opens a completed file, or the inspector of any other task. */
  val Open: KetchCommand = command(
    id = "list.open",
    label = Res.string.command_list_open.text(),
    icon = KetchIcon.Open,
    scope = CommandScope.List,
    mac = KeyChord(Key.Enter),
    inMenus = true,
  )

  /** Shows the file in Finder, Explorer or the file manager. */
  val Reveal: KetchCommand = command(
    id = "list.reveal",
    label = Res.string.command_list_reveal.text(),
    icon = KetchIcon.Reveal,
    scope = CommandScope.List,
    mac = KeyChord(Key.Enter, primary = true),
    inMenus = true,
  )

  /** Removes the selected tasks from the list, keeping their files, with Undo. */
  val Remove: KetchCommand = command(
    id = "list.remove",
    label = Res.string.command_list_remove.text(),
    icon = KetchIcon.Trash,
    scope = CommandScope.List,
    mac = KeyChord(Key.Backspace),
    pc = KeyChord(Key.Delete),
    inMenus = true,
  )

  /** Asks to remove the selected tasks and move their files to the trash. */
  val RemoveAndTrash: KetchCommand = command(
    id = "list.removeAndTrash",
    label = Res.string.command_list_remove_and_trash.text(),
    icon = KetchIcon.Trash,
    scope = CommandScope.List,
    mac = KeyChord(Key.Backspace, shift = true),
    pc = KeyChord(Key.Delete, shift = true),
  )

  /** Copies the links of the selected tasks. */
  val CopyLink: KetchCommand = command(
    id = "list.copyLink",
    label = Res.string.command_list_copy_link.text(),
    icon = KetchIcon.Copy,
    scope = CommandScope.List,
    mac = KeyChord(Key.C, primary = true),
    inMenus = true,
  )

  /** Copies the file paths of the selected tasks. */
  val CopyPath: KetchCommand = command(
    id = "list.copyPath",
    label = Res.string.command_list_copy_path.text(),
    icon = KetchIcon.Copy,
    scope = CommandScope.List,
    mac = KeyChord(Key.C, primary = true, alt = true),
  )

  /** Retries or resumes the selected tasks. */
  val Retry: KetchCommand = command(
    id = "list.retry",
    label = Res.string.command_list_retry.text(),
    icon = KetchIcon.Retry,
    scope = CommandScope.List,
    mac = KeyChord(Key.R, primary = true),
    web = listOf(KeyChord(Key.R)),
    inMenus = true,
  )

  /** Adds one connection to the selected tasks. */
  val MoreConnections: KetchCommand = command(
    id = "list.connectionsUp",
    label = Res.string.command_list_connections_up.text(),
    icon = null,
    scope = CommandScope.List,
    mac = KeyChord(Key.Equals),
  )

  /** Removes one connection from the selected tasks. */
  val FewerConnections: KetchCommand = command(
    id = "list.connectionsDown",
    label = Res.string.command_list_connections_down.text(),
    icon = null,
    scope = CommandScope.List,
    mac = KeyChord(Key.Minus),
  )

  /** Raises the priority one step, up to High; Urgent needs the menu or Start now. */
  val RaisePriority: KetchCommand = command(
    id = "list.priorityUp",
    label = Res.string.command_list_priority_up.text(),
    icon = KetchIcon.ChevronUp,
    scope = CommandScope.List,
    mac = KeyChord(Key.DirectionUp, primary = true, alt = true),
  )

  /** Lowers the priority one step, down to Low. */
  val LowerPriority: KetchCommand = command(
    id = "list.priorityDown",
    label = Res.string.command_list_priority_down.text(),
    icon = KetchIcon.ChevronDown,
    scope = CommandScope.List,
    mac = KeyChord(Key.DirectionDown, primary = true, alt = true),
  )

  /** Moves the focus into the inspector. */
  val FocusInspector: KetchCommand = command(
    id = "list.toInspector",
    label = Res.string.command_list_to_inspector.text(),
    icon = KetchIcon.Chevron,
    scope = CommandScope.List,
    mac = KeyChord(Key.DirectionRight),
  )

  /** Moves the focus out of the inspector, back to the list. */
  val FocusList: KetchCommand = command(
    id = "list.fromInspector",
    label = Res.string.command_list_from_inspector.text(),
    icon = KetchIcon.ChevronLeft,
    scope = CommandScope.List,
    mac = KeyChord(Key.DirectionLeft),
  )

  /** Adds what the add sheet holds. */
  val IntakeAdd: KetchCommand = command(
    id = "intake.add",
    label = Res.string.command_intake_add.text(),
    icon = KetchIcon.Plus,
    scope = CommandScope.Intake,
    mac = KeyChord(Key.Enter),
  )

  /** Starts a new line in the add sheet's input. */
  val IntakeNewLine: KetchCommand = command(
    id = "intake.newLine",
    label = Res.string.command_intake_new_line.text(),
    icon = null,
    scope = CommandScope.Intake,
    mac = KeyChord(Key.Enter, shift = true),
  )

  /** Searches Discover for the add sheet's text. */
  val IntakeDiscover: KetchCommand = command(
    id = "intake.discover",
    label = Res.string.command_intake_discover.text(),
    icon = KetchIcon.Discover,
    scope = CommandScope.Intake,
    mac = KeyChord(Key.Enter, alt = true),
  )

  /** Picks .torrent files from the add sheet. */
  val IntakeOpenTorrent: KetchCommand = command(
    id = "intake.torrent",
    label = Res.string.command_intake_torrent.text(),
    icon = KetchIcon.FileTorrent,
    scope = CommandScope.Intake,
    mac = KeyChord(Key.O, primary = true),
  )

  /** Closes the add sheet. */
  val IntakeClose: KetchCommand = command(
    id = "intake.close",
    label = Res.string.command_intake_close.text(),
    icon = KetchIcon.Close,
    scope = CommandScope.Intake,
    mac = KeyChord(Key.Escape),
  )

  private val intakeTargets: List<KetchCommand> = (1..9).map { number ->
    command(
      id = "intake.target.$number",
      label = Res.string.command_add_to_device.text(number),
      icon = null,
      scope = CommandScope.Intake,
      mac = KeyChord(digitKeys[number], primary = true, alt = true),
      pc = KeyChord(digitKeys[number], alt = true),
      web = listOf(KeyChord(digitKeys[number], alt = true, shift = true)),
    )
  }

  /** Moves the palette's highlight up. */
  val PaletteUp: KetchCommand = command(
    id = "palette.up",
    label = Res.string.command_palette_up.text(),
    icon = null,
    scope = CommandScope.Palette,
    mac = KeyChord(Key.DirectionUp),
  )

  /** Moves the palette's highlight down. */
  val PaletteDown: KetchCommand = command(
    id = "palette.down",
    label = Res.string.command_palette_down.text(),
    icon = null,
    scope = CommandScope.Palette,
    mac = KeyChord(Key.DirectionDown),
  )

  /** Runs the highlighted palette row. */
  val PaletteRun: KetchCommand = command(
    id = "palette.run",
    label = Res.string.command_palette_run.text(),
    icon = null,
    scope = CommandScope.Palette,
    mac = KeyChord(Key.Enter),
  )

  /** Runs the alternate action of the highlighted palette row, such as Add with options. */
  val PaletteAlternate: KetchCommand = command(
    id = "palette.alternate",
    label = Res.string.command_palette_alternate.text(),
    icon = null,
    scope = CommandScope.Palette,
    mac = KeyChord(Key.Enter, primary = true),
  )

  /** Searches Discover for the palette's text. */
  val PaletteDiscover: KetchCommand = command(
    id = "palette.discover",
    label = Res.string.command_palette_discover.text(),
    icon = KetchIcon.Discover,
    scope = CommandScope.Palette,
    mac = KeyChord(Key.Enter, alt = true),
  )

  /** Closes the palette. */
  val PaletteClose: KetchCommand = command(
    id = "palette.close",
    label = Res.string.command_palette_close.text(),
    icon = KetchIcon.Close,
    scope = CommandScope.Palette,
    mac = KeyChord(Key.Escape),
  )

  /** Every command, grouped by scope in the order the shortcut sheet lists them. */
  val all: List<KetchCommand> = buildList {
    addAll(listOf(Add, PasteLinks, AddClipboardLink, OpenTorrent, Palette, Search))
    addAll(tabs)
    addAll(listOf(Discover, Devices))
    addAll(devices)
    addAll(listOf(AllDevices, SwitchDevice, PauseAll, ResumeAll, RetryFailed, SlowLane))
    addAll(listOf(ShowDetails, ToggleSidebar, Activity, Undo, Settings, Shortcuts))
    addAll(listOf(CloseWindow, Minimize, Quit))
    addAll(listOf(ListUp, ListDown, ExtendSelectionUp, ExtendSelectionDown))
    addAll(listOf(ListFirst, ListLast, ListPageUp, ListPageDown, SelectAll, ClearSelection))
    addAll(listOf(TogglePause, Open, Reveal, Remove, RemoveAndTrash, CopyLink, CopyPath))
    addAll(listOf(Retry, MoreConnections, FewerConnections, RaisePriority, LowerPriority))
    addAll(listOf(FocusInspector, FocusList))
    addAll(listOf(IntakeAdd, IntakeNewLine, IntakeDiscover, IntakeOpenTorrent, IntakeClose))
    addAll(intakeTargets)
    addAll(listOf(PaletteUp, PaletteDown, PaletteRun, PaletteAlternate, PaletteDiscover))
    add(PaletteClose)
  }

  private val commandsById: Map<String, KetchCommand> = all.associateBy { it.id }

  /** The command that shows the [filter] tab: ⌘1 for All through ⌘6 for Failed. */
  fun tab(filter: StatusFilter): KetchCommand = tabs[filter.ordinal]

  /** The command that switches to the device listed [number]th, 1 to 9. */
  fun device(number: Int): KetchCommand {
    require(number in 1..9) { "Device number must be 1 to 9, was $number" }
    return devices[number - 1]
  }

  /** The command that makes the device listed [number]th, 1 to 9, the add sheet's target. */
  fun intakeTarget(number: Int): KetchCommand {
    require(number in 1..9) { "Device number must be 1 to 9, was $number" }
    return intakeTargets[number - 1]
  }

  /** The command with [id], or `null` when there is none, such as a stale palette history id. */
  fun byId(id: String): KetchCommand? = commandsById[id]
}

private fun command(
  id: String,
  label: UiText,
  icon: KetchIcon?,
  mac: KeyChord?,
  pc: KeyChord? = mac,
  web: List<KeyChord>? = null,
  scope: CommandScope = CommandScope.Global,
  inMenus: Boolean = false,
  onWeb: Boolean = true,
  yieldsToTextField: Boolean = false,
): KetchCommand = KetchCommand(
  id = id,
  label = label,
  icon = icon,
  scope = scope,
  mac = mac,
  pc = pc,
  web = web,
  inMenus = inMenus,
  onWeb = onWeb,
  yieldsToTextField = yieldsToTextField,
)

private fun tabIcon(filter: StatusFilter): KetchIcon = when (filter) {
  StatusFilter.All -> KetchIcon.All
  StatusFilter.Downloading -> KetchIcon.Active
  StatusFilter.Waiting -> KetchIcon.Queued
  StatusFilter.Paused -> KetchIcon.Pause
  StatusFilter.Done -> KetchIcon.Done
  StatusFilter.Failed -> KetchIcon.Failed
}
