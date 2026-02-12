package com.linroid.kdown.app.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.linroid.kdown.remote.ConnectionState

@Composable
fun ConnectionStatusDot(state: ConnectionState) {
  val color = when (state) {
    is ConnectionState.Connected ->
      MaterialTheme.colorScheme.tertiary
    is ConnectionState.Connecting ->
      MaterialTheme.colorScheme.secondary
    is ConnectionState.Disconnected ->
      MaterialTheme.colorScheme.error
  }
  Box(
    modifier = Modifier
      .size(8.dp)
      .clip(CircleShape)
      .background(color)
  )
}

@Composable
fun ConnectionStatusChip(
  state: ConnectionState,
  isActive: Boolean = false,
) {
  val (label, bgColor, textColor) = when (state) {
    is ConnectionState.Connected -> Triple(
      "Connected",
      MaterialTheme.colorScheme.tertiaryContainer,
      MaterialTheme.colorScheme.onTertiaryContainer
    )
    is ConnectionState.Connecting -> Triple(
      "Connecting",
      MaterialTheme.colorScheme.secondaryContainer,
      MaterialTheme.colorScheme.onSecondaryContainer
    )
    is ConnectionState.Disconnected -> if (isActive) {
      Triple(
        "Disconnected",
        MaterialTheme.colorScheme.errorContainer,
        MaterialTheme.colorScheme.onErrorContainer
      )
    } else {
      Triple(
        "Not connected",
        MaterialTheme.colorScheme.surfaceVariant,
        MaterialTheme.colorScheme.onSurfaceVariant
      )
    }
  }
  Box(
    modifier = Modifier
      .background(
        color = bgColor,
        shape = MaterialTheme.shapes.small,
      )
      .padding(horizontal = 6.dp, vertical = 2.dp),
  ) {
    Text(
      text = label,
      style = MaterialTheme.typography.labelSmall,
      color = textColor,
    )
  }
}
