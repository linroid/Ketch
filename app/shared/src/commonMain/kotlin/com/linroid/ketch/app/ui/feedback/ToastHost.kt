package com.linroid.ketch.app.ui.feedback

import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.linroid.ketch.app.components.KetchToast
import com.linroid.ketch.app.components.toastDuration
import com.linroid.ketch.app.feedback.AppMessage
import com.linroid.ketch.app.feedback.MessageCenter
import com.linroid.ketch.app.feedback.MessagePlacement
import com.linroid.ketch.app.i18n.UiText
import com.linroid.ketch.app.i18n.text
import com.linroid.ketch.app.i18n.verbatim
import com.linroid.ketch.app.theme.KetchTheme
import com.linroid.ketch.app.util.toCopy
import ketch.app.shared.generated.resources.Res
import ketch.app.shared.generated.resources.toast_subject_reason

/**
 * Shows the active toast messages of [messages], at most [MAX_TOASTS] stacked 8 dp apart with
 * the newest at the bottom. Each rises in and fades away. Place it at the bottom center of the
 * content card, 8 dp above the Pulse bar, or above the add button and bottom bar on phones.
 *
 * A timed toast that newer ones push off the stack is dismissed, so it never comes back later
 * with an Undo that has expired; one that stays until dismissed, such as an error, waits for
 * room. Banner messages belong to [BannerHost].
 */
@Composable
fun ToastHost(messages: MessageCenter, modifier: Modifier = Modifier) {
  val active by messages.active.collectAsState()
  LaunchedEffect(active) {
    overflowingToasts(active).forEach { messages.dismiss(it.id) }
  }
  val motion = KetchTheme.motion
  val spacing = KetchTheme.spacing
  AnimatedStack(
    items = visibleToasts(active),
    id = { it.id },
    enter = slideInVertically(tween(motion.medium, easing = motion.easeDecelerate)) { it / 2 } +
      fadeIn(tween(motion.medium)),
    exit = fadeOut(tween(motion.short, easing = motion.easeAccelerate)) +
      shrinkVertically(tween(motion.medium, delayMillis = motion.short)),
    modifier = modifier,
    horizontalAlignment = Alignment.CenterHorizontally,
  ) { message ->
    KetchToast(
      message = message,
      onDismiss = { messages.dismiss(message.id) },
      detail = toastDetail(message),
      modifier = Modifier.padding(top = spacing.s2),
    )
  }
}

/** The toasts on screen: the newest [MAX_TOASTS] toasts of [active], oldest first. */
internal fun visibleToasts(active: List<AppMessage>): List<AppMessage> =
  active.filter { it.placement == MessagePlacement.Toast }.takeLast(MAX_TOASTS)

/** Timed toasts of [active] that newer ones push off the stack. */
internal fun overflowingToasts(active: List<AppMessage>): List<AppMessage> =
  active.filter { it.placement == MessagePlacement.Toast }
    .dropLast(MAX_TOASTS)
    .filter { toastDuration(it) != null }

/**
 * Second line of the toast for [message]. A failure reads as the error catalog explains it
 * ([toCopy]), after what failed when the message names it, as in "q3-report.pdf: Access denied
 * (403)". A failure the catalog cannot explain reads as its own message.
 */
internal fun toastDetail(message: AppMessage): UiText? {
  val cause = message.cause ?: return message.detail
  val copy = cause.toCopy()
  val unexplained = copy.hint == null && copy.details != null
  val reason = if (unexplained) cause.message?.let(::verbatim) ?: copy.title else copy.title
  val subject = message.detail?.takeUnless { it == cause.message?.let(::verbatim) }
  return if (subject == null) reason else Res.string.toast_subject_reason.text(subject, reason)
}

/** Most toasts shown at once. */
internal const val MAX_TOASTS: Int = 3
