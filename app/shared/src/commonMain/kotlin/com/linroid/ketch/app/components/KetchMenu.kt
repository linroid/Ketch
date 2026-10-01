package com.linroid.ketch.app.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.linroid.ketch.app.icons.KetchIcon
import com.linroid.ketch.app.icons.KetchIconImage
import com.linroid.ketch.app.input.KetchCommand
import com.linroid.ketch.app.theme.KetchDensity
import com.linroid.ketch.app.theme.KetchElevationLevel
import com.linroid.ketch.app.theme.KetchTheme
import com.linroid.ketch.app.theme.eyebrowText
import com.linroid.ketch.app.theme.ketchSurface
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

/** Builds the entries of a [KetchMenu]. */
class KetchMenuScope internal constructor() {
  internal val entries = mutableListOf<MenuEntry>()

  /**
   * Adds an item that runs [onClick] and closes the menu.
   *
   * @param shortcut chord of the same action, right-aligned; hidden on touch.
   * @param caption second line, such as what a priority does.
   * @param destructive draws the label in the failed color.
   * @param checked shows a check when true; `null` for items that are not checkable.
   * @param keepOpen keeps the menu open after the click, for checkable lists.
   */
  fun item(
    label: String,
    onClick: () -> Unit,
    icon: KetchIcon? = null,
    shortcut: String? = null,
    caption: String? = null,
    enabled: Boolean = true,
    destructive: Boolean = false,
    checked: Boolean? = null,
    keepOpen: Boolean = false,
  ) {
    entries += MenuEntry.Item(
      label = label,
      onClick = onClick,
      icon = icon,
      shortcut = shortcut,
      caption = caption,
      enabled = enabled,
      destructive = destructive,
      checked = checked,
      keepOpen = keepOpen,
    )
  }

  /** Adds an item for [command], with its label, icon and shortcut on this platform. */
  fun item(
    command: KetchCommand,
    onClick: () -> Unit,
    enabled: Boolean = true,
    label: String = command.label,
    destructive: Boolean = false,
  ) {
    item(
      label = label,
      onClick = onClick,
      icon = command.icon,
      shortcut = command.shortcutLabel(),
      enabled = enabled,
      destructive = destructive,
    )
  }

  /** Adds an item that opens the entries of [content] beside the menu, or in place on touch. */
  fun submenu(
    label: String,
    icon: KetchIcon? = null,
    enabled: Boolean = true,
    content: KetchMenuScope.() -> Unit,
  ) {
    entries += MenuEntry.Submenu(label, icon, enabled, buildMenu(content))
  }

  /** Adds a divider; dividers at either end or next to another one are dropped. */
  fun divider() {
    entries += MenuEntry.Divider
  }

  /** Adds a section label, such as "Recent". */
  fun header(text: String) {
    entries += MenuEntry.Header(text)
  }

  /** Adds custom [content], such as a picker; it receives a function that closes the menu. */
  fun custom(content: @Composable (dismiss: () -> Unit) -> Unit) {
    entries += MenuEntry.Custom(content)
  }
}

/** One entry of a [KetchMenu]. */
internal sealed interface MenuEntry {
  class Item(
    val label: String,
    val onClick: () -> Unit,
    val icon: KetchIcon?,
    val shortcut: String?,
    val caption: String?,
    val enabled: Boolean,
    val destructive: Boolean,
    val checked: Boolean?,
    val keepOpen: Boolean,
  ) : MenuEntry

  class Submenu(
    val label: String,
    val icon: KetchIcon?,
    val enabled: Boolean,
    val entries: List<MenuEntry>,
  ) : MenuEntry

  data object Divider : MenuEntry

  class Header(val text: String) : MenuEntry

  class Custom(val content: @Composable (dismiss: () -> Unit) -> Unit) : MenuEntry
}

/** The entries [content] adds, without dividers at either end or next to each other. */
internal fun buildMenu(content: KetchMenuScope.() -> Unit): List<MenuEntry> {
  val entries = KetchMenuScope().apply(content).entries
  val result = ArrayList<MenuEntry>(entries.size)
  for (entry in entries) {
    if (entry == MenuEntry.Divider && result.lastOrNull().let { it == null || it == entry }) {
      continue
    }
    result += entry
  }
  if (result.lastOrNull() == MenuEntry.Divider) result.removeAt(result.lastIndex)
  return result
}

