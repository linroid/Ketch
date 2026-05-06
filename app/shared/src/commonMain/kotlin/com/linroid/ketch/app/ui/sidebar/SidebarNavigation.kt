package com.linroid.ketch.app.ui.sidebar

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.linroid.ketch.app.components.KetchSidebarItem
import com.linroid.ketch.app.icons.KetchIcon
import com.linroid.ketch.app.icons.KetchIconImage
import com.linroid.ketch.app.instance.EmbeddedInstance
import com.linroid.ketch.app.instance.InstanceEntry
import com.linroid.ketch.app.instance.RemoteInstance
import com.linroid.ketch.app.state.StatusFilter
import com.linroid.ketch.app.theme.KetchTheme
import com.linroid.ketch.remote.ConnectionState

private val SIDEBAR_WIDTH = 220.dp

@Composable
fun SidebarNavigation(
  selectedFilter: StatusFilter,
  taskCounts: Map<StatusFilter, Int>,
  onFilterSelect: (StatusFilter) -> Unit,
  activeInstance: InstanceEntry?,
  connectionState: ConnectionState?,
  onInstanceClick: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val colors = KetchTheme.colors

  Column(
    modifier = modifier
      .width(SIDEBAR_WIDTH)
      .fillMaxHeight()
      .background(colors.surfaceVariant),
  ) {
    // Brand header — keeps the macOS traffic-light insets that desktop windows
    // inject. Padding-left is generous so the wordmark clears the lights even
    // when the host doesn't insert one.
    Box(
      modifier = Modifier
        .fillMaxWidth()
        .height(48.dp)
        .padding(start = 78.dp, end = 12.dp),
      contentAlignment = Alignment.CenterStart,
    ) {
      Wordmark()
    }

    // Connection pill (clickable → instance selector).
    InstancePill(
      activeInstance = activeInstance,
      connectionState = connectionState,
      onClick = onInstanceClick,
      modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
    )

    Spacer(Modifier.height(12.dp))

    // Queue group.
    SectionLabel("Queue")
    StatusFilter.entries.forEach { filter ->
      val count = taskCounts[filter] ?: 0
      KetchSidebarItem(
        label = filter.label,
        icon = filterIcon(filter),
        selected = selectedFilter == filter,
        onClick = { onFilterSelect(filter) },
        count = if (count > 0) count else null,
      )
    }

    Spacer(Modifier.weight(1f))
    HorizontalDivider(
      modifier = Modifier.padding(horizontal = 16.dp),
      color = colors.outlineVariant,
    )
  }
}

@Composable
private fun Wordmark() {
  val colors = KetchTheme.colors
  Row(
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    Box(
      contentAlignment = Alignment.Center,
      modifier = Modifier
        .size(20.dp)
        .clip(RoundedCornerShape(6.dp))
        .background(colors.primary),
    ) {
      Text(
        text = "K",
        style = KetchTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
        color = androidx.compose.ui.graphics.Color.White,
      )
    }
    Text(
      text = "Ketch",
      style = KetchTheme.typography.displaySmall.copy(
        fontSize = 16.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = (-0.3).sp,
      ),
      color = colors.onBackground,
    )
  }
}

@Composable
private fun InstancePill(
  activeInstance: InstanceEntry?,
  connectionState: ConnectionState?,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val colors = KetchTheme.colors
  val type = KetchTheme.typography
  val (kindLabel, addressLabel) = when (activeInstance) {
    is RemoteInstance -> "Remote daemon" to "${activeInstance.host}:${activeInstance.port}"
    is EmbeddedInstance -> "Local daemon" to (activeInstance.label.ifBlank { "in-process" })
    else -> "Not connected" to "Tap to add a daemon"
  }
  val dotColor = when (connectionState) {
    is ConnectionState.Connected -> colors.success
    is ConnectionState.Connecting -> colors.warning
    is ConnectionState.Disconnected -> colors.error
    is ConnectionState.Unauthorized -> colors.error
    null -> if (activeInstance is EmbeddedInstance) colors.success else colors.onSurfaceDim
  }

  Row(
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(8.dp),
    modifier = modifier
      .fillMaxWidth()
      .clip(RoundedCornerShape(6.dp))
      .background(colors.background)
      .border(1.dp, colors.outline, RoundedCornerShape(6.dp))
      .clickable(onClick = onClick)
      .padding(horizontal = 10.dp, vertical = 6.dp),
  ) {
    Box(
      modifier = Modifier
        .size(7.dp)
        .clip(CircleShape)
        .background(dotColor)
        .border(3.dp, dotColor.copy(alpha = 0.18f), CircleShape),
    )
    Column(modifier = Modifier.weight(1f)) {
      Text(
        text = kindLabel,
        style = type.labelSmall,
        color = colors.onSurfaceDim,
      )
      Text(
        text = addressLabel,
        style = type.bodyMedium.copy(fontWeight = FontWeight.Medium),
        color = colors.onBackground,
        maxLines = 1,
        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
      )
    }
    KetchIconImage(
      icon = KetchIcon.ChevronDown,
      size = 12.dp,
      tint = colors.onSurfaceDim,
    )
  }
}

@Composable
private fun SectionLabel(text: String) {
  Text(
    text = text.uppercase(),
    style = KetchTheme.typography.labelSmall.copy(
      fontWeight = FontWeight.SemiBold,
      letterSpacing = 0.6.sp,
    ),
    color = KetchTheme.colors.onSurfaceDim,
    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
  )
}

internal fun filterIcon(filter: StatusFilter): KetchIcon = when (filter) {
  StatusFilter.All -> KetchIcon.All
  StatusFilter.Downloading -> KetchIcon.Active
  StatusFilter.Paused -> KetchIcon.Pause
  StatusFilter.Completed -> KetchIcon.Done
  StatusFilter.Failed -> KetchIcon.Failed
}
