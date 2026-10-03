package com.linroid.ketch.app.ui.shell

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
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
import com.linroid.ketch.app.components.DevicePennant
import com.linroid.ketch.app.components.DevicePennantDefaults
import com.linroid.ketch.app.components.interactionOverlay
import com.linroid.ketch.app.i18n.UiText
import com.linroid.ketch.app.i18n.joinText
import com.linroid.ketch.app.i18n.resolve
import com.linroid.ketch.app.i18n.speedText
import com.linroid.ketch.app.i18n.text
import com.linroid.ketch.app.i18n.verbatim
import com.linroid.ketch.app.icons.KetchIcon
import com.linroid.ketch.app.icons.KetchIconImage
import com.linroid.ketch.app.input.KetchCommands
import com.linroid.ketch.app.instance.DevicePresence
import com.linroid.ketch.app.instance.DeviceScope
import com.linroid.ketch.app.instance.EmbeddedInstance
import com.linroid.ketch.app.instance.LanServerDiscovery
import com.linroid.ketch.app.platform.localDeviceNounInSentence
import com.linroid.ketch.app.state.AppState
import com.linroid.ketch.app.state.DeviceHealth
import com.linroid.ketch.app.state.LocalClock
import com.linroid.ketch.app.state.deviceId
import com.linroid.ketch.app.state.isSlowLane
import com.linroid.ketch.app.theme.KetchDensity
import com.linroid.ketch.app.theme.KetchElevationLevel
import com.linroid.ketch.app.theme.KetchTheme
import com.linroid.ketch.app.theme.ketchSurface
import com.linroid.ketch.app.ui.devices.addDevice
import com.linroid.ketch.app.ui.devices.pairDevice
import com.linroid.ketch.app.ui.devices.shortDurationText
import com.linroid.ketch.app.ui.sidebar.PennantCluster
import com.linroid.ketch.app.ui.sidebar.deviceShortcut
import com.linroid.ketch.app.ui.sidebar.pennantHealth
import com.linroid.ketch.app.ui.sidebar.pennantName
import com.linroid.ketch.app.ui.sidebar.rememberDevices
import ketch.app.shared.generated.resources.Res
import ketch.app.shared.generated.resources.shell_device_active
import ketch.app.shared.generated.resources.shell_device_connecting_now
import ketch.app.shared.generated.resources.shell_device_failed
import ketch.app.shared.generated.resources.shell_device_idle
import ketch.app.shared.generated.resources.shell_device_last_seen
import ketch.app.shared.generated.resources.shell_device_needs_new_token
import ketch.app.shared.generated.resources.shell_device_not_connected
import ketch.app.shared.generated.resources.shell_device_offline
import ketch.app.shared.generated.resources.shell_device_slow_lane
import ketch.app.shared.generated.resources.shell_device_waiting
import ketch.app.shared.generated.resources.shell_devices_online
import ketch.app.shared.generated.resources.shell_devices_speed
import ketch.app.shared.generated.resources.switcher_add_device
import ketch.app.shared.generated.resources.switcher_find_on_network
import ketch.app.shared.generated.resources.switcher_share
import kotlin.time.Instant

/**
 * One row of the device switcher, which the keyboard moves through in order.
 *
 * @property run what picking it does; the switcher closes first.
 */
@Immutable
internal sealed interface SwitcherEntry {
  val run: () -> Unit

  /** Every device's downloads at once. */
  data class All(override val run: () -> Unit) : SwitcherEntry

  /** One device, listed [number]th. */
  data class Device(
    val device: DevicePresence,
    val number: Int,
    override val run: () -> Unit,
  ) : SwitcherEntry

  /** A way to add or share a device. */
  data class Action(
    val label: UiText,
    val icon: KetchIcon,
    override val run: () -> Unit,
  ) : SwitcherEntry
}

/**
 * What the device switcher and the phone's device sheet list: All devices from two devices on,
 * every device, then [actions].
 */
@Composable
internal fun rememberSwitcherEntries(
  state: AppState,
  actions: List<SwitcherEntry.Action>,
): List<SwitcherEntry> {
  val devices = rememberDevices(state)
  return remember(state, devices, actions) {
    buildList {
      if (devices.size >= DeviceScope.MIN_DEVICES) {
        add(SwitcherEntry.All(run = { state.showAllDevices() }))
      }
      devices.forEachIndexed { index, device ->
        add(SwitcherEntry.Device(device, index + 1, run = { state.switchInstance(device.entry) }))
      }
      addAll(actions)
    }
  }
}

/**
 * Add device…, Find on network where the platform can search it, and, with [share], sharing
 * this device where it can share its downloads.
 */
