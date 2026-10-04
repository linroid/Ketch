package com.linroid.ketch.app.ui.discover

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.linroid.ketch.app.components.KetchBottomSheet
import com.linroid.ketch.app.components.KetchButtonSize
import com.linroid.ketch.app.components.KetchDot
import com.linroid.ketch.app.components.KetchEyebrow
import com.linroid.ketch.app.components.KetchIconButton
import com.linroid.ketch.app.components.KetchTextField
import com.linroid.ketch.app.components.focusRing
import com.linroid.ketch.app.components.rememberFocusVisibility
import com.linroid.ketch.app.components.trackFocusVisibility
import com.linroid.ketch.app.i18n.UiText
import com.linroid.ketch.app.i18n.joinText
import com.linroid.ketch.app.i18n.resolve
import com.linroid.ketch.app.i18n.text
import com.linroid.ketch.app.icons.KetchIcon
import com.linroid.ketch.app.icons.KetchIconImage
import com.linroid.ketch.app.input.KetchCommands
import com.linroid.ketch.app.state.AppState
import com.linroid.ketch.app.state.DiscoverSession
import com.linroid.ketch.app.state.LocalClock
import com.linroid.ketch.app.theme.KetchDensity
import com.linroid.ketch.app.theme.KetchElevationLevel
import com.linroid.ketch.app.theme.KetchTheme
import com.linroid.ketch.app.theme.ketchSurface
import ketch.app.shared.generated.resources.Res
import ketch.app.shared.generated.resources.action_close
import ketch.app.shared.generated.resources.discover_delete_search
import ketch.app.shared.generated.resources.discover_history
import ketch.app.shared.generated.resources.discover_history_clear
import ketch.app.shared.generated.resources.discover_history_empty
import ketch.app.shared.generated.resources.discover_history_failed
import ketch.app.shared.generated.resources.discover_history_filter
import ketch.app.shared.generated.resources.discover_history_needs_ok
import ketch.app.shared.generated.resources.discover_history_no_match
import ketch.app.shared.generated.resources.discover_history_no_results
import ketch.app.shared.generated.resources.discover_history_queued
import ketch.app.shared.generated.resources.discover_history_results
import ketch.app.shared.generated.resources.discover_history_searching
import ketch.app.shared.generated.resources.discover_new_search
import ketch.app.shared.generated.resources.discover_stopped
import kotlinx.datetime.TimeZone
import org.jetbrains.compose.resources.stringResource

/** Where Discover's history shows. */
internal enum class HistoryPlacement {
  /** Beside the chat, toggled from the page header; whether it shows is saved. */
  Docked,

  /** A card over the start of the chat, on pages too narrow to dock it. */
  Overlay,

  /** A bottom sheet, on phones. */
  Sheet,
}

/** Where the history of a Discover page [pageWidth] wide shows; always a sheet on [phone]s. */
internal fun historyPlacement(phone: Boolean, pageWidth: Dp): HistoryPlacement = when {
  phone -> HistoryPlacement.Sheet
  pageWidth >= DockedHistoryMinWidth -> HistoryPlacement.Docked
  else -> HistoryPlacement.Overlay
}

/**
 * Discover's history: New search, which stands for the empty chat; a filter once there are
 * [FILTER_MIN_SESSIONS] searches or more; the searches by the day they last ran, the one shown
 * selected; and Clear history, which keeps the searches still running.
 *
 * Each search names its first message and what it is doing, or how many results it has and
 * when it last ran. Clicking one shows it; its trash, shown on hover or keyboard focus (always on
 * touch), ⌫ (Delete off Apple keyboards) and the screen reader's Delete action delete it, with
 * Undo.
 *
 * @param onNewSearch shows an empty chat with the keyboard in its composer, for New search.
 * @param onPicked runs after a search or New search was picked, or the history cleared, so a
 *   floating panel can close; in a phone's sheet, which hides the toasts, also after a search
 *   was deleted, so its Undo shows.
 * @param onClose closes a floating panel; `null` for the docked one, which the header toggles.
 * @param firstFocus takes the keyboard into the panel: the filter, or else New search.
 */
