package com.linroid.ketch.app.ui.feedback

import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.linroid.ketch.app.components.DevicePennant
import com.linroid.ketch.app.components.DevicePennantDefaults
import com.linroid.ketch.app.components.KetchButton
import com.linroid.ketch.app.components.KetchButtonSize
import com.linroid.ketch.app.components.KetchButtonVariant
import com.linroid.ketch.app.components.KetchDot
import com.linroid.ketch.app.components.KetchEyebrow
import com.linroid.ketch.app.components.focusRing
import com.linroid.ketch.app.components.ketchClickable
import com.linroid.ketch.app.components.rememberFocusVisibility
import com.linroid.ketch.app.components.rememberInteractionOverlay
import com.linroid.ketch.app.feedback.AppMessage
import com.linroid.ketch.app.feedback.MessageLevel
import com.linroid.ketch.app.i18n.UiText
import com.linroid.ketch.app.i18n.clockTime
import com.linroid.ketch.app.i18n.resolve
import com.linroid.ketch.app.i18n.shortDateText
import com.linroid.ketch.app.i18n.text
import com.linroid.ketch.app.i18n.verbatim
import com.linroid.ketch.app.icons.KetchIcon
import com.linroid.ketch.app.icons.KetchIconImage
import com.linroid.ketch.app.instance.InstanceEntry
import com.linroid.ketch.app.instance.displayName
import com.linroid.ketch.app.state.AppState
import com.linroid.ketch.app.state.LocalClock
import com.linroid.ketch.app.state.TaskKey
import com.linroid.ketch.app.state.deviceId
import com.linroid.ketch.app.theme.KetchColors
import com.linroid.ketch.app.theme.KetchTheme
import com.linroid.ketch.app.ui.pulse.PopoverAlignment
import com.linroid.ketch.app.ui.pulse.PulsePopover
import ketch.app.shared.generated.resources.Res
import ketch.app.shared.generated.resources.activity_clear
import ketch.app.shared.generated.resources.activity_earlier
import ketch.app.shared.generated.resources.activity_empty_body
import ketch.app.shared.generated.resources.activity_empty_title
import ketch.app.shared.generated.resources.activity_mark_all_read
import ketch.app.shared.generated.resources.activity_title
import ketch.app.shared.generated.resources.activity_unread
import ketch.app.shared.generated.resources.date_today
import ketch.app.shared.generated.resources.date_yesterday_at
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.TimeZone
import kotlinx.datetime.minus
import kotlinx.datetime.toLocalDateTime
import org.jetbrains.compose.resources.stringResource
import kotlin.time.Instant

/**
 * The Activity history (`⌘J`, the Pulse bar's bell): what the app reported, newest first and
 * grouped Today and Earlier, with "Mark all read" and "Clear". It opens 380 dp wide above its
 * anchor, lined up with its end, or as a bottom sheet on touch.
 *
 * Entries since the history was last read are marked; closing it, however it closes, marks them
 * all read. An entry names its device when that is not the active one, and clicking an entry
 * about a task on the active device shows that task. Its buttons stay while it is still on
 * screen as a toast, and using one takes the toast away; after that only failures and warnings
 * keep theirs, since an Undo can expire.
 */
@Composable
fun ActivityPopover(
  state: AppState,
  expanded: Boolean,
  onDismissRequest: () -> Unit,
  modifier: Modifier = Modifier,
) {
  PulsePopover(
    expanded = expanded,
    onDismissRequest = onDismissRequest,
    width = PopoverWidth,
    modifier = modifier,
    alignment = PopoverAlignment.End,
  ) {
    // Also covers ⌘J and the bell closing it, which do not go through onDismissRequest.
    DisposableEffect(state.messages) {
      onDispose { state.messages.markAllRead() }
    }
    val history by state.messages.history.collectAsState()
    val active by state.messages.active.collectAsState()
    val unread by state.messages.unreadCount.collectAsState()
    val instances by state.instances.collectAsState()
    val current by state.activeInstance.collectAsState()
    ActivityContent(
      history = history,
      active = active,
      unread = unread,
      deviceName = { id -> deviceName(instances, id) },
      pennantName = { id -> instances.firstOrNull { it.deviceId == id }?.label ?: id },
      activeDeviceId = current?.deviceId,
      onMarkAllRead = { state.messages.markAllRead() },
      onClear = { state.messages.clear() },
      onShowTask = { key ->
        state.showDownloads()
        state.inspect(key)
        onDismissRequest()
      },
      onActionUsed = state.messages::dismiss,
    )
  }
}

/**
 * The body of the [ActivityPopover], drawn from its values.
 *
 * @param pennantName what a device's pennant monogram is made from: the host name of the
 *   embedded device rather than "This Mac".
 * @param onActionUsed called with the id of an entry whose button was used, to take its toast
 *   away as the toast's own button does.
 */
