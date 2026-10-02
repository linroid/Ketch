package com.linroid.ketch.app.ui.palette

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import androidx.window.core.layout.WindowSizeClass
import com.linroid.ketch.api.SpeedLimit
import com.linroid.ketch.app.components.DevicePennant
import com.linroid.ketch.app.components.DevicePennantDefaults
import com.linroid.ketch.app.components.KetchButtonSize
import com.linroid.ketch.app.components.KetchEyebrow
import com.linroid.ketch.app.components.KetchFileTypeChip
import com.linroid.ketch.app.components.KetchFileTypeChipDefaults
import com.linroid.ketch.app.components.KetchIconButton
import com.linroid.ketch.app.components.ketchClickable
import com.linroid.ketch.app.icons.KetchIcon
import com.linroid.ketch.app.icons.KetchIconImage
import com.linroid.ketch.app.input.CommandScope
import com.linroid.ketch.app.input.KetchCommand
import com.linroid.ketch.app.input.KetchCommands
import com.linroid.ketch.app.input.KeyboardPlatform
import com.linroid.ketch.app.input.ShortcutContext
import com.linroid.ketch.app.instance.DevicePresence
import com.linroid.ketch.app.instance.displayName
import com.linroid.ketch.app.platform.rememberFileActions
import com.linroid.ketch.app.platform.rememberSystemClipboard
import com.linroid.ketch.app.state.AppDestination
import com.linroid.ketch.app.state.AppState
import com.linroid.ketch.app.state.DeviceHealth
import com.linroid.ketch.app.state.LocalClock
import com.linroid.ketch.app.state.SettingsCategory
import com.linroid.ketch.app.state.SpeedMode
import com.linroid.ketch.app.state.deviceId
import com.linroid.ketch.app.state.isSlowLane
import com.linroid.ketch.app.theme.KetchElevationLevel
import com.linroid.ketch.app.theme.KetchTheme
import com.linroid.ketch.app.theme.ketchSurface
import com.linroid.ketch.app.ui.list.FileNameText
import com.linroid.ketch.app.ui.list.RowCommands
import com.linroid.ketch.app.ui.pulse.activeSpeedMode
import com.linroid.ketch.app.ui.pulse.slowLaneLimit
import com.linroid.ketch.app.ui.shell.KeyCap
import com.linroid.ketch.app.ui.shell.LocalHostShortcuts
import com.linroid.ketch.app.ui.shell.shellShortcuts
import com.linroid.ketch.app.util.formatBytes
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.datetime.TimeZone

/** Sizes of the command palette. */
internal object PaletteDefaults {
  /** Space between the top of the window and the palette, except on a phone. */
  val TopOffset: Dp = 72.dp

  /** Widest the palette grows. */
  val MaxWidth: Dp = 600.dp

  /** Height of the input. */
  val InputHeight: Dp = 48.dp

  /** Height of a row with a pointer; touch rows are a menu item tall. */
  val RowHeight: Dp = 40.dp

  /** Height of a heading over rows. */
  val HeaderHeight: Dp = 28.dp

  /** Height of the key hints at the bottom. */
  val FooterHeight: Dp = 28.dp

  /** Width of hairline dividers. */
  val Hairline: Dp = 1.dp

  /** Most rows shown before the list scrolls. */
  const val VISIBLE_ROWS: Int = 9

  /** Share of a row's text width a subtitle keeps while the title is long. */
  const val SUBTITLE_SHARE: Float = 0.4f

  /** Scale the palette grows from as it opens. */
  const val ENTER_SCALE: Float = 0.98f
}

/**
 * The command palette (`⌘K`): one field that downloads pasted links on any device, sets a
 * typed speed such as "5m", runs commands with live counts, finds downloads by name and search
 * tokens, jumps to tabs, destinations, devices and Settings pages ("/speed"), and falls back to
 * filtering the list or searching Discover. ↑↓ move, ↩ runs, ⌘↩ runs the alternate action,
 * ⌥↩ searches Discover and Esc closes; ⌥⌘1… download a link on that device.
 *
 * It hangs below the top of the window over a scrim; clicking outside closes it, and so does the
 * add sheet opening.
 *
 * @param onCommand runs a global command as its shortcut does; returns whether it ran.
 * @param canRun whether the window runs a global command, so the palette offers it.
 * @param destinations the destinations the navigation offers.
 * @param initialQuery text the field starts with, selected, such as the Downloads search.
 * @param history the rows run last, listed first.
 */