@Composable
internal fun rememberDeviceActions(
  state: AppState,
  share: Boolean = true,
): List<SwitcherEntry.Action> {
  val canSearch = remember { LanServerDiscovery().supported }
  val instances by state.instances.collectAsState()
  val shares = share && state.instanceManager.isLocalServerSupported &&
    instances.any { it is EmbeddedInstance }
  return remember(state, canSearch, shares) {
    listOfNotNull(
      SwitcherEntry.Action(Res.string.switcher_add_device.text(), KetchIcon.Plus) {
        state.addDevice()
      },
      SwitcherEntry.Action(Res.string.switcher_find_on_network.text(), KetchIcon.Network) {
        state.findOnNetwork()
      }.takeIf { canSearch },
      SwitcherEntry.Action(
        Res.string.switcher_share.text(localDeviceNounInSentence()),
        KetchIcon.QrCode
      ) { state.pairDevice() }.takeIf { shares }
    )
  }
}

/** Searches the network for devices and opens the Add device sheet, which lists them. */
internal fun AppState.findOnNetwork() {
  discoverRemoteServers()
  addDevice()
}

/**
 * The device switcher (`⇧⌘D`, the page header's device chip), opening below where it is placed:
 * All devices, every device with what it is doing and its `⌥⌘` digit, and the ways to add one.
 *
 * Picking a row switches to it and closes the switcher. ↑ and ↓ (or Tab) move through the rows,
 * ↩ picks one, a digit or its `⌥⌘` chord picks that device (0 for All devices), and Esc closes.
 *
 * @param offset where it opens from the top-start corner of where it is placed.
 */
@Composable
internal fun DeviceSwitcherPopover(
  state: AppState,
  onDismissRequest: () -> Unit,
  offset: IntOffset = IntOffset.Zero,
) {
  val entries = rememberSwitcherEntries(state, rememberDeviceActions(state))
  val margin = KetchTheme.spacing.s2
  val density = LocalDensity.current
  val marginPx = with(density) { margin.roundToPx() }
  val windowHeight = with(density) { LocalWindowInfo.current.containerSize.height.toDp() }
  // A short window scrolls the rows rather than pushing the panel past its edges.
  val maxHeight = (windowHeight - margin * 2).coerceAtLeast(margin)
  Popup(
    popupPositionProvider = remember(offset, marginPx) {
      BelowStartPositionProvider(offset, marginPx)
    },
    onDismissRequest = onDismissRequest,
    properties = PopupProperties(focusable = true),
  ) {
    val focus = remember { FocusRequester() }
    val appear = remember { Animatable(0f) }
    val motion = KetchTheme.motion
    LaunchedEffect(Unit) {
      focus.requestFocus()
      appear.animateTo(1f, tween(motion.short, easing = motion.easeDecelerate))
    }
    SwitcherPanel(
      state = state,
      entries = entries,
      onDismissRequest = onDismissRequest,
      modifier = Modifier
        .heightIn(max = maxHeight)
        .graphicsLayer {
          alpha = appear.value
          val scale = APPEAR_SCALE + (1f - APPEAR_SCALE) * appear.value
          scaleX = scale
          scaleY = scale
        }
        .focusRequester(focus)
        .focusable(),
    )
  }
}

/** The switcher's raised panel with [entries], without the popup around it. */
@Composable
internal fun SwitcherPanel(
  state: AppState,
  entries: List<SwitcherEntry>,
  onDismissRequest: () -> Unit,
  modifier: Modifier = Modifier,
  initialHighlight: Int = -1,
) {
  val colors = KetchTheme.colors
  val spacing = KetchTheme.spacing
  var highlighted by remember { mutableIntStateOf(initialHighlight) }
  // Rows the keyboard moves to scroll into view; the ones the pointer rests on are in view.
  var fromKeyboard by remember { mutableStateOf(false) }
  val move = { step: Int ->
    highlighted = (highlighted + step).coerceIn(0, entries.lastIndex)
    fromKeyboard = true
  }
  val pick = { entry: SwitcherEntry ->
    onDismissRequest()
    entry.run()
  }
  Column(
    modifier = modifier
      .onPreviewKeyEvent { event ->
        if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
        when (event.key) {
          Key.DirectionDown -> {
            move(1)
            true
          }
          Key.DirectionUp -> {
            move(-1)
            true
          }
          Key.Tab -> {
            // Tab moves the highlight too, so the focus stays here and ↩ picks the row it shows.
            move(if (event.isShiftPressed) -1 else 1)
            true
          }
          Key.Enter, Key.NumPadEnter, Key.Spacebar -> {
            entries.getOrNull(highlighted)?.let(pick)
            true
          }
          Key.Escape -> {
            onDismissRequest()
            true
          }
          Key.D -> {
            // ⇧⌘D closes the switcher it opened.
            val chord = event.isShiftPressed && (event.isMetaPressed || event.isCtrlPressed)
            if (chord) onDismissRequest()
            chord
          }
          else -> digitPick(event, entries)?.let(pick) != null
        }
      }
      .width(SwitcherWidth)
      .ketchSurface(
        level = KetchElevationLevel.E3,
        shape = KetchTheme.shapes.menu,
        fill = colors.surfaceRaised,
        border = colors.hairline,
      )
      .verticalScroll(rememberScrollState())
      .padding(vertical = spacing.s1),
  ) {
    SwitcherRows(
      state = state,
      entries = entries,
      highlighted = highlighted,
      onHighlight = {
        highlighted = it
        fromKeyboard = false
      },
      onPick = pick,
      revealHighlight = fromKeyboard,
    )
  }
}