@Composable
internal fun DiscoverHistoryPanel(
  state: AppState,
  placement: HistoryPlacement,
  onNewSearch: () -> Unit,
  onPicked: () -> Unit,
  onClose: (() -> Unit)?,
  modifier: Modifier = Modifier,
  firstFocus: FocusRequester? = null,
) {
  val controller = state.aiDiscover
  val sessions = controller.sessions
  val spacing = KetchTheme.spacing
  val touch = KetchTheme.density == KetchDensity.Comfortable
  var query by rememberSaveable { mutableStateOf("") }
  val filtering = sessions.size >= FILTER_MIN_SESSIONS
  val shown = if (filtering) sessions.filter { matchesHistory(it, query) } else sessions
  val now = LocalClock.current.now()
  val zone = remember { TimeZone.currentSystemDefault() }
  val groups = historyGroups(shown, now, zone)
  val waiting = controller.approvals.mapTo(mutableSetOf()) { it.sessionId }
  val sheet = placement == HistoryPlacement.Sheet
  val historyTitle = stringResource(Res.string.discover_history)
  val floating = placement != HistoryPlacement.Docked
  Column(modifier.semantics { if (floating) paneTitle = historyTitle }) {
    PanelHeader(placement, historyTitle, onClose)
    PanelItem(
      label = stringResource(Res.string.discover_new_search),
      icon = KetchIcon.Compose,
      selected = controller.currentId == null,
      onClick = {
        onNewSearch()
        onPicked()
      },
      modifier = if (firstFocus != null && !filtering) {
        Modifier.focusRequester(firstFocus)
      } else {
        Modifier
      },
    )
    if (filtering) {
      KetchTextField(
        value = query,
        onValueChange = { query = it },
        placeholder = stringResource(Res.string.discover_history_filter),
        leadingIcon = KetchIcon.Search,
        modifier = Modifier
          .fillMaxWidth()
          // As wide as the lines' fills.
          .padding(horizontal = spacing.s2, vertical = spacing.s1)
          .then(if (firstFocus != null) Modifier.focusRequester(firstFocus) else Modifier),
      )
    }
    val list = if (sheet) Modifier.weight(1f, fill = false) else Modifier.weight(1f)
    when {
      sessions.isEmpty() -> EmptyNote(stringResource(Res.string.discover_history_empty), list)
      shown.isEmpty() -> EmptyNote(
        text = stringResource(Res.string.discover_history_no_match, query.trim()),
        modifier = list,
      )
      else -> LazyColumn(
        modifier = list.fillMaxWidth(),
        contentPadding = PaddingValues(bottom = spacing.s2),
      ) {
        for ((day, members) in groups) {
          item(key = "day/${day.name}", contentType = "day") {
            KetchEyebrow(
              text = day.title.resolve(),
              modifier = Modifier
                .padding(horizontal = spacing.s4)
                .padding(top = spacing.s4, bottom = spacing.s1)
                .semantics { heading() },
            )
          }
          items(members, key = { it.id }, contentType = { "session" }) { session ->
            HistoryRow(
              session = session,
              status = historyStatus(session, waiting = session.id in waiting),
              time = historyTime(session.updatedAt, day, now, zone),
              selected = session.id == controller.currentId,
              touch = touch,
              onOpen = {
                controller.open(session.id)
                onPicked()
              },
              onDelete = {
                state.deleteDiscoverSession(session.id)
                // A phone's sheet covers the toast with Undo, so it steps aside for it.
                if (sheet) onPicked()
              },
            )
          }
        }
      }
    }
    if (sessions.isNotEmpty()) {
      Footer(
        canClear = sessions.any { !it.running },
        onClear = {
          state.clearDiscoverHistory()
          // A floating panel steps aside for the toast with Undo.
          if (placement != HistoryPlacement.Docked) onPicked()
        },
      )
    }
  }
}

/**
 * The docked history at the start of the page, [DockedHistoryWidth] wide on the card's surface,
 * with a hairline toward the chat; it slides in from the edge and pushes the chat aside.
 */
@Composable
internal fun DockedHistory(
  state: AppState,
  visible: Boolean,
  onNewSearch: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val colors = KetchTheme.colors
  val motion = KetchTheme.motion
  val enter = tween<IntSize>(motion.medium, easing = motion.easeDecelerate)
  val exit = tween<IntSize>(motion.longExit, easing = motion.easeAccelerate)
  AnimatedVisibility(
    visible = visible,
    enter = expandHorizontally(enter, Alignment.Start) +
      fadeIn(tween(motion.medium, easing = motion.easeDecelerate)),
    exit = shrinkHorizontally(exit, Alignment.Start) +
      fadeOut(tween(motion.longExit, easing = motion.easeAccelerate)),
    modifier = modifier.fillMaxHeight(),
  ) {
    Row(Modifier.fillMaxHeight()) {
      DiscoverHistoryPanel(
        state = state,
        placement = HistoryPlacement.Docked,
        onNewSearch = onNewSearch,
        onPicked = {},
        onClose = null,
        modifier = Modifier.width(DockedHistoryWidth).fillMaxHeight().background(colors.surface),
      )
      Box(Modifier.width(HairlineWidth).fillMaxHeight().background(colors.hairline))
    }
  }
}