@Composable
internal fun CommandPalette(
  state: AppState,
  onDismiss: () -> Unit,
  onCommand: (KetchCommand) -> Boolean,
  canRun: (KetchCommand) -> Boolean,
  destinations: List<AppDestination>,
  initialQuery: String = "",
  history: PaletteHistory = PaletteHistory.Default,
) {
  val dismiss by rememberUpdatedState(onDismiss)
  val window = LocalWindowInfo.current.containerSize
  val density = LocalDensity.current
  val width = with(density) { window.width.toDp() }
  val compact = width.value < WindowSizeClass.WIDTH_DP_MEDIUM_LOWER_BOUND
  val spacing = KetchTheme.spacing
  val appear = remember { Animatable(0f) }
  val motion = KetchTheme.motion
  LaunchedEffect(appear) {
    appear.animateTo(1f, tween(motion.short, easing = motion.easeDecelerate))
  }
  LaunchedEffect(state) {
    // The add sheet takes over, also when the menu bar opens it under the palette.
    snapshotFlow { state.showAddDialog }.first { it }
    dismiss()
  }
  Popup(
    popupPositionProvider = WindowOrigin,
    onDismissRequest = { dismiss() },
    properties = PopupProperties(
      focusable = true,
      dismissOnBackPress = true,
      dismissOnClickOutside = false,
    ),
  ) {
    Box(
      contentAlignment = Alignment.TopCenter,
      modifier = Modifier
        .fillMaxSize()
        .graphicsLayer { alpha = appear.value }
        .background(KetchTheme.colors.scrim)
        .pointerInput(Unit) { detectTapGestures { dismiss() } }
        .windowInsetsPadding(WindowInsets.safeDrawing)
        .imePadding(),
    ) {
      PalettePanel(
        state = state,
        onDismiss = { dismiss() },
        onCommand = onCommand,
        canRun = canRun,
        destinations = destinations,
        initialQuery = initialQuery,
        history = history,
        wide = !compact,
        modifier = Modifier
          .padding(top = if (compact) spacing.s2 else PaletteDefaults.TopOffset)
          .padding(horizontal = spacing.s4)
          .padding(bottom = spacing.s4)
          .widthIn(max = PaletteDefaults.MaxWidth)
          .fillMaxWidth()
          .graphicsLayer {
            val grown = (1 - PaletteDefaults.ENTER_SCALE) * appear.value
            scaleX = PaletteDefaults.ENTER_SCALE + grown
            scaleY = PaletteDefaults.ENTER_SCALE + grown
          }
          .pointerInput(Unit) { detectTapGestures {} },
      )
    }
  }
}

/**
 * The palette's surface: the field, the rows and the key hints, without the popup around it.
 * Only [wide] palettes show key hints, and the whole placeholder.
 */