/**
 * The rows of [entries], with a divider before the devices and before the actions; shared by
 * the switcher and the phone's device sheet.
 *
 * @param highlighted index of the row the keyboard or the pointer is on.
 * @param revealHighlight whether the [highlighted] row scrolls into view.
 */
@Composable
internal fun ColumnScope.SwitcherRows(
  state: AppState,
  entries: List<SwitcherEntry>,
  highlighted: Int,
  onHighlight: (Int) -> Unit,
  onPick: (SwitcherEntry) -> Unit,
  revealHighlight: Boolean = false,
) {
  val devices = rememberDevices(state)
  val active by state.activeInstance.collectAsState()
  val scope by state.deviceScope.collectAsState()
  val now = LocalClock.current.now()
  entries.forEachIndexed { index, entry ->
    val previous = entries.getOrNull(index - 1)
    if (previous != null && previous::class != entry::class) SwitcherDivider()
    // A row that takes the keyboard focus, as in the device sheet, shows it as the highlight.
    val rowModifier = Modifier
      .highlightOnHover { onHighlight(index) }
      .onFocusChanged { if (it.isFocused) onHighlight(index) }
      .revealWhen(revealHighlight && index == highlighted)
    when (entry) {
      is SwitcherEntry.All -> SwitcherRow(
        leading = { PennantCluster(devices, ring = KetchTheme.colors.surfaceRaised) },
        title = KetchCommands.AllDevices.label.resolve(),
        detail = allDevicesDetail(devices).resolve(),
        current = scope == DeviceScope.All,
        shortcut = KetchCommands.AllDevices.shortcutLabel(),
        highlighted = index == highlighted,
        onClick = { onPick(entry) },
        modifier = rowModifier,
      )
      is SwitcherEntry.Device -> SwitcherRow(
        leading = {
          DevicePennant(
            deviceId = entry.device.deviceId,
            name = entry.device.pennantName,
            size = DevicePennantDefaults.Large,
            health = pennantHealth(entry.device),
            failures = entry.device.unseenFailures,
          )
        },
        title = entry.device.name.resolve(),
        subtitle = entry.device.detail.takeIf { entry.device.name != verbatim(it) },
        detail = switcherDetail(entry.device, now).resolve(),
        alert = entry.device.health == DeviceHealth.Unauthorized ||
          entry.device.connected && entry.device.health is DeviceHealth.Offline,
        current = scope != DeviceScope.All && entry.device.deviceId == active?.deviceId,
        shortcut = deviceShortcut(entry.number),
        highlighted = index == highlighted,
        onClick = { onPick(entry) },
        modifier = rowModifier,
      )
      is SwitcherEntry.Action -> ActionRow(
        entry = entry,
        highlighted = index == highlighted,
        onClick = { onPick(entry) },
        modifier = rowModifier,
      )
    }
  }
}

/**
 * A device row of the switcher: [leading] in a pennant's room, [title] with a check when it is
 * the [current] scope and [subtitle] after it, [detail] under them, and the chord that picks it
 * at the end.
 *
 * @param subtitle secondary name, such as the host name of this device or a remote's address.
 * @param alert whether [detail] tells of a problem, which it shows in the failed color.
 */