@Composable
internal fun ActivityContent(
  history: List<AppMessage>,
  active: List<AppMessage>,
  unread: Int,
  deviceName: (String) -> UiText,
  activeDeviceId: String?,
  onMarkAllRead: () -> Unit,
  onClear: () -> Unit,
  onShowTask: (TaskKey) -> Unit,
  pennantName: (String) -> String = { it },
  onActionUsed: (Long) -> Unit = {},
  now: Instant = LocalClock.current.now(),
  timeZone: TimeZone = TimeZone.currentSystemDefault(),
) {
  val colors = KetchTheme.colors
  val spacing = KetchTheme.spacing
  Row(
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(spacing.s1),
    modifier = Modifier
      .fillMaxWidth()
      .heightIn(min = KetchTheme.density.buttonSmall)
      .padding(bottom = spacing.s2),
  ) {
    Text(
      text = stringResource(Res.string.activity_title),
      style = KetchTheme.typography.titleM,
      color = colors.textPrimary,
      modifier = Modifier.weight(1f),
    )
    if (unread > 0) {
      KetchButton(
        text = stringResource(Res.string.activity_mark_all_read),
        onClick = onMarkAllRead,
        variant = KetchButtonVariant.Ghost,
        size = KetchButtonSize.Small,
      )
    }
    if (history.isNotEmpty()) {
      KetchButton(
        text = stringResource(Res.string.activity_clear),
        onClick = onClear,
        variant = KetchButtonVariant.Ghost,
        size = KetchButtonSize.Small,
      )
    }
  }
  if (history.isEmpty()) {
    EmptyActivity()
    return
  }
  val groups = remember(history, now, timeZone) { activityGroups(history, now, timeZone) }
  val unreadIds = remember(history, unread) { history.take(unread).mapTo(HashSet()) { it.id } }
  val activeIds = remember(active) { active.mapTo(HashSet()) { it.id } }
  LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = ListMaxHeight)) {
    groups.forEach { (day, messages) ->
      item(key = "group-${day.name}") {
        KetchEyebrow(day.title.resolve(), Modifier.padding(top = spacing.s2, bottom = spacing.s1))
      }
      items(messages, key = { it.id }) { message ->
        val taskKey = message.taskKey?.takeIf { it.deviceId == activeDeviceId }
        ActivityEntry(
          message = message,
          time = activityTime(message.at, now, timeZone),
          unread = message.id in unreadIds,
          device = message.deviceId?.takeIf { it != activeDeviceId }
            ?.let { EntryDevice(it, deviceName(it), pennantName(it)) },
          showActions = showsActions(message, message.id in activeIds),
          onClick = taskKey?.let { key -> { onShowTask(key) } },
          onActionUsed = { onActionUsed(message.id) },
        )
      }
    }
  }
}

@Composable
private fun ActivityEntry(
  message: AppMessage,
  time: UiText,
  unread: Boolean,
  device: EntryDevice?,
  showActions: Boolean,
  onClick: (() -> Unit)?,
  onActionUsed: () -> Unit,
) {
  val colors = KetchTheme.colors
  val spacing = KetchTheme.spacing
  val shape = KetchTheme.shapes.sm
  val interactions = remember { MutableInteractionSource() }
  val overlay = rememberInteractionOverlay(interactions, enabled = onClick != null)
  val focus = rememberFocusVisibility()
  val clickable = if (onClick != null) {
    Modifier
      .ketchClickable(interactions, focus, onClick = onClick)
  } else {
    Modifier
  }
  Row(
    horizontalArrangement = Arrangement.spacedBy(spacing.s2),
    modifier = Modifier
      .fillMaxWidth()
      // Drawn inside the entry, since the list clips anything outside it.
      .focusRing(focus.visible, shape, colors.focusRing, gap = -spacing.s0_5)
      .clip(shape)
      .background(overlay)
      .then(clickable)
      .padding(horizontal = spacing.s2, vertical = spacing.s2),
  ) {
    Box(Modifier.padding(top = spacing.s0_5)) {
      KetchIconImage(
        icon = message.level.icon,
        size = LevelIconSize,
        tint = message.level.tint(colors),
      )
    }
    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(spacing.s0_5)) {
      Row {
        Text(
          text = message.title.resolve(),
          style = KetchTheme.typography.bodyS,
          color = colors.textPrimary,
          maxLines = 2,
          overflow = TextOverflow.Ellipsis,
          modifier = Modifier.weight(1f).alignByBaseline(),
        )
        Spacer(Modifier.size(spacing.s2))
        // The dot and the time sit on the title's first line, however many lines it takes.
        Row(
          verticalAlignment = Alignment.CenterVertically,
          modifier = Modifier.alignByBaseline(),
        ) {
          if (unread) {
            val description = stringResource(Res.string.activity_unread)
            KetchDot(
              color = colors.accent,
              size = UnreadDotSize,
              modifier = Modifier
                .padding(end = spacing.s1)
                .semantics { contentDescription = description },
            )
          }
          Text(
            text = time.resolve(),
            // Tabular figures, so the times and their dots line up down the list.
            style = KetchTheme.typography.caption.copy(fontFeatureSettings = "tnum"),
            color = if (unread) colors.accentText else colors.textTertiary,
            maxLines = 1,
          )
        }
      }
      val detail = toastDetail(message)?.resolve()
      if (detail != null) {
        Text(
          text = detail,
          style = KetchTheme.typography.caption,
          color = if (message.level == MessageLevel.Error) {
            colors.status.failed.color
          } else {
            colors.textSecondary
          },
          maxLines = 2,
          overflow = TextOverflow.Ellipsis,
        )
      }
      if (device != null) {
        Row(
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(spacing.s1),
          modifier = Modifier.padding(top = spacing.s0_5),
        ) {
          DevicePennant(
            deviceId = device.id,
            name = device.pennantName,
            size = DevicePennantDefaults.XSmall,
          )
          Text(
            text = device.name.resolve(),
            style = KetchTheme.typography.caption,
            color = colors.textSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
        }
      }
      if (showActions) {
        Row(
          horizontalArrangement = Arrangement.spacedBy(spacing.s1),
          modifier = Modifier.padding(top = spacing.s1),
        ) {
          message.actions.forEach { action ->
            KetchButton(
              text = action.label.resolve(),
              onClick = {
                action.onClick()
                onActionUsed()
              },
              variant = KetchButtonVariant.Secondary,
              size = KetchButtonSize.Small,
            )
          }
        }
      }
    }
  }
}