@Composable
private fun PalettePanel(
  state: AppState,
  onDismiss: () -> Unit,
  onCommand: (KetchCommand) -> Boolean,
  canRun: (KetchCommand) -> Boolean,
  destinations: List<AppDestination>,
  modifier: Modifier = Modifier,
  initialQuery: String = "",
  history: PaletteHistory = PaletteHistory.Default,
  wide: Boolean = true,
) {
  val colors = KetchTheme.colors
  var query by remember {
    mutableStateOf(TextFieldValue(initialQuery, TextRange(0, initialQuery.length)))
  }
  var highlighted by remember { mutableIntStateOf(0) }
  // Counts the moves of the highlight by key, each of which scrolls the row into view.
  var keyMoves by remember { mutableIntStateOf(0) }
  val rowCommands = rememberRowCommands(state)
  val source = rememberPaletteSource(state, query.text, canRun, destinations, rowCommands)
  val results = remember(source, history.recent) {
    paletteResults(source.query, paletteItems(source), history.recent)
  }
  val items = results.items
  val current = highlighted.coerceIn(0, (items.size - 1).coerceAtLeast(0))
  val command by rememberUpdatedState(onCommand)
  val runner = remember(state, rowCommands) { PaletteRunner(state, rowCommands) { command(it) } }
  val discover = AppDestination.Discover in destinations
  val hostShortcuts = LocalHostShortcuts.current
  val matcher = remember(hostShortcuts) { shellShortcuts(hostShortcuts) }
  val focus = remember { FocusRequester() }
  val listState = rememberLazyListState()

  fun run(item: PaletteItem, alternate: Boolean) {
    val action = if (alternate) item.alternate ?: return else item.action
    history.record(item.id)
    onDismiss()
    runner.run(action)
  }

  fun handleKey(event: KeyEvent): Boolean {
    val context = ShortcutContext(
      overlay = CommandScope.Palette,
      textFieldFocused = true,
      // ↩ and the arrows that pick an input method's candidate must not run or move a row.
      composing = query.composition != null,
    )
    val command = matcher.match(event, context) ?: return false
    val item = items.getOrNull(current)
    when (command) {
      KetchCommands.PaletteUp -> {
        highlighted = (current - 1).coerceAtLeast(0)
        keyMoves++
      }
      KetchCommands.PaletteDown -> {
        highlighted = (current + 1).coerceIn(0, items.lastIndex.coerceAtLeast(0))
        keyMoves++
      }
      KetchCommands.PaletteRun -> item?.let { run(it, alternate = false) }
      KetchCommands.PaletteAlternate -> item?.let { run(it, alternate = true) }
      KetchCommands.PaletteDiscover -> {
        val search = items.firstOrNull { it.action is PaletteAction.Discover } ?: return false
        run(search, alternate = false)
      }
      KetchCommands.PaletteClose, KetchCommands.Palette -> onDismiss()
      else -> {
        // ⌥⌘n downloads a typed link on device n; any other global chord runs and closes.
        val device = (1..MAX_DEVICE_CHORDS).firstOrNull { KetchCommands.device(it) == command }
        val target = device?.let { number -> source.devices.firstOrNull { it.number == number } }
        val download = target?.let { downloadOn(items, it.deviceId) }
        if (download != null) {
          run(download, alternate = false)
          return true
        }
        if (command.scope != CommandScope.Global || !canRun(command)) return false
        onDismiss()
        return onCommand(command)
      }
    }
    return true
  }

  LaunchedEffect(focus) { runCatching { focus.requestFocus() } }
  LaunchedEffect(query.text) { listState.scrollToItem(0) }
  // Only keys scroll: rows that update as downloads run leave a list scrolled by hand alone.
  LaunchedEffect(keyMoves) { if (keyMoves > 0) revealRow(listState, results, current) }

  Column(
    modifier = modifier.ketchSurface(
      level = KetchElevationLevel.E4,
      shape = KetchTheme.shapes.xl,
      fill = colors.surfaceRaised,
    ),
  ) {
    PaletteInput(
      value = query,
      onValueChange = { value ->
        if (value.text != query.text) highlighted = 0
        query = value
      },
      placeholder = if (wide) PLACEHOLDER else SHORT_PLACEHOLDER,
      link = items.firstOrNull()?.provider == PaletteProvider.Links,
      focus = focus,
      onKey = ::handleKey,
      onGo = { items.getOrNull(current)?.let { run(it, alternate = false) } },
    )
    Divider()
    if (items.isEmpty()) {
      NoMatches(query.text.trim(), Modifier.weight(1f, fill = false))
    } else {
      PaletteList(
        results = results,
        highlighted = current,
        listState = listState,
        keys = wide,
        onHover = { highlighted = it },
        onRun = { run(it, alternate = false) },
        modifier = Modifier.weight(1f, fill = false),
      )
    }
    if (wide) {
      Divider()
      // ⌥↩ searches Discover only for text Discover is offered for.
      val discoverable = query.text.isBlank() || items.any { it.action is PaletteAction.Discover }
      KeyHints(discover = discover && discoverable)
    }
  }
}