@Composable
internal fun SwitcherRow(
  leading: @Composable () -> Unit,
  title: String,
  detail: String,
  current: Boolean,
  shortcut: String?,
  highlighted: Boolean,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
  subtitle: String? = null,
  alert: Boolean = false,
) {
  val colors = KetchTheme.colors
  val spacing = KetchTheme.spacing
  val pointer = KetchTheme.density == KetchDensity.Compact
  SwitcherItem(
    highlighted = highlighted,
    onClick = onClick,
    modifier = modifier.semantics { selected = current },
    minHeight = KetchTheme.density.listRow,
  ) {
    Box(Modifier.size(spacing.s10), contentAlignment = Alignment.Center) { leading() }
    Column(Modifier.weight(1f).padding(start = spacing.s3)) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
          text = title,
          style = KetchTheme.typography.label,
          fontWeight = FontWeight.SemiBold,
          color = colors.textPrimary,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
          modifier = Modifier.weight(1f, fill = false),
        )
        if (current) {
          KetchIconImage(
            icon = KetchIcon.Check,
            size = CheckGlyph,
            tint = colors.accentText,
            modifier = Modifier.padding(start = spacing.s1),
          )
        }
        if (subtitle != null) {
          Text(
            text = subtitle,
            style = KetchTheme.typography.caption,
            color = colors.textTertiary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false).padding(start = spacing.s2),
          )
        }
      }
      Text(
        text = detail,
        style = KetchTheme.typography.caption,
        color = if (alert) colors.status.failed.color else colors.textSecondary,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
    }
    if (shortcut != null && pointer) {
      Text(
        text = shortcut,
        style = KetchTheme.typography.numeralS,
        color = colors.textTertiary,
        modifier = Modifier.padding(start = spacing.s3),
      )
    }
  }
}

/** An action row of the switcher, such as Add device…. */
@Composable
private fun ActionRow(
  entry: SwitcherEntry.Action,
  highlighted: Boolean,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val colors = KetchTheme.colors
  val spacing = KetchTheme.spacing
  SwitcherItem(
    highlighted = highlighted,
    onClick = onClick,
    modifier = modifier,
    minHeight = KetchTheme.density.menuItem + spacing.s1,
  ) {
    Box(Modifier.width(spacing.s10), contentAlignment = Alignment.Center) {
      val glyph = KetchTheme.density.controlGlyph
      KetchIconImage(entry.icon, size = glyph, tint = colors.textSecondary)
    }
    Text(
      text = entry.label.resolve(),
      style = KetchTheme.typography.label,
      color = colors.textPrimary,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
      modifier = Modifier.weight(1f).padding(start = spacing.s3),
    )
  }
}

/** A clickable row of the switcher, on the hover overlay while [highlighted]. */
@Composable
private fun SwitcherItem(
  highlighted: Boolean,
  onClick: () -> Unit,
  minHeight: Dp,
  modifier: Modifier = Modifier,
  content: @Composable RowScope.() -> Unit,
) {
  val spacing = KetchTheme.spacing
  val shape = KetchTheme.shapes.sm
  val interactions = remember { MutableInteractionSource() }
  val pressed by interactions.collectIsPressedAsState()
  // In a sheet the rows line up with its 16 dp page edge.
  val inset = if (KetchTheme.density == KetchDensity.Compact) spacing.s1 else spacing.s2
  Row(
    verticalAlignment = Alignment.CenterVertically,
    modifier = modifier
      .fillMaxWidth()
      .padding(horizontal = inset)
      .heightIn(min = minHeight)
      .background(KetchTheme.colors.interactionOverlay(highlighted, pressed), shape)
      .clickable(
        interactionSource = interactions,
        indication = null,
        role = Role.Button,
        onClick = onClick,
      )
      .padding(horizontal = spacing.s2),
    content = content,
  )
}

@Composable
private fun SwitcherDivider() {
  val spacing = KetchTheme.spacing
  Spacer(
    Modifier
      .padding(vertical = spacing.s1)
      .fillMaxWidth()
      .height(DividerWidth)
      .background(KetchTheme.colors.divider),
  )
}

/** Scrolls this row into view of the scrolling list around it whenever [reveal] turns on. */
@Composable
private fun Modifier.revealWhen(reveal: Boolean): Modifier {
  val requester = remember { BringIntoViewRequester() }
  LaunchedEffect(reveal) { if (reveal) requester.bringIntoView() }
  return bringIntoViewRequester(requester)
}

/** Moves the highlight here while the pointer rests on this row. */
@Composable
private fun Modifier.highlightOnHover(onHover: () -> Unit): Modifier {
  val interactions = remember { MutableInteractionSource() }
  val hovered by interactions.collectIsHoveredAsState()
  LaunchedEffect(hovered) { if (hovered) onHover() }
  return hoverable(interactions)
}

