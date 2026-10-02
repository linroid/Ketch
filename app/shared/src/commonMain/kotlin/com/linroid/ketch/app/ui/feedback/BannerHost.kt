package com.linroid.ketch.app.ui.feedback

import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.linroid.ketch.api.log.KetchLogger
import com.linroid.ketch.api.log.describeCauses
import com.linroid.ketch.app.components.KetchButton
import com.linroid.ketch.app.components.KetchButtonSize
import com.linroid.ketch.app.components.KetchButtonVariant
import com.linroid.ketch.app.components.KetchIconButton
import com.linroid.ketch.app.components.KetchSpinner
import com.linroid.ketch.app.feedback.AppMessage
import com.linroid.ketch.app.feedback.MessageAction
import com.linroid.ketch.app.feedback.MessageLevel
import com.linroid.ketch.app.feedback.MessagePlacement
import com.linroid.ketch.app.icons.KetchIcon
import com.linroid.ketch.app.icons.KetchIconImage
import com.linroid.ketch.app.instance.EmbeddedInstance
import com.linroid.ketch.app.instance.RemoteInstance
import com.linroid.ketch.app.platform.localDeviceNoun
import com.linroid.ketch.app.state.AppState
import com.linroid.ketch.app.state.DeviceHealth
import com.linroid.ketch.app.state.deviceId
import com.linroid.ketch.app.theme.KetchColors
import com.linroid.ketch.app.theme.KetchTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

private val log = KetchLogger("BannerHost")

/** Color of a [Banner]. */
internal enum class BannerTone {
  /** The soft accent, for news. */
  Info,

  /** The paused color, for something under way such as connecting. */
  Warning,

  /** The failed color, for something that stops the app from working. */
  Danger,
}

/**
 * A condition shown at the top of the content card for as long as it lasts.
 *
 * @property id identifies the banner while it changes.
 * @property busy shows a spinner in place of the icon.
 * @property onDismiss closes it; `null` for conditions that end on their own.
 */
@Immutable
internal data class Banner(
  val id: String,
  val tone: BannerTone,
  val text: String,
  val icon: KetchIcon = KetchIcon.Info,
  val busy: Boolean = false,
  val actions: List<MessageAction> = emptyList(),
  val onDismiss: (() -> Unit)? = null,
)

/**
 * The banner about the active device's connection, or `null` when it is fine: "Connecting to
 * NAS…" once [connectingLong] says it has taken a while, "NAS is offline · retrying" with Retry
 * now and Switch to [localName], or "NAS needs a new access token" with Enter token. It never
 * opens a dialog by itself.
 *
 * @param localName name of the embedded device; `null` when there is none to switch to.
 */
internal fun deviceBanner(
  health: DeviceHealth?,
  name: String,
  connectingLong: Boolean,
  localName: String?,
  onRetry: () -> Unit,
  onSwitchToLocal: () -> Unit,
  onEnterToken: () -> Unit,
): Banner? = when (health) {
  DeviceHealth.Connecting -> if (connectingLong) {
    Banner(DEVICE_BANNER, BannerTone.Warning, "Connecting to $name…", busy = true)
  } else {
    null
  }
  is DeviceHealth.Offline -> Banner(
    id = DEVICE_BANNER,
    tone = BannerTone.Danger,
    text = listOfNotNull("$name is offline", "retrying", health.reason).joinToString(" · "),
    icon = KetchIcon.Warning,
    actions = listOfNotNull(
      MessageAction("Retry now", onRetry),
      localName?.let { MessageAction("Switch to $it", onSwitchToLocal) }
    ),
  )
  DeviceHealth.Unauthorized -> Banner(
    id = DEVICE_BANNER,
    tone = BannerTone.Danger,
    text = "$name needs a new access token",
    icon = KetchIcon.Warning,
    actions = listOf(MessageAction("Enter token", onEnterToken)),
  )
  else -> null
}

/** [message], posted as a banner, as it shows; closing it takes it off the screen. */
internal fun messageBanner(message: AppMessage, onDismiss: () -> Unit): Banner = Banner(
  id = "message-${message.id}",
  tone = when (message.level) {
    MessageLevel.Info, MessageLevel.Success -> BannerTone.Info
    MessageLevel.Warning -> BannerTone.Warning
    MessageLevel.Error -> BannerTone.Danger
  },
  text = listOfNotNull(message.title, toastDetail(message)).joinToString(" · "),
  icon = message.level.icon,
  actions = message.actions,
  onDismiss = onDismiss,
)

/**
 * Banners at the top of the content card, on every destination: the active device's connection
 * (see [deviceBanner]), then the [MessagePlacement.Banner] messages of [AppState.messages].
 * Each is at least 40 dp tall, inset 8 dp from the card.
 */
@Composable
fun BannerHost(state: AppState, modifier: Modifier = Modifier) {
  val pulse by state.pulse.state.collectAsState()
  val active by state.messages.active.collectAsState()
  val instances by state.instances.collectAsState()
  val device = pulse.devices.firstOrNull()
  val health = device?.health
  var connectingLong by remember { mutableStateOf(false) }
  LaunchedEffect(health == DeviceHealth.Connecting) {
    connectingLong = false
    if (health == DeviceHealth.Connecting) {
      delay(CONNECTING_GRACE)
      connectingLong = true
    }
  }
  val local = instances.firstOrNull { it is EmbeddedInstance }
  val banners = buildList {
    if (device != null) {
      deviceBanner(
        health = health,
        name = device.name,
        connectingLong = connectingLong,
        localName = local?.let { localDeviceNoun() },
        onRetry = { (state.activeInstance.value as? RemoteInstance)?.let(state::retryNow) },
        onSwitchToLocal = { local?.let(state::switchInstance) },
        onEnterToken = {
          val remote = state.activeInstance.value as? RemoteInstance
          if (remote != null) {
            state.unauthorizedInstance = remote
            state.showAddRemoteDialog = true
          }
        },
      )?.let(::add)
    }
    active.filter { it.placement == MessagePlacement.Banner }
      .forEach { message -> add(messageBanner(message) { state.messages.dismiss(message.id) }) }
  }
  BannerStack(banners, modifier)
}