/** What the palette's rows are made from, kept current with [state]. */
@Composable
private fun rememberPaletteSource(
  state: AppState,
  query: String,
  canRun: (KetchCommand) -> Boolean,
  destinations: List<AppDestination>,
  rowCommands: RowCommands,
): PaletteSource {
  val rows by state.taskList.rows.collectAsState()
  val instances by state.instances.collectAsState()
  val active by state.activeInstance.collectAsState()
  val presence by remember(state) { state.instanceManager.presence }.collectAsState()
  val ops by state.pendingOps.ops.collectAsState()
  val controller = state.activeSpeedMode
  val full = remember { MutableStateFlow<SpeedMode>(SpeedMode.Full) }
  val mode by (controller?.mode ?: full).collectAsState()
  val canRunTask = remember(rowCommands) { rowCommands::canRun }
  val platform = KeyboardPlatform.current
  val commands = remember(canRun, platform) {
    KetchCommands.all.filter { it.scope == CommandScope.Global && canRun(it) }
  }
  val clock = LocalClock.current
  val now = remember(query, clock) { clock.now() }
  val devices = instances.mapIndexed { index, entry ->
    val device = presence.firstOrNull { it.deviceId == entry.deviceId }
    PaletteDevice(
      deviceId = entry.deviceId,
      name = device?.name ?: entry.displayName,
      number = index + 1,
      active = entry == active,
      line = device?.let(::deviceLine).orEmpty(),
      reachable = device == null || device.reachable,
    )
  }
  return PaletteSource(
    query = query,
    rows = rows,
    devices = devices,
    commands = commands,
    destinations = destinations,
    settings = SettingsCategory.visible(
      discoverSupported = state.aiSettings.supported,
      device = active,
      serverSupported = state.instanceManager.isLocalServerSupported,
    ),
    speed = PaletteSpeed(
      modes = controller != null,
      slowLane = mode.isSlowLane,
      rules = mode is SpeedMode.Auto,
      slowLaneSpeed = controller?.slowLaneLimit,
      cap = state.instanceSettings.download?.speedLimit ?: SpeedLimit.Unlimited,
    ),
    undoLabel = ops.lastOrNull()?.label,
    canRun = canRunTask,
    now = now,
    timeZone = TimeZone.currentSystemDefault(),
    platform = platform,
  )
}

@Composable
private fun rememberRowCommands(state: AppState): RowCommands {
  val files = rememberFileActions()
  val clipboard = rememberSystemClipboard()
  val uriHandler = LocalUriHandler.current
  val scope = rememberCoroutineScope()
  return remember(state, files, clipboard, uriHandler, scope) {
    RowCommands(state, files, clipboard, scope, uriHandler::openUri)
  }
}

/** What a device is doing, after its name: "2 active · 6.4 MB/s", "Idle", "Offline"… */
private fun deviceLine(device: DevicePresence): String = when {
  device.health == DeviceHealth.Unauthorized -> "Needs a token"
  !device.connected -> "Not connected"
  device.health is DeviceHealth.Offline -> "Offline"
  device.health == DeviceHealth.Connecting -> "Connecting"
  device.counts.downloading > 0 ->
    "${device.counts.downloading} active · ${formatBytes(device.speed)}/s"
  device.counts.waiting > 0 -> "${device.counts.waiting} waiting"
  else -> "Idle"
}

// Offline or refused for want of a token, as [deviceLine] reads it; a device the app does not
// keep connected may still take a download.
private val DevicePresence.reachable: Boolean
  get() = health != DeviceHealth.Unauthorized && !(connected && health is DeviceHealth.Offline)

@Composable
private fun PaletteInput(
  value: TextFieldValue,
  onValueChange: (TextFieldValue) -> Unit,
  placeholder: String,
  link: Boolean,
  focus: FocusRequester,
  onKey: (KeyEvent) -> Boolean,
  onGo: () -> Unit,
) {
  val colors = KetchTheme.colors
  val spacing = KetchTheme.spacing
  val style = KetchTheme.typography.titleM.copy(
    fontWeight = FontWeight.Normal,
    color = colors.textPrimary,
  )
  Row(
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(spacing.s3),
    modifier = Modifier
      .fillMaxWidth()
      .height(PaletteDefaults.InputHeight)
      .padding(start = spacing.s4, end = spacing.s2),
  ) {
    KetchIconImage(
      icon = if (link) KetchIcon.Link else KetchIcon.Search,
      size = KetchTheme.density.controlGlyph,
      tint = if (link) colors.accentText else colors.textSecondary,
    )
    BasicTextField(
      value = value,
      onValueChange = onValueChange,
      singleLine = true,
      textStyle = style,
      cursorBrush = SolidColor(colors.accent),
      keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
      keyboardActions = KeyboardActions(onGo = { onGo() }),
      modifier = Modifier
        .weight(1f)
        .focusRequester(focus)
        .onPreviewKeyEvent(onKey)
        .semantics { contentDescription = "Command palette" },
      decorationBox = { field ->
        Box(contentAlignment = Alignment.CenterStart) {
          if (value.text.isEmpty()) {
            Text(
              text = placeholder,
              style = style,
              color = colors.textTertiary,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis,
            )
          }
          field()
        }
      },
    )
    if (value.text.isNotEmpty()) {
      KetchIconButton(
        icon = KetchIcon.Close,
        onClick = { onValueChange(TextFieldValue()) },
        size = KetchButtonSize.Small,
        contentDescription = "Clear",
        modifier = Modifier.focusProperties { canFocus = false },
      )
    }
  }
}