/** Whether the keyboard can move to [this] entry. */
internal val MenuEntry.isSelectable: Boolean
  get() = when (this) {
    is MenuEntry.Item -> enabled
    is MenuEntry.Submenu -> enabled
    else -> false
  }

/**
 * The next selectable entry after [from] in the direction of [step], wrapping around, or -1
 * when there is none. A negative [from] starts before the first entry, or after the last one
 * when stepping back.
 */
internal fun nextSelectable(entries: List<MenuEntry>, from: Int, step: Int): Int {
  if (entries.none { it.isSelectable }) return -1
  var index = if (from < 0) (if (step > 0) -1 else entries.size) else from
  repeat(entries.size) {
    index = (index + step).mod(entries.size)
    if (entries[index].isSelectable) return index
  }
  return -1
}

/** Keys a menu reacts to. */
internal enum class MenuKey { Up, Down, Left, Right, Enter, Escape }

/** Highlight and open submenu of one level of a popup menu. */
@Stable
internal class MenuLevel {
  var highlighted: Int by mutableIntStateOf(-1)
  var hovered: Int by mutableIntStateOf(-1)
    private set
  var openIndex: Int by mutableIntStateOf(-1)
    private set
  var child: MenuLevel? by mutableStateOf(null)
    private set

  fun hover(index: Int) {
    hovered = index
    highlighted = index
  }

  fun unhover(index: Int) {
    if (hovered == index) hovered = -1
    if (highlighted == index && openIndex != index) highlighted = -1
  }

  fun openSubmenu(index: Int): MenuLevel {
    val level = MenuLevel()
    openIndex = index
    child = level
    highlighted = index
    return level
  }

  fun closeSubmenu() {
    openIndex = -1
    child = null
  }

  /**
   * Handles [key] in the deepest open submenu that has a highlighted entry, or in this level.
   * Returns whether the key was used. While [editing], custom content such as a field has the
   * focus and keeps every key but Esc.
   */
  fun handle(
    key: MenuKey,
    entries: List<MenuEntry>,
    dismiss: () -> Unit,
    editing: Boolean = false,
  ): Boolean {
    if (editing && key != MenuKey.Escape) return false
    var parent: MenuLevel? = null
    var level = this
    var levelEntries = entries
    while (true) {
      val child = level.child ?: break
      val submenu = levelEntries.getOrNull(level.openIndex) as? MenuEntry.Submenu ?: break
      if (child.highlighted < 0) break
      parent = level
      level = child
      levelEntries = submenu.entries
    }
    when (key) {
      MenuKey.Down, MenuKey.Up -> {
        val step = if (key == MenuKey.Down) 1 else -1
        level.highlighted = nextSelectable(levelEntries, level.highlighted, step)
        if (level.openIndex >= 0 && level.openIndex != level.highlighted) level.closeSubmenu()
      }
      MenuKey.Right -> {
        val submenu = levelEntries.getOrNull(level.highlighted) as? MenuEntry.Submenu
        if (submenu == null || !submenu.enabled) return false
        level.openSubmenu(level.highlighted).highlighted = nextSelectable(submenu.entries, -1, 1)
      }
      MenuKey.Left -> parent?.closeSubmenu() ?: return false
      MenuKey.Enter -> return level.activate(level.highlighted, levelEntries, dismiss)
      MenuKey.Escape -> when {
        parent != null -> parent.closeSubmenu()
        level.child != null -> level.closeSubmenu()
        else -> dismiss()
      }
    }
    return true
  }

  /** Runs the item at [index], or opens the submenu there with its first entry highlighted. */
  fun activate(index: Int, entries: List<MenuEntry>, dismiss: () -> Unit): Boolean {
    when (val entry = entries.getOrNull(index)) {
      is MenuEntry.Item -> {
        if (!entry.enabled) return false
        entry.onClick()
        if (!entry.keepOpen) dismiss()
      }
      is MenuEntry.Submenu -> {
        if (!entry.enabled) return false
        openSubmenu(index).highlighted = nextSelectable(entry.entries, -1, 1)
      }
      else -> return false
    }
    return true
  }
}

/** The custom entries of a menu that hold the keyboard focus. */
internal class MenuFocus {
  private val focused = mutableSetOf<Any>()

  /** Whether custom content, such as a field, has the focus. */
  val editing: Boolean get() = focused.isNotEmpty()