/**
 * The entry a digit picks: 1 to 9 the device listed so, 0 All devices; plain or with the
 * device chord's modifiers.
 */
private fun digitPick(event: KeyEvent, entries: List<SwitcherEntry>): SwitcherEntry? {
  val digit = DigitKeys.indexOf(event.key).takeIf { it >= 0 } ?: return null
  if (event.isCtrlPressed && !event.isAltPressed && !event.isMetaPressed) return null
  return if (digit == 0) {
    entries.firstOrNull { it is SwitcherEntry.All }
  } else {
    entries.firstOrNull { it is SwitcherEntry.Device && it.number == digit }
  }
}

/**
 * What the switcher says under [device]'s name: its speed while it downloads, the Slow lane, how
 * many tasks are active or wait, and failures; or why it cannot be reached, with when it was
 * last seen.
 */
internal fun switcherDetail(device: DevicePresence, now: Instant): UiText {
  val health = device.health
  return when {
    health == DeviceHealth.Unauthorized -> Res.string.shell_device_needs_new_token.text()
    !device.connected -> Res.string.shell_device_not_connected.text()
    health is DeviceHealth.Offline -> listOfNotNull(
      Res.string.shell_device_offline.text(),
      device.lastSeen?.let { Res.string.shell_device_last_seen.text(shortDurationText(now - it)) }
    ).joinText()
    health == DeviceHealth.Connecting -> Res.string.shell_device_connecting_now.text()
    else -> {
      val counts = device.counts
      listOfNotNull(
        speedText(device.speed).takeIf { counts.downloading > 0 },
        Res.string.shell_device_slow_lane.text().takeIf { device.speedMode.isSlowLane },
        when {
          counts.downloading > 0 -> Res.plurals.shell_device_active.text(counts.downloading)
          counts.waiting > 0 -> Res.plurals.shell_device_waiting.text(counts.waiting)
          else -> Res.string.shell_device_idle.text()
        },
        Res.plurals.shell_device_failed.text(device.failures).takeIf { device.failures > 0 }
      ).joinText()
    }
  }
}

/** What the switcher says under All devices: "↓ 9.1 MB/s · 4 active", or what waits. */
internal fun allDevicesDetail(devices: List<DevicePresence>): UiText {
  val online = devices.filter { it.connected && it.health.isOnline }
  val downloading = online.sumOf { it.counts.downloading }
  val waiting = online.sumOf { it.counts.waiting }
  val reachable = Res.string.shell_devices_online.text(online.size, devices.size)
    .takeIf { online.size < devices.size }
  val activity = when {
    downloading > 0 -> listOf(
      Res.string.shell_devices_speed.text(speedText(online.sumOf { it.speed })),
      Res.plurals.shell_device_active.text(downloading)
    ).joinText()
    waiting > 0 -> Res.plurals.shell_device_waiting.text(waiting)
    else -> Res.string.shell_device_idle.text()
  }
  return listOfNotNull(activity, reachable).joinText()
}

/**
 * Opens below the anchor's top-start corner moved by [offset], kept [margin] pixels inside the
 * window.
 */
private class BelowStartPositionProvider(
  private val offset: IntOffset,
  private val margin: Int,
) : PopupPositionProvider {
  override fun calculatePosition(
    anchorBounds: IntRect,
    windowSize: IntSize,
    layoutDirection: LayoutDirection,
    popupContentSize: IntSize,
  ): IntOffset {
    val x = if (layoutDirection == LayoutDirection.Ltr) {
      anchorBounds.left + offset.x
    } else {
      anchorBounds.right - offset.x - popupContentSize.width
    }
    val maxX = (windowSize.width - popupContentSize.width - margin).coerceAtLeast(margin)
    val maxY = (windowSize.height - popupContentSize.height - margin).coerceAtLeast(margin)
    val y = anchorBounds.top + offset.y
    return IntOffset(x.coerceIn(margin, maxX), y.coerceIn(margin, maxY))
  }
}

/** [x] and [y] from where a [DeviceSwitcherPopover] is placed, in pixels. */
@Composable
internal fun switcherOffset(x: Dp, y: Dp): IntOffset = with(LocalDensity.current) {
  IntOffset(x.roundToPx(), y.roundToPx())
}

private val DigitKeys = listOf(
  Key.Zero, Key.One, Key.Two, Key.Three, Key.Four,
  Key.Five, Key.Six, Key.Seven, Key.Eight, Key.Nine
)

private const val APPEAR_SCALE = 0.96f

private val SwitcherWidth: Dp = 360.dp
private val CheckGlyph: Dp = 14.dp
private val DividerWidth: Dp = 1.dp
