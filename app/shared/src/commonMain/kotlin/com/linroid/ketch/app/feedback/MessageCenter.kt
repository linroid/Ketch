package com.linroid.ketch.app.feedback

import com.linroid.ketch.app.state.TaskKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlin.time.Clock
import kotlin.time.Instant

/** How much a message matters, which sets its color and icon. */
enum class MessageLevel { Info, Success, Warning, Error }

/** How long a message stays on screen as a toast. */
enum class ToastMode {
  /** Hides after a few seconds. */
  Auto,

  /** Stays until dismissed. */
  Sticky,

  /** Only recorded in the Activity history. */
  Silent,
}

/** Where a message shows while it is active. */
enum class MessagePlacement {
  /** A toast at the bottom of the window. */
  Toast,

  /** A banner at the top of the content, for conditions that last, such as a device offline. */
  Banner,
}

/**
 * A button on a message.
 *
 * @property label button text, such as "Undo".
 * @property onClick runs when the button is clicked.
 */
class MessageAction(
  val label: String,
  val onClick: () -> Unit,
)

/**
 * Something the app tells the user: a command result, an error or an activity event.
 *
 * @property id unique id within this run of the app.
 * @property level how much it matters.
 * @property title first line, such as "Removed 5 downloads".
 * @property detail second line, or `null`.
 * @property taskKey task it is about, or `null`.
 * @property deviceId device it happened on, or `null` when it is about the app.
 * @property actions at most two buttons.
 * @property at when it was posted.
 * @property toast how long it stays on screen.
 * @property notify whether the host may also post it as a system notification.
 * @property placement where it shows while active.
 * @property cause the failure behind an [MessageLevel.Error] message, for the error catalog.
 */
data class AppMessage(
  val id: Long,
  val level: MessageLevel,
  val title: String,
  val detail: String? = null,
  val taskKey: TaskKey? = null,
  val deviceId: String? = null,
  val actions: List<MessageAction> = emptyList(),
  val at: Instant,
  val toast: ToastMode = ToastMode.Auto,
  val notify: Boolean = false,
  val placement: MessagePlacement = MessagePlacement.Toast,
  val cause: Throwable? = null,
)

/**
 * Collects the messages the app shows as toasts and banners, and keeps the most recent ones for
 * the Activity history.
 *
 * Any component can [post] without touching the shell; the toast host renders [active].
 *
 * @param clock time source for [AppMessage.at].
 * @param historyLimit how many messages [history] keeps.
 */
class MessageCenter(
  private val clock: Clock = Clock.System,
  private val historyLimit: Int = DEFAULT_HISTORY_LIMIT,
) {
  private var nextId = 1L
  private val historyState = MutableStateFlow<List<AppMessage>>(emptyList())
  private val activeState = MutableStateFlow<List<AppMessage>>(emptyList())
  private val unreadState = MutableStateFlow(0)

  /** Recent messages, newest first, at most `historyLimit`. */
  val history: StateFlow<List<AppMessage>> = historyState.asStateFlow()

  /**
   * Toasts and banners not yet dismissed, oldest first, at most `historyLimit`; silent messages
   * never appear here.
   */
  val active: StateFlow<List<AppMessage>> = activeState.asStateFlow()

  /** Messages in [history] posted since it was last marked read. */
  val unreadCount: StateFlow<Int> = unreadState.asStateFlow()

  /**
   * Posts a message and returns it.
   *
   * @param actions at most two buttons; more are dropped.
   */
  fun post(
    level: MessageLevel,
    title: String,
    detail: String? = null,
    taskKey: TaskKey? = null,
    deviceId: String? = null,
    actions: List<MessageAction> = emptyList(),
    toast: ToastMode = ToastMode.Auto,
    notify: Boolean = false,
    placement: MessagePlacement = MessagePlacement.Toast,
    cause: Throwable? = null,
  ): AppMessage {
    val message = AppMessage(
      id = nextId++,
      level = level,
      title = title,
      detail = detail,
      taskKey = taskKey,
      deviceId = deviceId,
      actions = actions.take(MAX_ACTIONS),
      at = clock.now(),
      toast = toast,
      notify = notify,
      placement = placement,
      cause = cause,
    )
    historyState.update { (listOf(message) + it).take(historyLimit) }
    unreadState.update { minOf(it + 1, historyLimit) }
    if (toast != ToastMode.Silent) activeState.update { (it + message).takeLast(historyLimit) }
    return message
  }

  /** Takes the message with [id] off the screen; it stays in the history. */
  fun dismiss(id: Long) {
    activeState.update { messages -> messages.filterNot { it.id == id } }
  }

  /** Resets [unreadCount]. */
  fun markAllRead() {
    unreadState.value = 0
  }

  /** Empties the history and takes every message off the screen. */
  fun clear() {
    historyState.value = emptyList()
    activeState.value = emptyList()
    unreadState.value = 0
  }

  private companion object {
    const val DEFAULT_HISTORY_LIMIT = 100
    const val MAX_ACTIONS = 2
  }
}
