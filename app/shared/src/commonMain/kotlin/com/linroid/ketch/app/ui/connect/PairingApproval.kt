package com.linroid.ketch.app.ui.connect

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.linroid.ketch.app.components.DevicePennant
import com.linroid.ketch.app.components.DevicePennantDefaults
import com.linroid.ketch.app.components.KetchButton
import com.linroid.ketch.app.components.KetchButtonSize
import com.linroid.ketch.app.components.KetchButtonVariant
import com.linroid.ketch.app.i18n.SEPARATOR
import com.linroid.ketch.app.i18n.UiText
import com.linroid.ketch.app.i18n.etaText
import com.linroid.ketch.app.i18n.resolve
import com.linroid.ketch.app.instance.PairingAsk
import com.linroid.ketch.app.instance.PairingRequests
import com.linroid.ketch.app.platform.localDeviceNounInSentence
import com.linroid.ketch.app.state.AppState
import com.linroid.ketch.app.state.LocalClock
import com.linroid.ketch.app.theme.KetchTheme
import com.linroid.ketch.app.ui.devices.systemDeviceType
import com.linroid.ketch.app.ui.devices.systemName
import com.linroid.ketch.app.ui.settings.SettingsGroup
import com.linroid.ketch.app.ui.settings.SettingsRow
import ketch.app.shared.generated.resources.Res
import ketch.app.shared.generated.resources.pairing_request_allow
import ketch.app.shared.generated.resources.pairing_request_check
import ketch.app.shared.generated.resources.pairing_request_deny
import ketch.app.shared.generated.resources.pairing_request_note
import ketch.app.shared.generated.resources.pairing_request_title
import ketch.app.shared.generated.resources.settings_sharing_pairing
import ketch.app.shared.generated.resources.settings_sharing_pairing_off
import ketch.app.shared.generated.resources.settings_sharing_pairing_on
import ketch.app.shared.generated.resources.settings_sharing_pairing_turn_off
import ketch.app.shared.generated.resources.settings_sharing_pairing_turn_on
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.stringResource
import kotlin.math.ceil
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.DurationUnit

/**
 * The devices that found this one on the network and ask for its access code, oldest first,
 * each with Allow and Don't allow, at the top of Settings › Sharing: the only place the owner
 * answers them.
 */
@Composable
internal fun PairingRequestCards(state: AppState) {
  val requests = state.instanceManager.pairingRequests
  val pending by requests.pending.collectAsState()
  if (pending.isEmpty()) return
  Column(verticalArrangement = Arrangement.spacedBy(KetchTheme.spacing.s4)) {
    for (ask in pending) {
      key(ask.id) {
        PairingRequestCard(ask, onAnswer = { allow -> requests.answer(ask.id, allow) })
      }
    }
  }
}

/**
 * Tells [state] that Settings › Sharing of this device shows, and whether its window is in
 * front ([AppState.sharingShown]), so the app leads here with a toast and a banner only while
 * it does not. That follows the lifecycle, which is resumed only while the window is in front
 * and changes without a frame, as a hidden window never renders the one that would report it
 * lost focus.
 */
@Composable
internal fun ReportSharingShown(state: AppState) {
  val lifecycle = LocalLifecycleOwner.current.lifecycle
  DisposableEffect(state, lifecycle) {
    val observer = LifecycleEventObserver { _, _ ->
      state.sharingShown = lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
    }
    lifecycle.addObserver(observer)
    onDispose {
      lifecycle.removeObserver(observer)
      state.sharingShown = false
    }
  }
}

/**
 * Whether devices that find this one may ask to pair, a row of the Pair a device card: off
 * until the owner turns it on, then on for [PairingRequests.WINDOW] with the time left, until a
 * device is let in or the owner turns it off.
 */