@Composable
private fun PaletteList(
  results: PaletteResults,
  highlighted: Int,
  listState: LazyListState,
  keys: Boolean,
  onHover: (Int) -> Unit,
  onRun: (PaletteItem) -> Unit,
  modifier: Modifier = Modifier,
) {
  val spacing = KetchTheme.spacing
  val rowHeight = paletteRowHeight()
  val mouse = remember { MouseTracker() }
  LazyColumn(
    state = listState,
    contentPadding = PaddingValues(spacing.s2),
    modifier = modifier
      .heightIn(max = rowHeight * PaletteDefaults.VISIBLE_ROWS + spacing.s4)
      .trackMouse(mouse),
  ) {
    itemsIndexed(results.entries, contentType = { _, entry -> entry::class }) { _, entry ->
      when (entry) {
        is PaletteEntry.Header -> SectionHeader(entry.title)
        is PaletteEntry.Row -> PaletteRow(
          item = entry.item,
          highlighted = entry.index == highlighted,
          keys = keys,
          height = rowHeight,
          mouse = mouse,
          onHover = { onHover(entry.index) },
          onClick = { onRun(entry.item) },
        )
      }
    }
  }
}

/**
 * Where the mouse is over the palette's list, in the list's own coordinates, which stay put
 * while its rows scroll. Rows that scroll or change under a still pointer get a move at the
 * same spot, which [moved] tells apart from the mouse moving; a finger is never tracked.
 */
private class MouseTracker {
  var position: Offset? = null

  /** Whether the pointer event being handled moved the mouse. */
  var moved: Boolean = false
}

// Runs before the rows see each event, so they can read whether it moved the mouse.
private fun Modifier.trackMouse(mouse: MouseTracker): Modifier = pointerInput(mouse) {
  awaitPointerEventScope {
    while (true) {
      val event = awaitPointerEvent(PointerEventPass.Initial)
      val change = event.changes.firstOrNull { it.type == PointerType.Mouse }
      val position = change?.position?.takeIf { event.type != PointerEventType.Exit }
      val last = mouse.position
      mouse.moved = position != null && last != null && position != last
      mouse.position = position
    }
  }
}

@Composable
private fun paletteRowHeight(): Dp =
  maxOf(PaletteDefaults.RowHeight, KetchTheme.density.menuItem)

// Scrolls the list just enough to show the highlighted row, and its heading above it: a row
// above the rows shown comes in at the top, one below them at the bottom.
private suspend fun revealRow(listState: LazyListState, results: PaletteResults, row: Int) {
  val index = results.entryIndex(row)
  if (index < 0) return
  val top = if (index > 0 && results.entries[index - 1] is PaletteEntry.Header) index - 1 else index
  val visible = listState.layoutInfo.visibleItemsInfo
  val first = visible.firstOrNull() ?: return
  if (top < first.index || top == first.index && first.offset < 0) {
    // The row, or its heading, is above the rows shown.
    listState.scrollToItem(top)
    return
  }
  // Offsets count from below the top padding, and rows end above the bottom padding.
  val shown = visible.firstOrNull { it.index == index }
  if (shown != null) {
    val info = listState.layoutInfo
    val hidden = shown.offset + shown.size - (info.viewportEndOffset - info.afterContentPadding)
    if (hidden > 0) listState.scrollBy(hidden.toFloat())
    return
  }
  // A row not laid out yet goes to the top, as far as the list scrolls, then down to the bottom.
  listState.scrollToItem(index)
  val info = listState.layoutInfo
  val placed = info.visibleItemsInfo.firstOrNull { it.index == index } ?: return
  val gap = info.viewportEndOffset - info.afterContentPadding - (placed.offset + placed.size)
  if (gap > 0) listState.scrollBy(-gap.toFloat())
}

@Composable
private fun SectionHeader(title: String) {
  val spacing = KetchTheme.spacing
  Box(
    contentAlignment = Alignment.BottomStart,
    modifier = Modifier
      .fillMaxWidth()
      .height(PaletteDefaults.HeaderHeight)
      .padding(horizontal = spacing.s3)
      .padding(bottom = spacing.s1),
  ) {
    KetchEyebrow(title)
  }
}