  fun update(entry: Any, hasFocus: Boolean) {
    if (hasFocus) focused += entry else focused -= entry
  }
}

/**
 * A menu of commands, anchored to the layout it is placed in, like a dropdown.
 *
 * With a pointer it is a raised panel under the anchor whose submenus open beside it on hover
 * or with →; arrow keys, ↩ and Esc work as in system menus. On touch it opens as a bottom sheet
 * with 48 dp items, and submenus replace its content.
 *
 * @param offset shift from the anchor's bottom-start corner.
 * @param title heading of the bottom sheet on touch.
 */
@Composable
fun KetchMenu(
  expanded: Boolean,
  onDismissRequest: () -> Unit,
  modifier: Modifier = Modifier,
  offset: DpOffset = DpOffset.Zero,
  title: String? = null,
  content: KetchMenuScope.() -> Unit,
) {
  if (!expanded) return
  val entries = buildMenu(content)
  if (KetchTheme.density == KetchDensity.Comfortable) {
    MenuSheet(entries, onDismissRequest, title, modifier)
  } else {
    MenuPopup(entries, onDismissRequest, offset, modifier)
  }
}

/** The panel of a [KetchMenu] as drawn in place, without a popup; for previews. */
@Composable
internal fun KetchMenuPanel(
  modifier: Modifier = Modifier,
  content: KetchMenuScope.() -> Unit,
) {
  MenuPanel(
    entries = buildMenu(content),
    level = remember { MenuLevel() },
    focus = remember { MenuFocus() },
    onDismiss = {},
    modifier = modifier,
  )
}

@Composable
private fun MenuPopup(
  entries: List<MenuEntry>,
  onDismiss: () -> Unit,
  offset: DpOffset,
  modifier: Modifier,
) {
  val density = LocalDensity.current
  val shift = with(density) { IntOffset(offset.x.roundToPx(), offset.y.roundToPx()) }
  val gap = with(density) { MenuGap.roundToPx() }
  val root = remember { MenuLevel() }
  val menuFocus = remember { MenuFocus() }
  val currentEntries by rememberUpdatedState(entries)
  Popup(
    popupPositionProvider = remember(shift, gap) { DropdownPositionProvider(shift, gap) },
    onDismissRequest = onDismiss,
    properties = PopupProperties(focusable = true),
  ) {
    val focus = remember { FocusRequester() }
    val appear = remember { Animatable(0f) }
    val motion = KetchTheme.motion
    LaunchedEffect(Unit) {
      focus.requestFocus()
      appear.animateTo(1f, tween(motion.short, easing = motion.easeDecelerate))
    }
    MenuPanel(
      entries = entries,
      level = root,
      focus = menuFocus,
      onDismiss = onDismiss,
      modifier = modifier
        .graphicsLayer {
          alpha = appear.value
          val scale = APPEAR_SCALE + (1f - APPEAR_SCALE) * appear.value
          scaleX = scale
          scaleY = scale
        }
        .onPreviewKeyEvent { event ->
          if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
          val key = event.key.toMenuKey() ?: return@onPreviewKeyEvent false
          root.handle(key, currentEntries, onDismiss, menuFocus.editing)
        }
        .focusRequester(focus)
        .focusable(),
    )
  }
}

private fun Key.toMenuKey(): MenuKey? = when (this) {
  Key.DirectionUp -> MenuKey.Up
  Key.DirectionDown -> MenuKey.Down
  Key.DirectionLeft -> MenuKey.Left
  Key.DirectionRight -> MenuKey.Right
  Key.Enter, Key.NumPadEnter, Key.Spacebar -> MenuKey.Enter
  Key.Escape -> MenuKey.Escape
  else -> null
}