/**
 * The history floating over the start of the chat: a raised card inset 8 dp from the page's
 * edges that slides in from 24 dp aside. Esc closes it, as picking a search does.
 */
@Composable
internal fun BoxScope.OverlayHistory(
  state: AppState,
  visible: Boolean,
  firstFocus: FocusRequester,
  onNewSearch: () -> Unit,
  onClose: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val colors = KetchTheme.colors
  val spacing = KetchTheme.spacing
  val motion = KetchTheme.motion
  val shift = with(LocalDensity.current) { spacing.s6.roundToPx() }
  AnimatedVisibility(
    visible = visible,
    enter = slideInHorizontally(tween(motion.medium, easing = motion.easeDecelerate)) { -shift } +
      fadeIn(tween(motion.medium, easing = motion.easeDecelerate)),
    exit = slideOutHorizontally(tween(motion.longExit, easing = motion.easeAccelerate)) { -shift } +
      fadeOut(tween(motion.longExit, easing = motion.easeAccelerate)),
    modifier = modifier.align(Alignment.TopStart).fillMaxHeight(),
  ) {
    DiscoverHistoryPanel(
      state = state,
      placement = HistoryPlacement.Overlay,
      onNewSearch = onNewSearch,
      onPicked = onClose,
      onClose = onClose,
      firstFocus = firstFocus,
      modifier = Modifier
        .padding(spacing.s2)
        .width(OverlayHistoryWidth)
        .fillMaxHeight()
        .ketchSurface(
          level = KetchElevationLevel.E3,
          shape = KetchTheme.shapes.lg,
          fill = colors.surfaceRaised,
          border = colors.hairline,
        ),
    )
  }
}

/** The history in a bottom sheet on phones; picking a search closes it, as Back does. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun HistorySheet(
  state: AppState,
  onNewSearch: () -> Unit,
  onDismissRequest: () -> Unit,
) {
  KetchBottomSheet(onDismissRequest = onDismissRequest) {
    DiscoverHistoryPanel(
      state = state,
      placement = HistoryPlacement.Sheet,
      onNewSearch = onNewSearch,
      onPicked = onDismissRequest,
      onClose = onDismissRequest,
      modifier = Modifier
        .fillMaxWidth()
        .windowInsetsPadding(WindowInsets.navigationBars)
        // The filter stays above the keyboard.
        .imePadding(),
    )
  }
}

/** "History": an eyebrow over the docked panel, a title with a close button elsewhere. */
@Composable
private fun PanelHeader(placement: HistoryPlacement, title: String, onClose: (() -> Unit)?) {
  val spacing = KetchTheme.spacing
  if (placement == HistoryPlacement.Docked) {
    KetchEyebrow(
      text = title,
      color = KetchTheme.colors.textSecondary,
      modifier = Modifier
        .padding(start = spacing.s4, end = spacing.s4, top = spacing.s3, bottom = spacing.s2)
        .semantics { heading() },
    )
    return
  }
  val sheet = placement == HistoryPlacement.Sheet
  val inset = if (sheet) KetchTheme.density.pagePadding else spacing.s4
  Row(
    verticalAlignment = Alignment.CenterVertically,
    modifier = Modifier
      .fillMaxWidth()
      .heightIn(min = spacing.s12)
      .padding(start = inset, end = spacing.s2),
  ) {
    Text(
      text = title,
      style = KetchTheme.typography.titleM,
      color = KetchTheme.colors.textPrimary,
      modifier = Modifier.weight(1f).semantics { heading() },
    )
    if (onClose != null) {
      KetchIconButton(
        icon = KetchIcon.Close,
        onClick = onClose,
        contentDescription = stringResource(Res.string.action_close),
      )
    }
  }
}

/**
 * One line of the panel with a glyph, such as New search, on the panel's own fills: a tab
 * [selected] while it shows, or with no [selected] a plain button, such as Clear history.
 */