@Composable
private fun PaletteRow(
  item: PaletteItem,
  highlighted: Boolean,
  keys: Boolean,
  height: Dp,
  mouse: MouseTracker,
  onHover: () -> Unit,
  onClick: () -> Unit,
) {
  val colors = KetchTheme.colors
  val spacing = KetchTheme.spacing
  val hover by rememberUpdatedState(onHover)
  Row(
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(spacing.s3),
    modifier = Modifier
      .fillMaxWidth()
      // Taller when large text needs it, as a stacked subtitle may.
      .heightIn(min = height)
      .clip(KetchTheme.shapes.md)
      .background(if (highlighted) colors.accentSoft else Color.Transparent)
      // Only a mouse that moves highlights a row, not a row moving under a still one.
      .pointerInput(mouse) {
        awaitPointerEventScope {
          while (true) {
            if (awaitPointerEvent().type == PointerEventType.Move && mouse.moved) hover()
          }
        }
      }
      .focusProperties { canFocus = false }
      .ketchClickable(remember { MutableInteractionSource() }, onClick = onClick)
      .semantics { selected = highlighted }
      .padding(horizontal = spacing.s3),
  ) {
    RowIcon(item.icon, highlighted)
    TitleAndSubtitle(
      title = item.title,
      subtitle = item.subtitle,
      fileName = item.icon is PaletteIcon.File,
      stack = !keys,
      modifier = Modifier.weight(1f),
    )
    // Touch screens get no key hints.
    val verb = item.verb.takeIf { highlighted && keys }
    val run = KetchCommands.PaletteRun.shortcutLabel()
    if (verb != null && run != null) {
      Text(
        text = verb,
        style = KetchTheme.typography.labelS,
        color = colors.accentText,
        maxLines = 1,
      )
      KeyCap(run)
    } else if (keys && item.shortcut != null) {
      KeyCap(item.shortcut)
    }
  }
}

@Composable
private fun RowIcon(icon: PaletteIcon, highlighted: Boolean) {
  val colors = KetchTheme.colors
  val size = KetchFileTypeChipDefaults.TableSize
  Box(contentAlignment = Alignment.Center, modifier = Modifier.size(size)) {
    when (icon) {
      is PaletteIcon.Glyph -> KetchIconImage(
        icon = icon.icon,
        size = KetchTheme.density.controlGlyph,
        tint = if (highlighted) colors.accentText else colors.textSecondary,
      )
      is PaletteIcon.File -> KetchFileTypeChip(
        fileName = icon.name,
        sourceUrl = icon.url,
        size = size,
      )
      is PaletteIcon.Device -> DevicePennant(
        deviceId = icon.deviceId,
        name = icon.name,
        size = DevicePennantDefaults.Small,
      )
    }
  }
}

/**
 * [title] with [subtitle] under it when [stack] is set, as on a phone. Otherwise both share
 * one line, and when they do not fit the title gives up width first, leaving the subtitle at
 * least [PaletteDefaults.SUBTITLE_SHARE] of it, so a device name or a count is never cut off by
 * a long file name. A title that ends in a [fileName] loses characters from its middle, so the
 * extension stays.
 */