@Composable
private fun MenuPanel(
  entries: List<MenuEntry>,
  level: MenuLevel,
  focus: MenuFocus,
  onDismiss: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val colors = KetchTheme.colors
  val spacing = KetchTheme.spacing
  val currentEntries by rememberUpdatedState(entries)
  LaunchedEffect(level) {
    snapshotFlow { level.hovered }.collectLatest { index ->
      if (index < 0) return@collectLatest
      delay(SUBMENU_DELAY_MILLIS)
      val entry = currentEntries.getOrNull(index)
      if (entry is MenuEntry.Submenu && entry.enabled) {
        if (level.openIndex != index) level.openSubmenu(index)
      } else {
        level.closeSubmenu()
      }
    }
  }
  val leading = entries.any {
    it is MenuEntry.Item && (it.icon != null || it.checked != null) ||
      it is MenuEntry.Submenu && it.icon != null
  }
  Column(
    modifier = modifier
      .width(IntrinsicSize.Max)
      .widthIn(min = MenuMinWidth, max = MenuMaxWidth)
      .ketchSurface(
        KetchElevationLevel.E3,
        KetchTheme.shapes.menu,
        colors.surfaceRaised,
        colors.hairline,
      )
      .verticalScroll(rememberScrollState())
      .padding(MenuInset),
  ) {
    entries.forEachIndexed { index, entry ->
      when (entry) {
        is MenuEntry.Item -> MenuRow(
          label = entry.label,
          icon = if (entry.checked == true) KetchIcon.Check else entry.icon,
          leading = leading,
          shortcut = entry.shortcut,
          caption = entry.caption,
          enabled = entry.enabled,
          destructive = entry.destructive,
          checked = entry.checked,
          submenu = false,
          highlighted = level.highlighted == index,
          onHover = { if (it) level.hover(index) else level.unhover(index) },
          onClick = { level.activate(index, entries, onDismiss) },
        )
        is MenuEntry.Submenu -> Box {
          MenuRow(
            label = entry.label,
            icon = entry.icon,
            leading = leading,
            shortcut = null,
            caption = null,
            enabled = entry.enabled,
            destructive = false,
            checked = null,
            submenu = true,
            highlighted = level.highlighted == index,
            onHover = { if (it) level.hover(index) else level.unhover(index) },
            onClick = { if (level.openIndex != index) level.openSubmenu(index) },
          )
          val child = level.child
          if (level.openIndex == index && child != null) {
            SubmenuPopup(entry, child, focus, onDismiss)
          }
        }
        MenuEntry.Divider -> Box(
          Modifier
            .padding(vertical = spacing.s1)
            .fillMaxWidth()
            .height(1.dp)
            .background(colors.divider),
        )
        is MenuEntry.Header -> Text(
          text = eyebrowText(entry.text),
          style = KetchTheme.typography.eyebrow,
          color = colors.textTertiary,
          modifier = Modifier.padding(
            start = spacing.s2,
            end = spacing.s2,
            top = spacing.s2,
            bottom = spacing.s1,
          ),
        )
        is MenuEntry.Custom -> {
          val key = level to index
          DisposableEffect(focus, key) { onDispose { focus.update(key, false) } }
          Box(Modifier.padding(spacing.s2).onFocusChanged { focus.update(key, it.hasFocus) }) {
            entry.content(onDismiss)
          }
        }
      }
    }
  }
}

@Composable
private fun SubmenuPopup(
  entry: MenuEntry.Submenu,
  level: MenuLevel,
  focus: MenuFocus,
  onDismiss: () -> Unit,
) {
  val inset = with(LocalDensity.current) { (MenuInset + 1.dp).roundToPx() }
  Popup(
    popupPositionProvider = remember(inset) { SubmenuPositionProvider(inset) },
    properties = PopupProperties(focusable = false),
  ) {
    MenuPanel(entry.entries, level, focus, onDismiss)
  }
}

