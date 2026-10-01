package com.linroid.ketch.app.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.linroid.ketch.app.icons.KetchIcon
import com.linroid.ketch.app.icons.KetchIconImage
import com.linroid.ketch.app.state.DeviceHealth
import com.linroid.ketch.app.theme.KetchColors
import com.linroid.ketch.app.theme.KetchTheme

/** Sizes of a [DevicePennant]. */
object DevicePennantDefaults {
  /** List rows, the add sheet's device chip and stacked pennants. */
  val XSmall: Dp = 16.dp

  /** Compact device chips. */
  val Small: Dp = 20.dp

  /** Sidebar device rows. */
  val Medium: Dp = 24.dp

  /** Device cards and the phone top bar. */
  val Large: Dp = 32.dp

  /** The rail. */
  val XLarge: Dp = 40.dp
}

/**
 * A device's flag: a circle in the device's own hue with a white two-letter monogram of [name]
 * ("NB" for NAS-Basement), or a device-type [icon] instead.
 *
 * With a [health], a 2 dp ring 2 dp out from the circle shows how well the app is connected; a
 * halo pulses out of it while connecting. The ring stands for health only. A failure count over
 * zero adds a badge at the top end. The pennant takes the ring's room too, so a 24 dp pennant
 * with a ring is 32 dp across.
 *
 * @param deviceId picks the hue; the same id always gets the same one.
 * @param name what the monogram is made from; the host name for the embedded device.
 * @param size diameter of the circle, one of the [DevicePennantDefaults] sizes.
 */
@Composable
fun DevicePennant(
  deviceId: String,
  name: String,
  modifier: Modifier = Modifier,
  size: Dp = DevicePennantDefaults.Medium,
  health: DeviceHealth? = null,
  failures: Int = 0,
  icon: KetchIcon? = null,
) {
  val colors = KetchTheme.colors
  val motion = KetchTheme.motion
  val fill = colors.deviceHue(deviceId).light
  val ringColor = health?.let { colors.healthColor(it) }
  val pulse = rememberPulse(health == DeviceHealth.Connecting)
  val ringRoom = if (health != null) RingGap + RingWidth else 0.dp
  Box(
    contentAlignment = Alignment.Center,
    modifier = modifier
      .size(size + ringRoom * 2)
      .clearAndSetSemantics {
        if (failures > 0) contentDescription = "$failures failed"
      }
      .drawWithCache {
        val ring = Stroke(RingWidth.toPx())
        val radius = this.size.minDimension / 2 - ring.width / 2
        onDrawBehind {
          if (ringColor == null) return@onDrawBehind
          drawCircle(ringColor, radius, style = ring)
          if (pulse == null) return@onDrawBehind
          val progress = pulse.value
          drawCircle(
            color = ringColor,
            radius = radius + motion.pulseGrowth.toPx() * progress,
            alpha = motion.pulseAlpha * (1f - progress),
            style = ring,
          )
        }
      },
  ) {
    Box(
      contentAlignment = Alignment.Center,
      modifier = Modifier.size(size).background(fill, KetchTheme.shapes.full),
    ) {
      if (icon != null) {
        KetchIconImage(icon, size = size * ICON_SHARE, tint = Color.White)
      } else {
        val fontSize = with(LocalDensity.current) { (size * MONOGRAM_SHARE).toSp() }
        Text(
          text = monogram(name),
          style = KetchTheme.typography.label,
          fontSize = fontSize,
          lineHeight = fontSize,
          fontWeight = FontWeight.SemiBold,
          color = Color.White,
          textAlign = TextAlign.Center,
          maxLines = 1,
        )
      }
    }
    if (failures > 0) {
      Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
          .align(Alignment.TopEnd)
          .offset(x = BadgeOverhang, y = -BadgeOverhang)
          .sizeIn(minWidth = BadgeSize, minHeight = BadgeSize)
          .background(colors.dangerFill, KetchTheme.shapes.badge),
      ) {
        Text(
          text = failureBadge(failures),
          style = KetchTheme.typography.numeralS,
          color = Color.White,
          maxLines = 1,
        )
      }
    }
  }
}

/** Health of a device as its ring and dot show it. */
fun KetchColors.healthColor(health: DeviceHealth): Color = when (health) {
  is DeviceHealth.Local, DeviceHealth.Live -> status.completed.color
  DeviceHealth.Connecting -> status.paused.color
  is DeviceHealth.Offline, DeviceHealth.Unauthorized -> status.failed.color
}

/**
 * Two letters standing for [name]: the initials of its first two words ("NAS-Basement" gives
 * "NB"), or of a single word its first letter and its next capital or second letter
 * ("MacBook" gives "MB").
 */
internal fun monogram(name: String): String {
  val words = name.split(WordBreak).filter { it.isNotEmpty() }
  val letters = when {
    words.isEmpty() -> return "?"
    words.size >= 2 -> "${words[0].first()}${words[1].first()}"
    else -> {
      val word = words[0]
      val second = word.drop(1).firstOrNull { it.isUpperCase() } ?: word.getOrNull(1)
      if (second != null) "${word.first()}$second" else "${word.first()}"
    }
  }
  return letters.uppercase()
}

/** A failure count as its badge shows it: up to 9, then "9+". */
internal fun failureBadge(failures: Int): String =
  if (failures > MAX_BADGE_COUNT) "$MAX_BADGE_COUNT+" else failures.toString()

private val WordBreak = Regex("""[^\p{L}\p{N}]+""")
private const val MAX_BADGE_COUNT = 9
private const val MONOGRAM_SHARE = 0.4f
private const val ICON_SHARE = 0.55f

private val RingGap = 2.dp
private val RingWidth = 2.dp
private val BadgeSize = 14.dp
private val BadgeOverhang = 2.dp
