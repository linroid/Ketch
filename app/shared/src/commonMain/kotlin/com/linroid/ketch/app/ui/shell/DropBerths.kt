package com.linroid.ketch.app.ui.shell

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.linroid.ketch.app.components.DevicePennant
import com.linroid.ketch.app.instance.DevicePresence
import com.linroid.ketch.app.instance.DeviceScope
import com.linroid.ketch.app.state.AppState
import com.linroid.ketch.app.theme.KetchTheme
import com.linroid.ketch.app.ui.DROP_OVERLAY_ALPHA
import com.linroid.ketch.app.ui.DropHoverState
import com.linroid.ketch.app.ui.DropOverlay
import com.linroid.ketch.app.ui.dashedOutline
import com.linroid.ketch.app.ui.pulse.diskLabel
import com.linroid.ketch.app.ui.sidebar.deviceLine
import com.linroid.ketch.app.ui.sidebar.pennantName
import com.linroid.ketch.app.ui.sidebar.rememberDevices

/**
 * What a drag of links, magnets or `.torrent` files from another app shows over the content
 * card while [hover] is active: one berth per device, each adding what is dropped on it there,
 * up to four in a row and more in two rows. Devices that cannot be reached show a dimmed berth
 * that turns the drop down. With a single device there is one berth for the whole window.
 *
 * The berths stay laid out, unseen, while nothing is dragged: a drop target only takes part in a
 * drag that starts while it is on screen.
 */
@Composable
internal fun DropBerths(state: AppState, hover: DropHoverState, modifier: Modifier = Modifier) {
  val devices = rememberDevices(state)
  val motion = KetchTheme.motion
  val shown = hover.active
  if (devices.size < DeviceScope.MIN_DEVICES) {
    AnimatedVisibility(
      visible = shown,
      enter = fadeIn(tween(motion.short)),
      exit = fadeOut(tween(motion.short)),
      modifier = modifier,
    ) {
      DropOverlay(compact = false)
    }
    return
  }
  val alpha by animateFloatAsState(if (shown) 1f else 0f, tween(motion.short))
  val colors = KetchTheme.colors
  val spacing = KetchTheme.spacing
  BoxWithConstraints(
    contentAlignment = Alignment.Center,
    modifier = modifier
      .fillMaxSize()
      .graphicsLayer { this.alpha = alpha }
      .then(if (shown) Modifier else Modifier.clearAndSetSemantics {})
      .background(colors.surfaceRaised.copy(alpha = DROP_OVERLAY_ALPHA))
      .padding(spacing.s4),
  ) {
    val gap = spacing.s4
    val columns = berthColumns(devices.size, maxWidth, BerthMinWidth, gap)
    val width = ((maxWidth - gap * (columns - 1)) / columns).coerceAtMost(BerthMaxWidth)
    // Unseen, the berths take no pointer input, so clicks and scrolling reach the page.
    val scroll = rememberScrollState()
    Column(
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.spacedBy(gap),
      modifier = if (shown) Modifier.verticalScroll(scroll) else Modifier,
    ) {
      for (row in devices.chunked(columns)) {
        Row(
          horizontalArrangement = Arrangement.spacedBy(gap),
          modifier = Modifier.height(IntrinsicSize.Max),
        ) {
          for (device in row) {
            key(device.deviceId) {
              val berth = remember(device.deviceId) { BerthKey(device.deviceId) }
              BerthContent(
                device = device,
                hovered = hover.isHovered(berth),
                modifier = Modifier
                  .width(width)
                  .fillMaxHeight()
                  .deviceDropTarget(state, device, acceptsRows = false, hoverKey = berth),
              )
            }
          }
        }
      }
      Text(
        text = DROP_KINDS,
        style = KetchTheme.typography.bodyS,
        color = colors.textSecondary,
        textAlign = TextAlign.Center,
        modifier = Modifier.padding(top = spacing.s2),
      )
    }
  }
}

/** What the berth of the device with [deviceId] reports to the window's [DropHoverState]. */
internal data class BerthKey(val deviceId: String)

/**
 * One device's berth: its pennant, "Drop on {name}" and its free space and activity; filled
 * with the soft accent while a drag [hovered] it. A device that cannot be reached is dimmed and
 * says why.
 */
@Composable
internal fun BerthContent(device: DevicePresence, hovered: Boolean, modifier: Modifier = Modifier) {
  val colors = KetchTheme.colors
  val spacing = KetchTheme.spacing
  val shape = KetchTheme.shapes.lg
  val reachable = device.connected && device.health.isOnline
  val lit = hovered && reachable
  val outline = if (lit) {
    Modifier.background(colors.accentSoft, shape).border(HoverOutline, colors.accent, shape)
  } else {
    Modifier.dashedOutline(colors.borderStrong, shape)
  }
  Column(
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.spacedBy(spacing.s1, Alignment.CenterVertically),
    modifier = modifier
      // Opaque, so the page under the overlay never shows through a berth.
      .background(colors.surfaceRaised, shape)
      .then(outline)
      .padding(horizontal = spacing.s4, vertical = spacing.s6),
  ) {
    val content = Modifier.alpha(if (reachable) 1f else DISABLED_ALPHA)
    DevicePennant(
      deviceId = device.deviceId,
      name = device.pennantName,
      size = spacing.s12,
      modifier = content.padding(bottom = spacing.s2),
    )
    // A narrow berth wraps before the name, not at a hyphen inside it.
    val name = device.name.replace('-', NON_BREAKING_HYPHEN)
    Text(
      text = if (reachable) "Drop on $name" else name,
      style = KetchTheme.typography.bodyStrong,
      color = if (lit) colors.accentText else colors.textPrimary,
      textAlign = TextAlign.Center,
      maxLines = 2,
      overflow = TextOverflow.Ellipsis,
      modifier = content,
    )
    Text(
      text = berthCaption(device),
      style = KetchTheme.typography.caption,
      color = colors.textSecondary,
      textAlign = TextAlign.Center,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
      modifier = content,
    )
  }
}

/**
 * The second line of [device]'s berth: "1.8 TB free · 3 active" while it can take a drop,
 * otherwise why it cannot.
 */
internal fun berthCaption(device: DevicePresence): String {
  if (!device.connected) return "Not connected"
  if (!device.health.isOnline) return deviceLine(device).text
  val parts = listOfNotNull(
    device.disk?.let(::diskLabel),
    device.counts.downloading.takeIf { it > 0 }?.let { "$it active" }
  )
  return parts.joinToString(" · ").ifEmpty { "Ready" }
}

/**
 * Columns of the berth grid for [count] berths [width] wide: up to [MAX_BERTH_COLUMNS] in a row,
 * more in two rows, and fewer where they would be narrower than [minWidth] with [gap] between.
 */
internal fun berthColumns(count: Int, width: Dp, minWidth: Dp, gap: Dp): Int {
  if (count <= 1) return 1
  val wanted = if (count <= MAX_BERTH_COLUMNS) count else (count + 1) / 2
  val fitting = ((width + gap) / (minWidth + gap)).toInt().coerceAtLeast(1)
  return minOf(wanted, MAX_BERTH_COLUMNS, fitting)
}

private const val DROP_KINDS = "Links, magnets, .torrent files and lists of links"
private const val MAX_BERTH_COLUMNS = 4
private const val DISABLED_ALPHA = 0.5f
private const val NON_BREAKING_HYPHEN = '\u2011'

private val BerthMinWidth: Dp = 140.dp
private val BerthMaxWidth: Dp = 240.dp
private val HoverOutline: Dp = 2.dp
