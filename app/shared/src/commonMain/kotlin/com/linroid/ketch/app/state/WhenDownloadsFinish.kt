package com.linroid.ketch.app.state

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.linroid.ketch.api.log.KetchLogger
import com.linroid.ketch.api.log.describeCauses
import com.linroid.ketch.app.feedback.AppMessage
import com.linroid.ketch.app.feedback.MessageAction
import com.linroid.ketch.app.feedback.MessageCenter
import com.linroid.ketch.app.feedback.MessageLevel
import com.linroid.ketch.app.feedback.ToastMode
import com.linroid.ketch.app.i18n.text
import ketch.app.shared.generated.resources.Res
import ketch.app.shared.generated.resources.action_cancel
import ketch.app.shared.generated.resources.finish_failed_quit
import ketch.app.shared.generated.resources.finish_failed_shut_down
import ketch.app.shared.generated.resources.finish_failed_sleep
import ketch.app.shared.generated.resources.finish_warning_detail
import ketch.app.shared.generated.resources.finish_warning_quit
import ketch.app.shared.generated.resources.finish_warning_shut_down
import ketch.app.shared.generated.resources.finish_warning_sleep
import ketch.app.shared.generated.resources.settings_general_when_done_nothing
import ketch.app.shared.generated.resources.settings_general_when_done_quit
import ketch.app.shared.generated.resources.settings_general_when_done_shut_down
import ketch.app.shared.generated.resources.settings_general_when_done_sleep
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/** What the app does once the downloads of the device it runs on finish. */
enum class FinishAction {
  /** Keeps running, as always. */
  Nothing,

  /** Quits Ketch. */
  Quit,

  /** Puts the computer to sleep. */
  Sleep,

  /** Shuts the computer down. */
  ShutDown,
}

/** Carries out [FinishAction]s for a host that can, such as the desktop app. */
interface FinishActions {
  /** The actions this host can carry out, besides [FinishAction.Nothing]. */
  val supported: List<FinishAction>

  /** Carries out [action]; throws when the system refuses. */
  suspend fun perform(action: FinishAction)
}

/**
 * Quits Ketch, puts the computer to sleep or shuts it down once the downloads of the embedded
 * device finish, as chosen in Settings → General → Power.
 *
 * The choice lasts until it runs, or until the app quits: it is never saved, so a later launch
 * never shuts the computer down unasked. It runs when the embedded device goes from downloading,
 * starting or queueing tasks ([ForegroundStatus.keepsAwake]) to none, after a [grace] period in
 * which a sticky warning, also a system notification while the app is not in front, offers to
 * cancel it or to run it at once. Downloads that start again meanwhile put it off until they
 * finish too. Paused and failed tasks do not hold it back.
 *
 * @param actions what the host can do.
 * @param messages where the warning and any failure show.
 * @param scope runs the grace period and the action.
 * @param grace how long the warning shows before the action runs.
 */