// Connects to the offline device now rather than at its next attempt.
private fun AppState.retryNow(device: RemoteInstance) {
  launchCommand {
    try {
      instanceManager.reconnect(device)
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      log.w { "Couldn't reconnect to ${device.deviceId}: ${e.describeCauses()}" }
      messages.post(
        level = MessageLevel.Error,
        title = "Couldn't reconnect to ${device.label}",
        cause = e,
        deviceId = device.deviceId,
      )
    }
  }
}

/** [banners] stacked, each sliding in and out; drawn without the state it reads. */
@Composable
internal fun BannerStack(banners: List<Banner>, modifier: Modifier = Modifier) {
  val motion = KetchTheme.motion
  val spacing = KetchTheme.spacing
  AnimatedStack(
    items = banners,
    id = { it.id },
    enter = expandVertically(tween(motion.medium, easing = motion.easeDecelerate)) +
      fadeIn(tween(motion.medium)),
    exit = shrinkVertically(tween(motion.medium, easing = motion.easeAccelerate)) +
      fadeOut(tween(motion.short)),
    modifier = modifier.fillMaxWidth(),
  ) { banner ->
    BannerRow(
      banner = banner,
      modifier = Modifier.padding(
        start = spacing.cardInset,
        end = spacing.cardInset,
        top = spacing.cardInset,
      ),
    )
  }
}

@Composable
private fun BannerRow(banner: Banner, modifier: Modifier = Modifier) {
  val colors = KetchTheme.colors
  val spacing = KetchTheme.spacing
  val (fill, ink) = banner.tone.colors(colors)
  BoxWithConstraints(
    modifier = modifier
      .fillMaxWidth()
      .heightIn(min = BannerHeight)
      .background(fill, KetchTheme.shapes.md)
      .semantics {
        liveRegion = if (banner.tone == BannerTone.Danger) {
          LiveRegionMode.Assertive
        } else {
          LiveRegionMode.Polite
        }
      }
      .padding(start = spacing.s3, end = spacing.s1, top = spacing.s1, bottom = spacing.s1),
  ) {
    // A narrow card puts the buttons under the text, so the text keeps the width.
    val stacked = maxWidth < StackedBelowWidth && banner.actions.isNotEmpty()
    val textStyle = KetchTheme.typography.bodyS
    val firstLine = with(LocalDensity.current) { textStyle.lineHeight.toDp() }
    // Wrapped text keeps the icon and the close button on its first line.
    var wraps by remember(banner.text) { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(spacing.s1)) {
      Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(spacing.s2),
        modifier = Modifier.heightIn(min = BannerHeight - spacing.s2),
      ) {
        val firstLineOnly = if (wraps) Modifier.align(Alignment.Top) else Modifier
        Box(
          contentAlignment = Alignment.Center,
          modifier = firstLineOnly.padding(vertical = spacing.s1).height(firstLine),
        ) {
          if (banner.busy) {
            KetchSpinner(size = IconSize, color = ink)
          } else {
            KetchIconImage(icon = banner.icon, size = IconSize, tint = ink)
          }
        }
        Text(
          text = banner.text,
          style = textStyle,
          color = colors.textPrimary,
          maxLines = if (stacked) STACKED_LINES else 2,
          overflow = TextOverflow.Ellipsis,
          onTextLayout = { wraps = it.lineCount > 1 },
          modifier = Modifier.weight(1f).padding(vertical = spacing.s1),
        )
        if (!stacked) BannerActions(banner.actions)
        val dismiss = banner.onDismiss
        if (dismiss != null) {
          KetchIconButton(
            icon = KetchIcon.Close,
            onClick = dismiss,
            size = KetchButtonSize.Small,
            contentDescription = "Dismiss",
            modifier = firstLineOnly,
          )
        } else {
          Spacer(Modifier.width(spacing.s1))
        }
      }
      if (stacked) {
        Row(
          horizontalArrangement = Arrangement.spacedBy(spacing.s2),
          modifier = Modifier.padding(start = IconSize + spacing.s2, bottom = spacing.s1),
        ) {
          BannerActions(banner.actions)
        }
      }
    }
  }
}

@Composable
private fun BannerActions(actions: List<MessageAction>) {
  actions.forEach { action ->
    KetchButton(
      text = action.label,
      onClick = action.onClick,
      variant = KetchButtonVariant.Secondary,
      size = KetchButtonSize.Small,
    )
  }
}

private fun BannerTone.colors(colors: KetchColors): Pair<Color, Color> = when (this) {
  BannerTone.Info -> colors.accentSoft to colors.accentText
  BannerTone.Warning -> colors.status.paused.soft to colors.status.paused.color
  BannerTone.Danger -> colors.status.failed.soft to colors.status.failed.color
}

/** How long a connection may take before its banner shows. */
internal val CONNECTING_GRACE: Duration = 3.seconds

private const val DEVICE_BANNER = "device"
private const val STACKED_LINES = 3
private val BannerHeight = 40.dp
private val StackedBelowWidth = 480.dp
private val IconSize = 16.dp