@Composable
private fun MenuRow(
  label: String,
  icon: KetchIcon?,
  leading: Boolean,
  shortcut: String?,
  caption: String?,
  enabled: Boolean,
  destructive: Boolean,
  checked: Boolean?,
  submenu: Boolean,
  highlighted: Boolean,
  onHover: (Boolean) -> Unit,
  onClick: () -> Unit,
) {
  val colors = KetchTheme.colors
  val spacing = KetchTheme.spacing
  val glyph = KetchTheme.density.controlGlyph
  val interactions = remember { MutableInteractionSource() }
  val hovered = interactions.collectIsHoveredAsState()
  val pressed by interactions.collectIsPressedAsState()
  val currentOnHover by rememberUpdatedState(onHover)
  LaunchedEffect(interactions) {
    snapshotFlow { hovered.value }.drop(1).collect { currentOnHover(it) }
  }
  val ink = when {
    !enabled -> colors.textDisabled
    destructive -> colors.status.failed.color
    else -> colors.textPrimary
  }
  Row(
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(spacing.s2),
    modifier = Modifier
      .fillMaxWidth()
      .heightIn(min = KetchTheme.density.menuItem)
      .background(colors.interactionOverlay(highlighted, enabled && pressed), MenuItemShape)
      .semantics { if (checked != null) selected = checked }
      .clickable(
        interactionSource = interactions,
        indication = null,
        enabled = enabled,
        role = Role.Button,
        onClick = onClick,
      )
      .padding(horizontal = spacing.s2, vertical = if (caption != null) spacing.s1 else 0.dp),
  ) {
    if (leading) {
      Box(Modifier.size(glyph), contentAlignment = Alignment.Center) {
        if (icon != null) {
          KetchIconImage(
            icon = icon,
            size = glyph,
            tint = when {
              !enabled -> colors.textDisabled
              checked == true -> colors.accentText
              destructive -> ink
              else -> colors.textSecondary
            },
          )
        }
      }
    }
    Column(Modifier.weight(1f)) {
      Text(
        text = label,
        style = KetchTheme.typography.label,
        color = ink,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
      if (caption != null) {
        Text(caption, style = KetchTheme.typography.caption, color = colors.textSecondary)
      }
    }
    if (shortcut != null) {
      Text(
        text = shortcut,
        style = KetchTheme.typography.labelS,
        color = colors.textTertiary,
        maxLines = 1,
        modifier = Modifier.padding(start = spacing.s4),
      )
    }
    if (submenu) KetchIconImage(KetchIcon.Chevron, size = glyph, tint = colors.textTertiary)
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MenuSheet(
  entries: List<MenuEntry>,
  onDismiss: () -> Unit,
  title: String?,
  modifier: Modifier,
) {
  val colors = KetchTheme.colors
  val spacing = KetchTheme.spacing
  val sheetState = rememberBottomSheetState(
    initialValue = SheetValue.Hidden,
    enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded),
  )
  val scope = rememberCoroutineScope()
  val close: () -> Unit = {
    scope.launch { sheetState.hide() }.invokeOnCompletion { onDismiss() }
  }
  var path by remember { mutableStateOf(emptyList<Int>()) }
  var current = entries
  var heading = title
  for (index in path) {
    val submenu = current.getOrNull(index) as? MenuEntry.Submenu ?: break
    current = submenu.entries
    heading = submenu.label
  }
  ModalBottomSheet(
    onDismissRequest = onDismiss,
    modifier = modifier,
    sheetState = sheetState,
    shape = KetchTheme.shapes.sheetTop,
    containerColor = colors.surfaceRaised,
    contentColor = colors.textPrimary,
    tonalElevation = 0.dp,
    scrimColor = colors.scrim,
  ) {
    Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = spacing.s4)) {
      if (heading != null || path.isNotEmpty()) {
        Row(
          verticalAlignment = Alignment.CenterVertically,
          modifier = Modifier.padding(horizontal = spacing.s2).heightIn(min = spacing.s12),
        ) {
          if (path.isNotEmpty()) {
            KetchIconButton(
              icon = KetchIcon.ChevronLeft,
              contentDescription = "Back",
              onClick = { path = path.dropLast(1) },
            )
          }
          if (heading != null) {
            Text(
              text = heading,
              style = KetchTheme.typography.titleM,
              color = colors.textPrimary,
              modifier = Modifier.padding(horizontal = spacing.s2),
            )
          }
        }
      }
      current.forEachIndexed { index, entry ->
        when (entry) {
          is MenuEntry.Item -> SheetRow(
            label = entry.label,
            icon = if (entry.checked == true) KetchIcon.Check else entry.icon,
            caption = entry.caption,
            enabled = entry.enabled,
            destructive = entry.destructive,
            checked = entry.checked,
            submenu = false,
            onClick = {
              entry.onClick()
              if (!entry.keepOpen) close()
            },
          )
          is MenuEntry.Submenu -> SheetRow(
            label = entry.label,
            icon = entry.icon,
            caption = null,
            enabled = entry.enabled,
            destructive = false,
            checked = null,
            submenu = true,
            onClick = { path = path + index },
          )
          MenuEntry.Divider -> Box(
            Modifier
              .padding(vertical = spacing.s2)
              .fillMaxWidth()
              .height(1.dp)
              .background(colors.divider),
          )
          is MenuEntry.Header -> Text(
            text = eyebrowText(entry.text),
            style = KetchTheme.typography.eyebrow,
            color = colors.textTertiary,
            modifier = Modifier.padding(
              start = spacing.s4,
              end = spacing.s4,
              top = spacing.s3,
              bottom = spacing.s1,
            ),
          )
          is MenuEntry.Custom -> Box(
            Modifier.padding(horizontal = spacing.s4, vertical = spacing.s2),
          ) { entry.content(close) }
        }
      }
    }
  }
}

