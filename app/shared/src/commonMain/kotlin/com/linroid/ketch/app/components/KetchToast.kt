package com.linroid.ketch.app.components

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.linroid.ketch.app.feedback.AppMessage
import com.linroid.ketch.app.feedback.MessageAction
import com.linroid.ketch.app.feedback.MessageLevel
import com.linroid.ketch.app.feedback.ToastMode
import com.linroid.ketch.app.icons.KetchIcon
import com.linroid.ketch.app.icons.KetchIconImage
import com.linroid.ketch.app.theme.KetchColors
import com.linroid.ketch.app.theme.KetchElevationLevel
import com.linroid.ketch.app.theme.KetchTheme
import com.linroid.ketch.app.theme.darkKetchColors
import com.linroid.ketch.app.theme.ketchSurface
import com.linroid.ketch.app.theme.lightKetchColors
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * How long a toast stays: 4 s, or 8 s when it has actions to reach; `null` when it stays until
 * dismissed, as errors and sticky messages do.
 */
fun toastDuration(level: MessageLevel, mode: ToastMode, hasActions: Boolean): Duration? = when {
  mode == ToastMode.Sticky || level == MessageLevel.Error -> null
  hasActions -> 8.seconds
  else -> 4.seconds
}

/** [toastDuration] of [message]. */
fun toastDuration(message: AppMessage): Duration? =
  toastDuration(message.level, message.toast, message.actions.isNotEmpty())

/**
 * A toast for [message]; see the other overload. Clicking one of its actions also dismisses
 * it.
 *
 * @param detail second line; defaults to the message's own.
 */
@Composable
fun KetchToast(
  message: AppMessage,
  onDismiss: () -> Unit,
  modifier: Modifier = Modifier,
  detail: String? = message.detail,
) {
  KetchToast(
    title = message.title,
    onDismiss = onDismiss,
    modifier = modifier,
    level = message.level,
    detail = detail,
    actions = message.actions,
    duration = toastDuration(message),
  )
}

/**
 * A short message on the inverse surface, with up to two text actions.
 *
 * It calls [onDismiss] once [duration] has passed, counting only time the pointer is not on
 * it, when its ✕ or an action is clicked, or when it is swiped sideways. Screen readers
 * announce it politely, and errors at once.
 *
 * @param duration how long it stays; `null` keeps it until dismissed.
 */
@Composable
fun KetchToast(
  title: String,
  onDismiss: () -> Unit,
  modifier: Modifier = Modifier,
  level: MessageLevel = MessageLevel.Info,
  detail: String? = null,
  actions: List<MessageAction> = emptyList(),
  duration: Duration? = toastDuration(level, ToastMode.Auto, actions.isNotEmpty()),
) {
  val colors = KetchTheme.colors
  val spacing = KetchTheme.spacing
  val currentDismiss by rememberUpdatedState(onDismiss)
  val interactions = remember { MutableInteractionSource() }
  val hovered = interactions.collectIsHoveredAsState()
  if (duration != null) {
    LaunchedEffect(duration) {
      var remaining = duration
      while (remaining.isPositive()) {
        snapshotFlow { hovered.value }.first { !it }
        val start = TimeSource.Monotonic.markNow()
        val paused = withTimeoutOrNull(remaining) { snapshotFlow { hovered.value }.first { it } }
        if (paused == null) break
        remaining -= start.elapsedNow()
      }
      currentDismiss()
    }
  }
  val swipe = remember { Animatable(0f) }
  val scope = rememberCoroutineScope()
  val dismissDistance = with(LocalDensity.current) { SwipeDistance.toPx() }
  val dragState = rememberDraggableState { delta ->
    scope.launch { swipe.snapTo(swipe.value + delta) }
  }
  Row(
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(spacing.s3),
    modifier = modifier
      .graphicsLayer {
        translationX = swipe.value
        alpha = 1f - (abs(swipe.value) / dismissDistance).coerceIn(0f, 1f) * SWIPE_FADE
      }
      .draggable(
        state = dragState,
        orientation = Orientation.Horizontal,
        onDragStopped = {
          if (abs(swipe.value) >= dismissDistance) {
            currentDismiss()
          } else {
            swipe.animateTo(0f)
          }
        },
      )
      .widthIn(max = ToastMaxWidth)
      .heightIn(min = ToastHeight)
      .ketchSurface(KetchElevationLevel.E3, KetchTheme.shapes.toast, colors.inverseSurface)
      .hoverable(interactions)
      .semantics {
        liveRegion = if (level == MessageLevel.Error) {
          LiveRegionMode.Assertive
        } else {
          LiveRegionMode.Polite
        }
      }
      .padding(start = spacing.s4, end = spacing.s1, top = spacing.s2, bottom = spacing.s2),
  ) {
    val inverse = remember(colors.isDark) { inverseColors(colors.isDark) }
    KetchIconImage(
      icon = level.icon,
      size = LevelIconSize,
      tint = level.tint(inverse, colors.inverseOnSurface),
    )
    Column(Modifier.weight(1f, fill = false)) {
      Text(
        text = title,
        style = KetchTheme.typography.bodyS,
        color = colors.inverseOnSurface,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
      )
      if (detail != null) {
        Text(
          text = detail,
          style = KetchTheme.typography.caption,
          color = colors.inverseOnSurface.copy(alpha = DETAIL_ALPHA),
          maxLines = 2,
          overflow = TextOverflow.Ellipsis,
        )
      }
    }
    actions.take(MAX_ACTIONS).forEach { action ->
      ToastAction(action.label) {
        action.onClick()
        currentDismiss()
      }
    }
    ToastClose(onClick = { currentDismiss() })
  }
}

