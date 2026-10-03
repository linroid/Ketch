package com.linroid.ketch.app.input

import androidx.compose.ui.input.key.Key
import com.linroid.ketch.app.icons.KetchIcon
import com.linroid.ketch.app.state.StatusFilter

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
  /** How many devices, the first to the ninth listed, have [device] and [intakeTarget] chords. */
  const val NUMBERED_DEVICES: Int = 9

  /** Opens the add sheet, prefilled from the clipboard. */
  val Add: KetchCommand = KetchCommand(
    id = "add",
    label = "New download…",
    icon = KetchIcon.Plus,
    mac = KeyChord(Key.N, primary = true),
    web = listOf(KeyChord(Key.N)),
    inMenus = true,
  )

  /** Adds pasted links at once, or opens the add sheet with them. */
  val PasteLinks: KetchCommand = KetchCommand(
    id = "add.paste",
    label = "Paste links",
    icon = KetchIcon.Link,
    mac = KeyChord(Key.V, primary = true),
    inMenus = true,
    yieldsToTextField = true,
  )

  /** Adds the link on the clipboard without opening the add sheet. */
  val AddClipboardLink: KetchCommand = KetchCommand(
    id = "add.clipboard",
    label = "Download link from clipboard",
    icon = KetchIcon.Link,
    mac = KeyChord(Key.V, primary = true, shift = true),
    inMenus = true,
  )

  /** Picks .torrent files to add. */
  val OpenTorrent: KetchCommand = KetchCommand(
    id = "add.torrent",
    label = "Open torrent file…",
    icon = KetchIcon.FileTorrent,
    mac = KeyChord(Key.O, primary = true),
    inMenus = true,
  )

  /** Opens the command palette. */
  val Palette: KetchCommand = KetchCommand(
    id = "palette",
    label = "Command palette",
    icon = KetchIcon.Command,
    mac = KeyChord(Key.K, primary = true),
    web = listOf(KeyChord(Key.K, primary = true), KeyChord(Key.K)),
    inMenus = true,
  )

  /** Focuses the search field, switching to Downloads. */
  val Search: KetchCommand = KetchCommand(
    id = "search",
    label = "Search downloads",
    icon = KetchIcon.Search,
    mac = KeyChord(Key.F, primary = true),
    web = listOf(KeyChord(Key.Slash)),
    inMenus = true,
  )

  private val tabs: List<KetchCommand> = StatusFilter.entries.map { filter ->
    val digit = digitKeys[filter.ordinal + 1]
    KetchCommand(
      id = "tab.${filter.name.lowercase()}",
      label = filter.label,
      icon = tabIcon(filter),
      mac = KeyChord(digit, primary = true),
      web = listOf(KeyChord(digit, alt = true)),
      inMenus = true,
    )
  }

  /** Shows the Discover page. */
  val Discover: KetchCommand = KetchCommand(
    id = "discover",
    label = "Discover",
    icon = KetchIcon.Discover,
    mac = KeyChord(Key.E, primary = true),
    inMenus = true,
  )

  /** Shows the Devices page. */
  val Devices: KetchCommand = KetchCommand(
    id = "devices",
    label = "Devices",
    icon = KetchIcon.Devices,
    mac = KeyChord(Key.Zero, primary = true),
    inMenus = true,
  )

  private val devices: List<KetchCommand> = (1..NUMBERED_DEVICES).map { number ->
    KetchCommand(
      id = "device.$number",
      label = "Switch to device $number",
      icon = null,
      mac = KeyChord(digitKeys[number], primary = true, alt = true),
      pc = KeyChord(digitKeys[number], alt = true),
      web = listOf(KeyChord(digitKeys[number], alt = true, shift = true)),
      inMenus = true,
    )
  }

  /** Shows the downloads of every device at once. */
  val AllDevices: KetchCommand = KetchCommand(
    id = "device.all",
    label = "All devices",
    icon = KetchIcon.Fleet,
    mac = KeyChord(Key.Zero, primary = true, alt = true),
    pc = KeyChord(Key.Zero, alt = true),
    web = listOf(KeyChord(Key.Zero, alt = true, shift = true)),
    inMenus = true,
  )

  /** Opens the device switcher. */
  val SwitchDevice: KetchCommand = KetchCommand(
    id = "device.switcher",
    label = "Switch device…",
    icon = KetchIcon.Devices,
    mac = KeyChord(Key.D, primary = true, shift = true),
  )

  /** Pauses every task in scope, queued ones included. */
  val PauseAll: KetchCommand = KetchCommand(
    id = "pauseAll",
    label = "Pause all",
    icon = KetchIcon.Pause,
    mac = KeyChord(Key.P, primary = true, shift = true),
    web = listOf(KeyChord(Key.P, shift = true)),
    inMenus = true,
  )

  /** Resumes every paused task in scope. */
  val ResumeAll: KetchCommand = KetchCommand(
    id = "resumeAll",
    label = "Resume all",
    icon = KetchIcon.Play,
    mac = KeyChord(Key.R, primary = true, shift = true),
    web = listOf(KeyChord(Key.R, shift = true)),
    inMenus = true,
  )

  /** Retries every failed task in scope. */
  val RetryFailed: KetchCommand = KetchCommand(
    id = "retryFailed",
    label = "Retry all failed",
    icon = KetchIcon.Retry,
    mac = KeyChord(Key.R, primary = true, alt = true),
    inMenus = true,
  )

  /** Turns the Slow lane on or off. */
  val SlowLane: KetchCommand = KetchCommand(
    id = "slowLane",
    label = "Slow lane",
    icon = KetchIcon.SlowLane,
    mac = KeyChord(Key.L, primary = true, shift = true),
    web = listOf(KeyChord(Key.L, shift = true)),
    inMenus = true,
  )

  /** Shows the selected download in the inspector. */
  val ShowDetails: KetchCommand = KetchCommand(
    id = "inspector.show",
    label = "Show details",
    icon = KetchIcon.Info,
    mac = KeyChord(Key.I, primary = true),
    web = listOf(KeyChord(Key.I)),
    inMenus = true,
  )

  /** Collapses the sidebar to the rail, or expands it. */
  val ToggleSidebar: KetchCommand = KetchCommand(
    id = "sidebar.toggle",
    label = "Toggle sidebar",
    icon = KetchIcon.Sidebar,
    mac = KeyChord(Key.S, primary = true, ctrl = true),
    pc = KeyChord(Key.S, primary = true, shift = true),
    inMenus = true,
  )

  /** Opens the Activity popover. */
  val Activity: KetchCommand = KetchCommand(
    id = "activity",
    label = "Activity",
    icon = KetchIcon.Bell,
    mac = KeyChord(Key.J, primary = true),
    inMenus = true,
  )

  /** Undoes the last remove, clear, pause or move. */
  val Undo: KetchCommand = KetchCommand(
    id = "undo",
    label = "Undo",
    icon = KetchIcon.Undo,
    mac = KeyChord(Key.Z, primary = true),
    inMenus = true,
    yieldsToTextField = true,
  )

  /** Opens Settings. */
  val Settings: KetchCommand = KetchCommand(
    id = "settings",
    label = "Settings…",
    icon = KetchIcon.Settings,
    mac = KeyChord(Key.Comma, primary = true),
    inMenus = true,
  )

  /** Shows the keyboard shortcut sheet. */
  val Shortcuts: KetchCommand = KetchCommand(
    id = "shortcuts",
    label = "Keyboard shortcuts",
    icon = KetchIcon.Command,
    mac = KeyChord(Key.Slash, primary = true),
    web = listOf(KeyChord(Key.Slash, shift = true)),
    inMenus = true,
  )

  /** Closes the window; Ketch keeps running. */
  val CloseWindow: KetchCommand = KetchCommand(
    id = "window.close",
    label = "Close window",
    icon = KetchIcon.Close,
    mac = KeyChord(Key.W, primary = true),
    inMenus = true,
    onWeb = false,
  )

  /** Minimizes the window. Windows and Linux have no chord for it. */
  val Minimize: KetchCommand = KetchCommand(
    id = "window.minimize",
    label = "Minimize",
    icon = null,
    mac = KeyChord(Key.M, primary = true),
    pc = null,
    inMenus = true,
    onWeb = false,
  )

  /** Quits Ketch, asking first while downloads are active. */
  val Quit: KetchCommand = KetchCommand(
    id = "quit",
    label = "Quit Ketch",
    icon = null,
    mac = KeyChord(Key.Q, primary = true),
    inMenus = true,
    onWeb = false,
  )

  /** Moves the focused row up. */
  val ListUp: KetchCommand = KetchCommand(
    id = "list.up",
    label = "Move up",
    icon = null,
    scope = CommandScope.List,
    mac = KeyChord(Key.DirectionUp),
  )

  /** Moves the focused row down. */
  val ListDown: KetchCommand = KetchCommand(
    id = "list.down",
    label = "Move down",
    icon = null,
    scope = CommandScope.List,
    mac = KeyChord(Key.DirectionDown),
  )

  /** Extends the selection one row up. */
  val ExtendSelectionUp: KetchCommand = KetchCommand(
    id = "list.extendUp",
    label = "Extend selection up",
    icon = null,
    scope = CommandScope.List,
    mac = KeyChord(Key.DirectionUp, shift = true),
  )

  /** Extends the selection one row down. */
  val ExtendSelectionDown: KetchCommand = KetchCommand(
    id = "list.extendDown",
    label = "Extend selection down",
    icon = null,
    scope = CommandScope.List,
    mac = KeyChord(Key.DirectionDown, shift = true),
  )

  /** Jumps to the first row. */
  val ListFirst: KetchCommand = KetchCommand(
    id = "list.first",
    label = "Go to first",
    icon = null,
    scope = CommandScope.List,
    mac = KeyChord(Key.MoveHome),
  )

  /** Jumps to the last row. */
  val ListLast: KetchCommand = KetchCommand(
    id = "list.last",
    label = "Go to last",
    icon = null,
    scope = CommandScope.List,
    mac = KeyChord(Key.MoveEnd),
  )

  /** Jumps one page up. */
  val ListPageUp: KetchCommand = KetchCommand(
    id = "list.pageUp",
    label = "Page up",
    icon = null,
    scope = CommandScope.List,
    mac = KeyChord(Key.PageUp),
  )

  /** Jumps one page down. */
  val ListPageDown: KetchCommand = KetchCommand(
    id = "list.pageDown",
    label = "Page down",
    icon = null,
    scope = CommandScope.List,
    mac = KeyChord(Key.PageDown),
  )

  /** Selects every visible row, rows in collapsed groups included. */
  val SelectAll: KetchCommand = KetchCommand(
    id = "list.selectAll",
    label = "Select all",
    icon = KetchIcon.Check,
    scope = CommandScope.List,
    mac = KeyChord(Key.A, primary = true),
    inMenus = true,
  )

  /** Clears the selection; pressed again, moves to the search field. */
  val ClearSelection: KetchCommand = KetchCommand(
    id = "list.clearSelection",
    label = "Clear selection",
    icon = null,
    scope = CommandScope.List,
    mac = KeyChord(Key.Escape),
  )

  /** Pauses or resumes; retries a failed task and downloads a canceled one again. */
  val TogglePause: KetchCommand = KetchCommand(
    id = "list.toggle",
    label = "Pause or resume",
    icon = KetchIcon.Pause,
    scope = CommandScope.List,
    mac = KeyChord(Key.Spacebar),
    inMenus = true,
  )

  /** Opens a completed file, or the inspector of any other task. */
  val Open: KetchCommand = KetchCommand(
    id = "list.open",
    label = "Open",
    icon = KetchIcon.Open,
    scope = CommandScope.List,
    mac = KeyChord(Key.Enter),
    inMenus = true,
  )

  /** Shows the file in Finder, Explorer or the file manager. */
  val Reveal: KetchCommand = KetchCommand(
    id = "list.reveal",
    label = "Show in folder",
    icon = KetchIcon.Reveal,
    scope = CommandScope.List,
    mac = KeyChord(Key.Enter, primary = true),
    inMenus = true,
  )

  /** Removes the selected tasks from the list, keeping their files, with Undo. */
  val Remove: KetchCommand = KetchCommand(
    id = "list.remove",
    label = "Remove from list",
    icon = KetchIcon.Trash,
    scope = CommandScope.List,
    mac = KeyChord(Key.Backspace),
    pc = KeyChord(Key.Delete),
    inMenus = true,
  )

  /** Asks to remove the selected tasks and move their files to the trash. */
  val RemoveAndTrash: KetchCommand = KetchCommand(
    id = "list.removeAndTrash",
    label = "Remove and trash files…",
    icon = KetchIcon.Trash,
    scope = CommandScope.List,
    mac = KeyChord(Key.Backspace, shift = true),
    pc = KeyChord(Key.Delete, shift = true),
  )

  /** Copies the links of the selected tasks. */
  val CopyLink: KetchCommand = KetchCommand(
    id = "list.copyLink",
    label = "Copy link",
    icon = KetchIcon.Copy,
    scope = CommandScope.List,
    mac = KeyChord(Key.C, primary = true),
    inMenus = true,
  )

  /** Copies the file paths of the selected tasks. */
  val CopyPath: KetchCommand = KetchCommand(
    id = "list.copyPath",
    label = "Copy file path",
    icon = KetchIcon.Copy,
    scope = CommandScope.List,
    mac = KeyChord(Key.C, primary = true, alt = true),
  )

  /** Retries or resumes the selected tasks. */
  val Retry: KetchCommand = KetchCommand(
    id = "list.retry",
    label = "Retry",
    icon = KetchIcon.Retry,
    scope = CommandScope.List,
    mac = KeyChord(Key.R, primary = true),
    web = listOf(KeyChord(Key.R)),
    inMenus = true,
  )

  /** Adds one connection to the selected tasks. */
  val MoreConnections: KetchCommand = KetchCommand(
    id = "list.connectionsUp",
    label = "More connections",
    icon = null,
    scope = CommandScope.List,
    mac = KeyChord(Key.Equals),
  )

  /** Removes one connection from the selected tasks. */
  val FewerConnections: KetchCommand = KetchCommand(
    id = "list.connectionsDown",
    label = "Fewer connections",
    icon = null,
    scope = CommandScope.List,
    mac = KeyChord(Key.Minus),
  )

  /** Raises the priority one step, up to High; Urgent needs the menu or Start now. */
  val RaisePriority: KetchCommand = KetchCommand(
    id = "list.priorityUp",
    label = "Raise priority",
    icon = KetchIcon.ChevronUp,
    scope = CommandScope.List,
    mac = KeyChord(Key.DirectionUp, primary = true, alt = true),
  )

  /** Lowers the priority one step, down to Low. */
  val LowerPriority: KetchCommand = KetchCommand(
    id = "list.priorityDown",
    label = "Lower priority",
    icon = KetchIcon.ChevronDown,
    scope = CommandScope.List,
    mac = KeyChord(Key.DirectionDown, primary = true, alt = true),
  )

  /** Moves the focus into the inspector. */
  val FocusInspector: KetchCommand = KetchCommand(
    id = "list.toInspector",
    label = "Focus inspector",
    icon = KetchIcon.Chevron,
    scope = CommandScope.List,
    mac = KeyChord(Key.DirectionRight),
  )

  /** Moves the focus out of the inspector, back to the list. */
  val FocusList: KetchCommand = KetchCommand(
    id = "list.fromInspector",
    label = "Back to the list",
    icon = KetchIcon.ChevronLeft,
    scope = CommandScope.List,
    mac = KeyChord(Key.DirectionLeft),
  )

  /** Adds what the add sheet holds. */
  val IntakeAdd: KetchCommand = KetchCommand(
    id = "intake.add",
    label = "Add",
    icon = KetchIcon.Plus,
    scope = CommandScope.Intake,
    mac = KeyChord(Key.Enter),
  )

  /** Starts a new line in the add sheet's input. */
  val IntakeNewLine: KetchCommand = KetchCommand(
    id = "intake.newLine",
    label = "New line",
    icon = null,
    scope = CommandScope.Intake,
    mac = KeyChord(Key.Enter, shift = true),
  )

  /** Searches Discover for the add sheet's text. */
  val IntakeDiscover: KetchCommand = KetchCommand(
    id = "intake.discover",
    label = "Discover",
    icon = KetchIcon.Discover,
    scope = CommandScope.Intake,
    mac = KeyChord(Key.Enter, alt = true),
  )

  /** Picks .torrent files from the add sheet. */
  val IntakeOpenTorrent: KetchCommand = KetchCommand(
    id = "intake.torrent",
    label = "Open torrent file…",
    icon = KetchIcon.FileTorrent,
    scope = CommandScope.Intake,
    mac = KeyChord(Key.O, primary = true),
  )

  /** Closes the add sheet. */
  val IntakeClose: KetchCommand = KetchCommand(
    id = "intake.close",
    label = "Close",
    icon = KetchIcon.Close,
    scope = CommandScope.Intake,
    mac = KeyChord(Key.Escape),
  )

  private val intakeTargets: List<KetchCommand> = (1..NUMBERED_DEVICES).map { number ->
    KetchCommand(
      id = "intake.target.$number",
      label = "Add to device $number",
      icon = null,
      scope = CommandScope.Intake,
      mac = KeyChord(digitKeys[number], primary = true, alt = true),
      pc = KeyChord(digitKeys[number], alt = true),
      web = listOf(KeyChord(digitKeys[number], alt = true, shift = true)),
    )
  }

  /** Moves the palette's highlight up. */
  val PaletteUp: KetchCommand = KetchCommand(
    id = "palette.up",
    label = "Move up",
    icon = null,
    scope = CommandScope.Palette,
    mac = KeyChord(Key.DirectionUp),
  )

  /** Moves the palette's highlight down. */
  val PaletteDown: KetchCommand = KetchCommand(
    id = "palette.down",
    label = "Move down",
    icon = null,
    scope = CommandScope.Palette,
    mac = KeyChord(Key.DirectionDown),
  )

  /** Runs the highlighted palette row. */
  val PaletteRun: KetchCommand = KetchCommand(
    id = "palette.run",
    label = "Run",
    icon = null,
    scope = CommandScope.Palette,
    mac = KeyChord(Key.Enter),
  )

  /** Runs the alternate action of the highlighted palette row, such as Add with options. */
  val PaletteAlternate: KetchCommand = KetchCommand(
    id = "palette.alternate",
    label = "Run alternate action",
    icon = null,
    scope = CommandScope.Palette,
    mac = KeyChord(Key.Enter, primary = true),
  )

  /** Searches Discover for the palette's text. */
  val PaletteDiscover: KetchCommand = KetchCommand(
    id = "palette.discover",
    label = "Discover",
    icon = KetchIcon.Discover,
    scope = CommandScope.Palette,
    mac = KeyChord(Key.Enter, alt = true),
  )

  /** Closes the palette. */
  val PaletteClose: KetchCommand = KetchCommand(
    id = "palette.close",
    label = "Close",
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

  /** The command that shows the [filter] tab: ⌘1 for All through ⌘6 for Failed. */
  fun tab(filter: StatusFilter): KetchCommand = tabs[filter.ordinal]

  /** The filter whose tab [command] shows, or `null` when it is not a [tab] command. */
  fun tabFilter(command: KetchCommand): StatusFilter? =
    StatusFilter.entries.getOrNull(tabs.indexOf(command))

  /** The command that switches to the device listed [number]th, 1 to 9. */
  fun device(number: Int): KetchCommand {
    require(number in 1..NUMBERED_DEVICES) { "Device number must be 1 to 9, was $number" }
    return devices[number - 1]
  }

  /** The [device] command of [number], or `null` for a device listed past the ninth. */
  fun deviceOrNull(number: Int): KetchCommand? = devices.getOrNull(number - 1)

  /** The number of the device [command] switches to, or `null` when it is not a [device]. */
  fun deviceNumber(command: KetchCommand): Int? = numberOf(devices, command)

  /** The command that makes the device listed [number]th, 1 to 9, the add sheet's target. */
  fun intakeTarget(number: Int): KetchCommand {
    require(number in 1..NUMBERED_DEVICES) { "Device number must be 1 to 9, was $number" }
    return intakeTargets[number - 1]
  }

  /** The number of the device [command] makes the target, or `null` for other commands. */
  fun intakeTargetNumber(command: KetchCommand): Int? = numberOf(intakeTargets, command)

  private fun numberOf(commands: List<KetchCommand>, command: KetchCommand): Int? =
    (commands.indexOf(command) + 1).takeIf { it > 0 }
}

private fun tabIcon(filter: StatusFilter): KetchIcon = when (filter) {
  StatusFilter.All -> KetchIcon.All
  StatusFilter.Downloading -> KetchIcon.Active
  StatusFilter.Waiting -> KetchIcon.Queued
  StatusFilter.Paused -> KetchIcon.Pause
  StatusFilter.Done -> KetchIcon.Done
  StatusFilter.Failed -> KetchIcon.Failed
}