@Composable
private fun SheetRow(
  label: String,
  icon: KetchIcon?,
  caption: String?,
  enabled: Boolean,
  destructive: Boolean,
  checked: Boolean?,
  submenu: Boolean,
  onClick: () -> Unit,
) {
  val colors = KetchTheme.colors
  val spacing = KetchTheme.spacing
  val glyph = KetchTheme.density.controlGlyph
  val interactions = remember { MutableInteractionSource() }
  val overlay = rememberInteractionOverlay(interactions, enabled)
  val ink = when {
    !enabled -> colors.textDisabled
    destructive -> colors.status.failed.color
    else -> colors.textPrimary
  }
  Row(
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(spacing.s4),
    modifier = Modifier
      .fillMaxWidth()
      .heightIn(min = KetchTheme.density.menuItem)
      .background(overlay)
      .semantics { if (checked != null) selected = checked }
      .clickable(
        interactionSource = interactions,
        indication = null,
        enabled = enabled,
        role = Role.Button,
        onClick = onClick,
      )
      .padding(horizontal = spacing.s4, vertical = spacing.s2),
  ) {
    if (icon != null) {
      KetchIconImage(
        icon = icon,
        size = glyph,
        tint = if (checked == true) colors.accentText else colors.textSecondary,
      )
    }
    Column(Modifier.weight(1f)) {
      Text(label, style = KetchTheme.typography.body, color = ink)
      if (caption != null) {
        Text(caption, style = KetchTheme.typography.caption, color = colors.textSecondary)
      }
    }
    if (submenu) KetchIconImage(KetchIcon.Chevron, size = glyph, tint = colors.textTertiary)
  }
}

/** Opens under the anchor's start edge, or above it when there is no room below. */
private class DropdownPositionProvider(
  private val offset: IntOffset,
  private val gap: Int,
) : PopupPositionProvider {
  override fun calculatePosition(
    anchorBounds: IntRect,
    windowSize: IntSize,
    layoutDirection: LayoutDirection,
    popupContentSize: IntSize,
  ): IntOffset {
    val start = if (layoutDirection == LayoutDirection.Ltr) {
      anchorBounds.left + offset.x
    } else {
      anchorBounds.right - popupContentSize.width - offset.x
    }
    val x = start.coerceIn(0, (windowSize.width - popupContentSize.width).coerceAtLeast(0))
    val below = anchorBounds.bottom + gap + offset.y
    val above = anchorBounds.top - gap - popupContentSize.height - offset.y
    val y = when {
      below + popupContentSize.height <= windowSize.height -> below
      above >= 0 -> above
      else -> (windowSize.height - popupContentSize.height).coerceAtLeast(0)
    }
    return IntOffset(x, y)
  }
}

/** Opens beside its row, on the end side when it fits, with its first item level with it. */
private class SubmenuPositionProvider(private val inset: Int) : PopupPositionProvider {
  override fun calculatePosition(
    anchorBounds: IntRect,
    windowSize: IntSize,
    layoutDirection: LayoutDirection,
    popupContentSize: IntSize,
  ): IntOffset {
    val end = anchorBounds.right + inset
    val start = anchorBounds.left - inset - popupContentSize.width
    val endFits = end + popupContentSize.width <= windowSize.width
    val startFits = start >= 0
    val x = if (layoutDirection == LayoutDirection.Ltr) {
      if (endFits || !startFits) end else start
    } else {
      if (startFits || !endFits) start else end
    }
    val maxX = (windowSize.width - popupContentSize.width).coerceAtLeast(0)
    val maxY = (windowSize.height - popupContentSize.height).coerceAtLeast(0)
    return IntOffset(x.coerceIn(0, maxX), (anchorBounds.top - inset).coerceIn(0, maxY))
  }
}

private const val SUBMENU_DELAY_MILLIS = 150L
private const val APPEAR_SCALE = 0.96f
private val MenuInset = 4.dp
private val MenuGap = 4.dp
private val MenuMinWidth = 180.dp
private val MenuMaxWidth = 320.dp
private val MenuItemShape = RoundedCornerShape(6.dp)
