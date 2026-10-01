package com.linroid.ketch.app.ui.feedback

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.linroid.ketch.app.components.KetchToast
import com.linroid.ketch.app.feedback.AppMessage
import com.linroid.ketch.app.feedback.MessageCenter
import com.linroid.ketch.app.theme.KetchTheme
import com.linroid.ketch.app.util.toCopy

/**
 * Shows the active messages of [messages] as toasts, at most [MAX_TOASTS] stacked with the newest
 * at the bottom. Place it at the bottom center of the content, above the status bar.
 *
 * Banners show here as toasts too, until the app has a place for them.
 */
@Composable
fun ToastHost(messages: MessageCenter, modifier: Modifier = Modifier) {
  val active by messages.active.collectAsState()
  Column(
    modifier = modifier,
    verticalArrangement = Arrangement.spacedBy(KetchTheme.spacing.s2),
    horizontalAlignment = Alignment.CenterHorizontally,
  ) {
    visibleToasts(active).forEach { message ->
      key(message.id) {
        KetchToast(
          message = message,
          onDismiss = { messages.dismiss(message.id) },
          detail = toastDetail(message),
        )
      }
    }
  }
}

/** The toasts on screen: the newest [MAX_TOASTS] of [active], oldest first. */
internal fun visibleToasts(active: List<AppMessage>): List<AppMessage> = active.takeLast(MAX_TOASTS)

/**
 * Second line of the toast for [message]. A failure reads as the error catalog explains it
 * ([toCopy]), after what failed when the message names it, as in "q3-report.pdf: Access denied
 * (403)". A failure the catalog cannot explain reads as its own message.
 */
internal fun toastDetail(message: AppMessage): String? {
  val cause = message.cause ?: return message.detail
  val copy = cause.toCopy()
  val unexplained = copy.hint == null && copy.details != null
  val reason = if (unexplained) cause.message ?: copy.title else copy.title
  val subject = message.detail?.takeUnless { it == cause.message }
  return if (subject == null) reason else "$subject: $reason"
}

/** Most toasts shown at once. */
internal const val MAX_TOASTS: Int = 3