class WhenDownloadsFinish(
  private val actions: FinishActions,
  private val messages: MessageCenter,
  private val scope: CoroutineScope,
  private val grace: Duration = GRACE,
) {
  private val log = KetchLogger("WhenDownloadsFinish")
  private var countdown: Job? = null
  private var busy = false
  private var warning: AppMessage? = null

  /** The choices, [FinishAction.Nothing] first. */
  val options: List<FinishAction> =
    listOf(FinishAction.Nothing) + actions.supported.filter { it != FinishAction.Nothing }

  /** The action chosen, [FinishAction.Nothing] until one is and again once it ran. */
  var action: FinishAction by mutableStateOf(FinishAction.Nothing)
    private set

  /** Whether the action waits out its grace period, its warning showing. */
  var pending: Boolean by mutableStateOf(false)
    private set

  /** Chooses [choice], one of [options]; a warning showing goes away. */
  fun choose(choice: FinishAction) {
    require(choice in options) { "Unsupported action: $choice" }
    stopCountdown()
    if (choice != action) log.i { "When downloads finish: $choice" }
    action = choice
  }

  /** Puts the action off and forgets it, as the warning's Cancel does. */
  fun cancel() {
    if (pending) log.i { "Canceled $action after downloads finished" }
    choose(FinishAction.Nothing)
  }

  /**
   * Runs the chosen action once the latest of [statuses], those of the embedded device, goes
   * from busy to idle; returns only when cancelled. They must not be sampled, so a download that
   * starts and ends within a moment still counts, and one starting during the grace period puts
   * the action off at once.
   */
  suspend fun follow(statuses: Flow<ForegroundStatus>) {
    busy = false
    statuses.map { it.keepsAwake }.distinctUntilChanged().collect { now ->
      if (now) {
        // Downloads that start again put the action off until they finish too.
        if (pending) log.i { "Downloads started again, putting off $action" }
        stopCountdown()
      } else if (busy && action != FinishAction.Nothing) {
        startCountdown(action)
      }
      busy = now
    }
  }

  private fun startCountdown(action: FinishAction) {
    stopCountdown()
    log.i { "Downloads finished, running $action in ${grace.inWholeSeconds}s" }
    pending = true
    warning = messages.post(
      level = MessageLevel.Warning,
      title = action.warning.text(),
      detail = Res.string.finish_warning_detail.text(),
      actions = listOf(
        MessageAction(Res.string.action_cancel.text(), ::cancel),
        MessageAction(action.label.text()) { runNow(action) },
      ),
      toast = ToastMode.Sticky,
      notify = true,
      recordInHistory = false,
    )
    countdown = scope.launch {
      delay(grace)
      run(action)
    }
  }

  private fun runNow(action: FinishAction) {
    if (!pending || this.action != action) return
    countdown?.cancel()
    countdown = scope.launch { run(action) }
  }

  private suspend fun run(action: FinishAction) {
    // A download that started meanwhile puts it off; its end starts the countdown again.
    if (busy) return
    withdrawWarning()
    pending = false
    // It runs once: a computer that wakes from sleep goes on as usual.
    this.action = FinishAction.Nothing
    log.i { "Downloads finished, running $action" }
    try {
      actions.perform(action)
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      log.w { "Couldn't run $action: ${e.describeCauses()}" }
      messages.post(level = MessageLevel.Error, title = action.failure.text(), cause = e)
    }
  }

  private fun stopCountdown() {
    countdown?.cancel()
    countdown = null
    pending = false
    withdrawWarning()
  }

  private fun withdrawWarning() {
    warning?.let { messages.withdraw(it.id) }
    warning = null
  }

  companion object {
    /** How long the warning shows before the action runs. */
    val GRACE: Duration = 1.minutes
  }
}

/** The name of this action, as its choice and the warning's button show it. */
internal val FinishAction.label
  get() = when (this) {
    FinishAction.Nothing -> Res.string.settings_general_when_done_nothing
    FinishAction.Quit -> Res.string.settings_general_when_done_quit
    FinishAction.Sleep -> Res.string.settings_general_when_done_sleep
    FinishAction.ShutDown -> Res.string.settings_general_when_done_shut_down
  }

private val FinishAction.warning
  get() = when (this) {
    FinishAction.Nothing -> error("Nothing never warns")
    FinishAction.Quit -> Res.string.finish_warning_quit
    FinishAction.Sleep -> Res.string.finish_warning_sleep
    FinishAction.ShutDown -> Res.string.finish_warning_shut_down
  }

private val FinishAction.failure
  get() = when (this) {
    FinishAction.Nothing -> error("Nothing never fails")
    FinishAction.Quit -> Res.string.finish_failed_quit
    FinishAction.Sleep -> Res.string.finish_failed_sleep
    FinishAction.ShutDown -> Res.string.finish_failed_shut_down
  }