@Composable
internal fun PairingWindowRow(requests: PairingRequests) {
  val left = rememberPairingTimeLeft(requests)
  val noun = localDeviceNounInSentence().resolve()
  SettingsRow(
    title = stringResource(Res.string.settings_sharing_pairing),
    description = if (left == null) {
      stringResource(Res.string.settings_sharing_pairing_off, noun)
    } else {
      stringResource(Res.string.settings_sharing_pairing_on, pairingTimeText(left).resolve(), noun)
    },
    descriptionColor = if (left == null) {
      KetchTheme.colors.textSecondary
    } else {
      KetchTheme.colors.accent
    },
    trailing = {
      if (left == null) {
        KetchButton(
          text = stringResource(Res.string.settings_sharing_pairing_turn_on),
          onClick = { requests.open() },
          variant = KetchButtonVariant.Secondary,
          size = KetchButtonSize.Small,
        )
      } else {
        KetchButton(
          text = stringResource(Res.string.settings_sharing_pairing_turn_off),
          onClick = requests::close,
          variant = KetchButtonVariant.Ghost,
          size = KetchButtonSize.Small,
        )
      }
    },
  )
}

/**
 * How long [requests] still take pairing requests, updated every second, or `null` while
 * pairing is off.
 */
@Composable
internal fun rememberPairingTimeLeft(requests: PairingRequests): Duration? {
  val end by requests.openUntil.collectAsState()
  val clock = LocalClock.current
  val left by produceState<Duration?>(null, end, clock) {
    val until = end
    while (true) {
      value = until?.let { it - clock.now() }?.takeIf { it.isPositive() }
      val now = value ?: break
      // Wakes as the shown second changes.
      delay(now - now.inWholeSeconds.seconds + 1.milliseconds)
    }
  }
  return left
}

/** [left] to the second, rounded up, so it reads 3m 0s right after pairing turns on. */
internal fun pairingTimeText(left: Duration): UiText =
  etaText(ceil(left.toDouble(DurationUnit.SECONDS)).toLong())

/**
 * Whether to let [ask]'s device control this one, with the code it shows, so the owner can
 * tell it is the device in their hand.
 *
 * @param onAnswer receives `true` to let the device in.
 */
@Composable
internal fun PairingRequestCard(ask: PairingAsk, onAnswer: (Boolean) -> Unit) {
  val colors = KetchTheme.colors
  val type = KetchTheme.typography
  val spacing = KetchTheme.spacing
  val noun = localDeviceNounInSentence().resolve()
  SettingsGroup(title = stringResource(Res.string.pairing_request_title, ask.name, noun)) {
    Column(
      verticalArrangement = Arrangement.spacedBy(spacing.s4),
      modifier = Modifier.fillMaxWidth()
        .background(colors.surface)
        .padding(spacing.s5),
    ) {
      Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(spacing.s3),
        modifier = Modifier
          .fillMaxWidth()
          .background(colors.surfaceSunken, KetchTheme.shapes.md)
          .padding(spacing.s3),
      ) {
        DevicePennant(
          deviceId = ask.address,
          name = ask.name,
          size = DevicePennantDefaults.Large,
          fallbackType = ask.os?.let(::systemDeviceType),
        )
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(spacing.s0_5)) {
          Text(
            text = ask.name,
            style = type.titleM,
            color = colors.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
          Text(
            text = listOfNotNull(ask.os?.let(::systemName), ask.address).joinToString(SEPARATOR),
            style = type.caption,
            color = colors.textSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
        }
      }
      Text(
        text = stringResource(Res.string.pairing_request_check, ask.name),
        style = type.bodyS,
        color = colors.textSecondary,
      )
      Text(
        text = ask.code,
        style = type.numeralXL,
        color = colors.textPrimary,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth(),
      )
      Text(
        text = stringResource(Res.string.pairing_request_note),
        style = type.caption,
        color = colors.textTertiary,
      )
      Row(
        horizontalArrangement = Arrangement.spacedBy(spacing.s2, Alignment.End),
        modifier = Modifier.fillMaxWidth(),
      ) {
        KetchButton(
          text = stringResource(Res.string.pairing_request_deny),
          onClick = { onAnswer(false) },
          variant = KetchButtonVariant.Secondary,
        )
        KetchButton(
          text = stringResource(Res.string.pairing_request_allow),
          onClick = { onAnswer(true) },
        )
      }
    }
  }
}