@Composable
private fun EmptyActivity() {
  val colors = KetchTheme.colors
  val spacing = KetchTheme.spacing
  Column(
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.spacedBy(spacing.s2),
    modifier = Modifier.fillMaxWidth().padding(vertical = spacing.s8),
  ) {
    KetchIconImage(icon = KetchIcon.Bell, size = EmptyIconSize, tint = colors.textTertiary)
    Text(
      text = stringResource(Res.string.activity_empty_title),
      style = KetchTheme.typography.bodyStrong,
      color = colors.textPrimary,
    )
    Text(
      text = stringResource(Res.string.activity_empty_body),
      style = KetchTheme.typography.caption,
      color = colors.textSecondary,
      textAlign = TextAlign.Center,
      modifier = Modifier.padding(horizontal = spacing.s4),
    )
  }
}

/** A device an Activity entry names, with what its pennant's monogram is made from. */
private class EntryDevice(val id: String, val name: UiText, val pennantName: String)

/** A group of the Activity history, by the day its entries were posted. */
internal enum class ActivityDay(val title: UiText) {
  Today(Res.string.date_today.text()),
  Earlier(Res.string.activity_earlier.text()),
}

/** [history], newest first, split into Today and Earlier by the local day of [now]. */
internal fun activityGroups(
  history: List<AppMessage>,
  now: Instant,
  timeZone: TimeZone,
): List<Pair<ActivityDay, List<AppMessage>>> {
  val today = now.toLocalDateTime(timeZone).date
  val (todays, earlier) = history.partition { it.at.toLocalDateTime(timeZone).date == today }
  return listOfNotNull(
    todays.takeIf { it.isNotEmpty() }?.let { ActivityDay.Today to it },
    earlier.takeIf { it.isNotEmpty() }?.let { ActivityDay.Earlier to it }
  )
}

/** When an entry happened: "14:02" today, "Yesterday 18:20", or "Sep 28" before. */
internal fun activityTime(at: Instant, now: Instant, timeZone: TimeZone): UiText {
  val time = at.toLocalDateTime(timeZone)
  val today = now.toLocalDateTime(timeZone).date
  return when (time.date) {
    today -> verbatim(clockTime(time))
    today.minus(1, DateTimeUnit.DAY) -> Res.string.date_yesterday_at.text(clockTime(time))
    else -> shortDateText(time.date, today)
  }
}

/**
 * Whether the Activity history shows the buttons of [message]: while it is still [onScreen]
 * as a toast, or for a failure or warning, whose Retry stays useful. An Undo can expire.
 */
internal fun showsActions(message: AppMessage, onScreen: Boolean): Boolean =
  message.actions.isNotEmpty() &&
    (onScreen || message.level == MessageLevel.Error || message.level == MessageLevel.Warning)

/** How the app names the device [deviceId]: "This Mac" for the embedded one. */
internal fun deviceName(instances: List<InstanceEntry>, deviceId: String): UiText =
  instances.firstOrNull { it.deviceId == deviceId }?.displayName ?: verbatim(deviceId)

internal val MessageLevel.icon: KetchIcon
  get() = when (this) {
    MessageLevel.Info -> KetchIcon.Info
    MessageLevel.Success -> KetchIcon.CheckCircle
    MessageLevel.Warning -> KetchIcon.Warning
    MessageLevel.Error -> KetchIcon.Failed
  }

internal fun MessageLevel.tint(colors: KetchColors): Color = when (this) {
  MessageLevel.Info -> colors.textSecondary
  MessageLevel.Success -> colors.status.completed.color
  MessageLevel.Warning -> colors.status.paused.color
  MessageLevel.Error -> colors.status.failed.color
}

private val UnreadDotSize = 6.dp
private val PopoverWidth = 380.dp
private val ListMaxHeight = 440.dp
private val LevelIconSize = 16.dp
private val EmptyIconSize = 24.dp