@Composable
private fun ToastAction(label: String, onClick: () -> Unit) {
  val colors = KetchTheme.colors
  val interactions = remember { MutableInteractionSource() }
  val hovered by interactions.collectIsHoveredAsState()
  Text(
    text = label,
    style = KetchTheme.typography.label,
    color = colors.inverseAccent,
    maxLines = 1,
    modifier = Modifier
      .background(
        colors.inverseOnSurface.copy(alpha = if (hovered) HOVER_OVERLAY_ALPHA else 0f),
        KetchTheme.shapes.xs,
      )
      .clickable(
        interactionSource = interactions,
        indication = null,
        role = Role.Button,
        onClick = onClick,
      )
      .padding(horizontal = KetchTheme.spacing.s2, vertical = KetchTheme.spacing.s1),
  )
}

@Composable
private fun ToastClose(onClick: () -> Unit) {
  val colors = KetchTheme.colors
  val interactions = remember { MutableInteractionSource() }
  val hovered by interactions.collectIsHoveredAsState()
  Box(
    contentAlignment = Alignment.Center,
    modifier = Modifier
      .size(CloseTarget)
      .semantics { contentDescription = "Dismiss" }
      .clickable(
        interactionSource = interactions,
        indication = null,
        role = Role.Button,
        onClick = onClick,
      ),
  ) {
    KetchIconImage(
      icon = KetchIcon.Close,
      size = CloseGlyph,
      tint = colors.inverseOnSurface.copy(alpha = if (hovered) 1f else DETAIL_ALPHA),
    )
  }
}

private val MessageLevel.icon: KetchIcon
  get() = when (this) {
    MessageLevel.Info -> KetchIcon.Info
    MessageLevel.Success -> KetchIcon.CheckCircle
    MessageLevel.Warning -> KetchIcon.Warning
    MessageLevel.Error -> KetchIcon.Failed
  }

/** Status colors read on the inverse surface: the other theme's. */
private fun MessageLevel.tint(inverse: KetchColors, neutral: Color): Color = when (this) {
  MessageLevel.Info -> neutral
  MessageLevel.Success -> inverse.status.completed.color
  MessageLevel.Warning -> inverse.status.paused.color
  MessageLevel.Error -> inverse.status.failed.color
}

private fun inverseColors(dark: Boolean): KetchColors =
  if (dark) lightKetchColors() else darkKetchColors()

private const val MAX_ACTIONS = 2
private const val DETAIL_ALPHA = 0.72f
private const val SWIPE_FADE = 0.8f
private val ToastHeight = 40.dp
private val ToastMaxWidth = 480.dp
private val LevelIconSize = 16.dp
private val CloseGlyph = 14.dp
private val CloseTarget = 28.dp
private val SwipeDistance = 96.dp