@Composable
private fun PanelItem(
  label: String,
  icon: KetchIcon,
  selected: Boolean?,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
  enabled: Boolean = true,
) {
  val colors = KetchTheme.colors
  val spacing = KetchTheme.spacing
  val shape = KetchTheme.shapes.sidebarItem
  val interactions = remember { MutableInteractionSource() }
  val hovered by interactions.collectIsHoveredAsState()
  val focus = rememberFocusVisibility()
  val picked = selected == true
  val fill by animateColorAsState(
    targetValue = itemFill(picked, hovered && enabled),
    animationSpec = tween(KetchTheme.motion.micro),
  )
  val action = if (selected != null) {
    Modifier.selectable(
      selected = selected,
      interactionSource = interactions,
      indication = null,
      role = Role.Tab,
      onClick = onClick,
    )
  } else {
    Modifier.clickable(
      interactionSource = interactions,
      indication = null,
      enabled = enabled,
      role = Role.Button,
      onClick = onClick,
    )
  }
  Row(
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(spacing.iconLabelGap),
    modifier = modifier
      .fillMaxWidth()
      .padding(horizontal = spacing.s2, vertical = spacing.s0_5)
      .focusRing(focus.visible, shape, colors.focusRing)
      .heightIn(min = KetchTheme.density.sidebarItem)
      .clip(shape)
      .background(fill)
      .trackFocusVisibility(focus)
      .then(action)
      .padding(horizontal = spacing.s2),
  ) {
    KetchIconImage(
      icon = icon,
      size = KetchTheme.density.controlGlyph,
      tint = when {
        !enabled -> colors.textDisabled
        picked -> colors.accentText
        else -> colors.textSecondary
      },
    )
    Text(
      text = label,
      style = KetchTheme.typography.label,
      fontWeight = if (picked) FontWeight.SemiBold else null,
      color = if (enabled) colors.textPrimary else colors.textDisabled,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
      modifier = Modifier.weight(1f),
    )
  }
}

/**
 * A search of the history: its title over what it is doing or found and when, selected while
 * it shows, and its trash.
 */
@Composable
private fun HistoryRow(
  session: DiscoverSession,
  status: HistoryStatus,
  time: UiText,
  selected: Boolean,
  touch: Boolean,
  onOpen: () -> Unit,
  onDelete: () -> Unit,
) {
  val colors = KetchTheme.colors
  val spacing = KetchTheme.spacing
  val shape = KetchTheme.shapes.sidebarItem
  val interactions = remember { MutableInteractionSource() }
  val hovered by interactions.collectIsHoveredAsState()
  val focus = rememberFocusVisibility()
  var focusWithin by remember { mutableStateOf(false) }
  val delete by rememberUpdatedState(onDelete)
  val focusManager = LocalFocusManager.current
  val deleteLabel = stringResource(Res.string.discover_delete_search)
  val fill by animateColorAsState(
    targetValue = itemFill(selected, hovered),
    animationSpec = tween(KetchTheme.motion.micro),
  )
  Row(
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(spacing.s1),
    modifier = Modifier
      .fillMaxWidth()
      .padding(horizontal = spacing.s2, vertical = spacing.s0_5)
      .focusRing(focus.visible, shape, colors.focusRing)
      .clip(shape)
      .background(fill)
      .onFocusChanged { focusWithin = it.hasFocus }
      .onKeyEvent { event -> discardOnKey(event, focusManager, delete) }
      .semantics {
        customActions = listOf(
          CustomAccessibilityAction(deleteLabel) {
            delete()
            true
          },
        )
      }
      .trackFocusVisibility(focus)
      .selectable(
        selected = selected,
        interactionSource = interactions,
        indication = null,
        role = Role.Tab,
        onClick = onOpen,
      )
      // The title lines up with the day above it and New search's glyph.
      .padding(start = spacing.s2, end = spacing.s1, top = spacing.s2, bottom = spacing.s2),
  ) {
    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(spacing.s0_5)) {
      Text(
        text = session.title,
        style = KetchTheme.typography.label,
        fontWeight = if (selected) FontWeight.SemiBold else null,
        color = colors.textPrimary,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
      StatusLine(status, time)
    }
    // Keeps its slot while hidden, so the text never reflows under the pointer.
    val shown = touch || hovered || focusWithin
    val visibility by animateFloatAsState(
      targetValue = if (shown) 1f else 0f,
      animationSpec = tween(KetchTheme.motion.micro),
    )
    KetchIconButton(
      icon = KetchIcon.Trash,
      onClick = onDelete,
      size = KetchButtonSize.Small,
      tint = colors.textTertiary,
      contentDescription = deleteLabel,
      shortcut = if (touch) null else KetchCommands.DiscoverDiscard.shortcutLabel(),
      modifier = Modifier.alpha(visibility),
    )
  }
}