@Composable
private fun TitleAndSubtitle(
  title: String,
  subtitle: String?,
  fileName: Boolean,
  stack: Boolean,
  modifier: Modifier = Modifier,
) {
  val colors = KetchTheme.colors
  val gap = KetchTheme.spacing.s2
  Layout(
    modifier = modifier,
    content = {
      if (fileName) {
        FileNameText(text = title, style = KetchTheme.typography.body, color = colors.textPrimary)
      } else {
        Text(
          text = title,
          style = KetchTheme.typography.body,
          color = colors.textPrimary,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
      }
      if (subtitle != null) {
        Text(
          text = subtitle,
          style = KetchTheme.typography.caption,
          color = colors.textSecondary,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
      }
    },
  ) { measurables, constraints ->
    val width = constraints.maxWidth
    val gapPx = gap.roundToPx()
    val loose = constraints.copy(minWidth = 0, minHeight = 0)
    val subtitle = measurables.getOrNull(1)
    if (stack && subtitle != null) {
      val titlePlaceable = measurables[0].measure(loose)
      val subtitlePlaceable = subtitle.measure(loose)
      return@Layout layout(width, titlePlaceable.height + subtitlePlaceable.height) {
        titlePlaceable.placeRelative(0, 0)
        subtitlePlaceable.placeRelative(0, titlePlaceable.height)
      }
    }
    val subtitleWants = subtitle?.maxIntrinsicWidth(constraints.maxHeight)?.plus(gapPx) ?: 0
    val subtitleKeeps = minOf(subtitleWants, (width * PaletteDefaults.SUBTITLE_SHARE).toInt())
    val titlePlaceable = measurables[0].measure(loose.copy(maxWidth = width - subtitleKeeps))
    val subtitleWidth = (width - titlePlaceable.width - gapPx).coerceAtLeast(0)
    val subtitlePlaceable = subtitle?.measure(loose.copy(maxWidth = subtitleWidth))
    val height = maxOf(titlePlaceable.height, subtitlePlaceable?.height ?: 0)
    layout(width, height) {
      titlePlaceable.placeRelative(0, (height - titlePlaceable.height) / 2)
      subtitlePlaceable?.placeRelative(
        x = titlePlaceable.width + gapPx,
        y = (height - subtitlePlaceable.height) / 2,
      )
    }
  }
}

@Composable
private fun NoMatches(query: String, modifier: Modifier = Modifier) {
  val colors = KetchTheme.colors
  val spacing = KetchTheme.spacing
  Column(
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.spacedBy(spacing.s1),
    modifier = modifier
      .fillMaxWidth()
      .padding(horizontal = spacing.s6, vertical = spacing.s8),
  ) {
    Text(
      text = "Nothing matches “$query”",
      style = KetchTheme.typography.bodyStrong,
      color = colors.textPrimary,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
    )
    Text(
      text = "Try a file name, a command such as “pause”, a link or /speed",
      style = KetchTheme.typography.caption,
      color = colors.textSecondary,
    )
  }
}

@Composable
private fun KeyHints(discover: Boolean) {
  val colors = KetchTheme.colors
  val spacing = KetchTheme.spacing
  val hints = buildList {
    val move = listOfNotNull(
      KetchCommands.PaletteUp.shortcutLabel(),
      KetchCommands.PaletteDown.shortcutLabel()
    ).joinToString("")
    add(move to "move")
    KetchCommands.PaletteRun.shortcutLabel()?.let { add(it to "run") }
    KetchCommands.PaletteAlternate.shortcutLabel()?.let { add(it to "alternate") }
    if (discover) KetchCommands.PaletteDiscover.shortcutLabel()?.let { add(it to "Discover") }
    KetchCommands.PaletteClose.shortcutLabel()?.let { add(it.lowercase() to "close") }
  }
  Row(
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(spacing.s2),
    modifier = Modifier
      .fillMaxWidth()
      .height(PaletteDefaults.FooterHeight)
      .background(colors.surfaceSunken)
      .padding(horizontal = spacing.s4),
  ) {
    hints.forEachIndexed { index, (key, label) ->
      if (index > 0) {
        Text(text = "·", style = KetchTheme.typography.caption, color = colors.textTertiary)
      }
      Text(text = key, style = KetchTheme.typography.labelS, color = colors.textSecondary)
      Text(text = label, style = KetchTheme.typography.caption, color = colors.textTertiary)
    }
  }
}

@Composable
private fun Divider() {
  Box(
    Modifier
      .fillMaxWidth()
      .height(PaletteDefaults.Hairline)
      .background(KetchTheme.colors.divider),
  )
}

/** The row that downloads the typed links on the device [deviceId], if there is one. */
private fun downloadOn(items: List<PaletteItem>, deviceId: String): PaletteItem? =
  items.firstOrNull { (it.action as? PaletteAction.Download)?.deviceId == deviceId }

/** Places the popup over the whole window, whatever composed it. */
private object WindowOrigin : PopupPositionProvider {
  override fun calculatePosition(
    anchorBounds: IntRect,
    windowSize: IntSize,
    layoutDirection: LayoutDirection,
    popupContentSize: IntSize,
  ): IntOffset = IntOffset.Zero
}

private const val MAX_DEVICE_CHORDS = 9
private const val PLACEHOLDER = "Paste a link, search downloads, or type a command"
private const val SHORT_PLACEHOLDER = "Paste a link, search, or type a command"
