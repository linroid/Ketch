package com.linroid.ketch.app.ui.downloads

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Dp
import com.linroid.ketch.app.components.DevicePennant
import com.linroid.ketch.app.components.DevicePennantDefaults
import com.linroid.ketch.app.state.AppState
import com.linroid.ketch.app.state.TaskRow
import com.linroid.ketch.app.state.deviceId
import com.linroid.ketch.app.theme.KetchTheme

/**
 * The devices the Downloads list shows rows of, so each row can say which device it is on.
 *
 * @property ids the shown devices, in order.
 * @property pennantNames what each device's pennant monogram is made from, by device id: the
 *   host name of the embedded device rather than "This Mac".
 */
@Immutable
internal data class ShownDevices(
  val ids: List<String>,
  val pennantNames: Map<String, String>,
) {
  /** Whether rows of more than one device show, so rows name their device. */
  val several: Boolean get() = ids.size > 1

  /** What the pennant of the device [deviceId] is made from. */
  fun pennantName(deviceId: String, fallback: String): String = pennantNames[deviceId] ?: fallback
}

/** The devices the Downloads list around this composition shows; one by default. */
internal val LocalShownDevices = compositionLocalOf { ShownDevices(emptyList(), emptyMap()) }

/** The devices [state] shows, as rows and the header name them. */
@Composable
internal fun rememberShownDevices(state: AppState): ShownDevices {
  val shown by state.shownInstances.collectAsState()
  val instances by state.instances.collectAsState()
  return remember(shown, instances) {
    ShownDevices(
      ids = shown.map { it.deviceId },
      pennantNames = instances.associate { it.deviceId to it.label },
    )
  }
}

/** The 16 dp pennant of the device that runs [row], before its name under All devices. */
@Composable
internal fun RowPennant(row: TaskRow, modifier: Modifier = Modifier) {
  val devices = LocalShownDevices.current
  DevicePennant(
    deviceId = row.key.deviceId,
    name = devices.pennantName(row.key.deviceId, row.deviceName),
    size = DevicePennantDefaults.XSmall,
    modifier = modifier,
  )
}

/**
 * Up to three device pennants stacked, each tucked a quarter under the one before, as All
 * devices shows them. A ring in the surface color sets each apart from the next.
 *
 * @param devices device ids and the names their monograms are made from, in order.
 */
@Composable
internal fun StackedPennants(
  devices: List<Pair<String, String>>,
  modifier: Modifier = Modifier,
  size: Dp = DevicePennantDefaults.XSmall,
) {
  val colors = KetchTheme.colors
  val shown = devices.take(MAX_STACKED)
  val step = size * 3 / 4
  Box(
    modifier = modifier
      .size(width = size + step * (shown.size - 1).coerceAtLeast(0), height = size)
      .clearAndSetSemantics {},
  ) {
    // The first device stays in front, so its pennant reads whole.
    shown.withIndex().reversed().forEach { (index, device) ->
      val (id, name) = device
      DevicePennant(
        deviceId = id,
        name = name,
        size = size,
        modifier = Modifier
          .padding(start = step * index)
          .border(StackRing, colors.surface, KetchTheme.shapes.full),
      )
    }
  }
}

private const val MAX_STACKED = 3
private val StackRing: Dp = HairlineWidth
