package com.linroid.ketch.app.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.linroid.ketch.app.components.KetchBadge
import com.linroid.ketch.app.components.KetchBadgeTone
import com.linroid.ketch.app.components.KetchDot
import com.linroid.ketch.app.theme.KetchTheme
import com.linroid.ketch.remote.ConnectionState

/** Dot in the health color of a remote device's connection; it pulses while connecting. */
@Composable
fun ConnectionStatusDot(state: ConnectionState, modifier: Modifier = Modifier) {
  KetchDot(
    color = KetchTheme.colors.healthColor(state),
    modifier = modifier,
    pulse = state is ConnectionState.Connecting,
  )
}

/**
 * Badge naming a remote device's connection [state].
 *
 * @param isActive whether the device is the active one, whose lost connection reads
 *   "Disconnected" rather than "Not connected".
 */
@Composable
fun ConnectionStatusChip(
  state: ConnectionState,
  isActive: Boolean = false,
) {
  val (label, tone) = when (state) {
    is ConnectionState.Connected -> "Connected" to KetchBadgeTone.Success
    is ConnectionState.Connecting -> "Connecting" to KetchBadgeTone.Warning
    is ConnectionState.Disconnected -> if (isActive) {
      "Disconnected" to KetchBadgeTone.Danger
    } else {
      "Not connected" to KetchBadgeTone.Neutral
    }
    is ConnectionState.Unauthorized -> "Unauthorized" to KetchBadgeTone.Danger
  }
  KetchBadge(text = label, tone = tone)
}