/**
 * What a search is doing, "Needs your OK" in the accent with a dot, or once it is done how many
 * results it has and when it last ran. A dot leads what a search is waiting for or doing, in the
 * colors of a download's status.
 */
@Composable
private fun StatusLine(status: HistoryStatus, time: UiText) {
  val colors = KetchTheme.colors
  val (text, color) = when (status) {
    HistoryStatus.NeedsOk -> Res.string.discover_history_needs_ok.text() to colors.accentText
    HistoryStatus.Searching -> Res.string.discover_history_searching.text() to colors.textSecondary
    HistoryStatus.Queued -> Res.string.discover_history_queued.text() to colors.textSecondary
    HistoryStatus.Failed -> Res.string.discover_history_failed.text() to colors.status.failed.color
    HistoryStatus.Stopped -> Res.string.discover_stopped.text() to colors.textSecondary
    is HistoryStatus.Results -> {
      val found = if (status.count == 0) {
        Res.string.discover_history_no_results.text()
      } else {
        Res.plurals.discover_history_results.text(status.count)
      }
      listOf(found, time).joinText() to colors.textSecondary
    }
  }
  Row(
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(KetchTheme.spacing.s1),
  ) {
    when (status) {
      HistoryStatus.NeedsOk -> KetchDot(colors.accent, size = StatusDotSize)
      HistoryStatus.Searching -> KetchDot(
        color = colors.status.downloading.color,
        size = StatusDotSize,
        pulse = !KetchTheme.reduceMotion,
      )
      // As a download waiting for a free slot shows.
      HistoryStatus.Queued -> KetchDot(colors.status.queued.color, size = StatusDotSize)
      else -> Unit
    }
    Text(
      text = text.resolve(),
      style = KetchTheme.typography.caption,
      color = color,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
    )
  }
}

/**
 * Clear history at the foot of the panel, over a hairline, its glyph in line with New search's.
 */
@Composable
private fun Footer(canClear: Boolean, onClear: () -> Unit) {
  val spacing = KetchTheme.spacing
  Column(Modifier.fillMaxWidth()) {
    Spacer(Modifier.fillMaxWidth().height(HairlineWidth).background(KetchTheme.colors.hairline))
    PanelItem(
      label = stringResource(Res.string.discover_history_clear),
      icon = KetchIcon.Trash,
      selected = null,
      onClick = onClear,
      enabled = canClear,
      modifier = Modifier.padding(vertical = spacing.s1),
    )
  }
}

/** A quiet line in the middle of the panel, such as for a history with nothing in it yet. */
@Composable
private fun EmptyNote(text: String, modifier: Modifier = Modifier) {
  Box(
    contentAlignment = Alignment.TopCenter,
    modifier = modifier.fillMaxWidth().padding(KetchTheme.spacing.s6),
  ) {
    Text(
      text = text,
      style = KetchTheme.typography.bodyS,
      color = KetchTheme.colors.textSecondary,
      textAlign = TextAlign.Center,
    )
  }
}

/**
 * The fill of a picked or hovered line. The sidebar's translucent pills only show on the canvas,
 * so on the panel's surface its own hover and pressed tones mark them.
 */
@Composable
private fun itemFill(selected: Boolean, hovered: Boolean): Color {
  val colors = KetchTheme.colors
  return when {
    selected -> colors.surfacePressed
    hovered -> colors.surfaceHover
    // Fades by alpha alone: Color.Transparent is transparent black, which flashes grey.
    else -> colors.surfaceHover.copy(alpha = 0f)
  }
}

/** Narrowest page that docks the history beside the chat. */
internal val DockedHistoryMinWidth: Dp = 780.dp

private val DockedHistoryWidth: Dp = 260.dp
private val OverlayHistoryWidth: Dp = 300.dp
private val HairlineWidth: Dp = 1.dp
private val StatusDotSize: Dp = 6.dp

/** Searches in the history from which it offers a filter. */
internal const val FILTER_MIN_SESSIONS: Int = 6
