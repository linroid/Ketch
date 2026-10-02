package com.linroid.ketch.app

import com.linroid.ketch.api.DownloadState
import com.linroid.ketch.api.DownloadTask
import com.linroid.ketch.api.KetchApi
import com.linroid.ketch.api.log.KetchLogger
import com.linroid.ketch.api.log.describeCauses
import com.linroid.ketch.app.feedback.IosNotifier
import com.linroid.ketch.app.feedback.MessageAction
import com.linroid.ketch.app.feedback.MessageCenter
import com.linroid.ketch.app.feedback.MessageLevel
import com.linroid.ketch.app.feedback.MessagePlacement
import com.linroid.ketch.app.feedback.ToastMode
import com.linroid.ketch.app.instance.ServerState
import com.linroid.ketch.app.state.ForegroundPolicy
import com.linroid.ketch.app.state.LOCAL_DEVICE_ID
import com.linroid.ketch.app.state.PendingOps
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DisposableHandle
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSOperationQueue
import platform.Foundation.NSUserDefaults
import platform.UIKit.UIApplication
import platform.UIKit.UIApplicationDidEnterBackgroundNotification
import platform.UIKit.UIApplicationState
import platform.UIKit.UIApplicationWillEnterForegroundNotification
import platform.UIKit.UIBackgroundTaskInvalid
import kotlin.time.Duration.Companion.seconds

/**
 * Pauses this device's downloads before iOS suspends Ketch in the background, and resumes them
 * when Ketch returns.
 *
 * iOS suspends an app soon after it leaves the screen, and the downloads' connections die with
 * it. Pausing first saves their progress, so they show as Paused rather than failed. The tasks
 * it paused are remembered across launches, so they also resume when iOS ended Ketch meanwhile,
 * which it does without notice; operations still waiting for their Undo window are committed
 * for the same reason. While downloads run, a banner tells the user that they pause in the
 * background.
 */
object KetchBackground {
  private val log = KetchLogger("KetchBackground")
  private val scope = MainScope()
  private var pauser: BackgroundPauser? = null

  /**
   * Pauses this device's queued and downloading tasks and waits until their progress is saved.
   * They resume when Ketch returns to the foreground.
   *
   * @return how many tasks it paused.
   */
  suspend fun prepareForSuspension(): Int = pauser?.prepareForSuspension() ?: 0

  /**
   * Follows [api], the embedded device, until the returned handle is disposed: commits
   * [pendingOps] and pauses its downloads in the background, resumes them in front, and posts
   * the background banner to [messages], whose button runs [onUseComputer].
   */
  internal fun attach(
    api: KetchApi,
    messages: MessageCenter,
    pendingOps: PendingOps,
    onUseComputer: () -> Unit,
  ): DisposableHandle {
    val pauser = BackgroundPauser(
      api = api,
      scope = scope,
      saved = SavedPausedIds,
      commitPending = pendingOps::flush,
      onPaused = IosNotifier::notifyPaused,
      onResumed = IosNotifier::clearPaused,
    )
    this.pauser = pauser
    val center = NSNotificationCenter.defaultCenter
    val queue = NSOperationQueue.mainQueue
    val observers = listOf(
      center.addObserverForName(UIApplicationDidEnterBackgroundNotification, null, queue) {
        pauser.enterBackground()?.let(::holdBackgroundTime)
      },
      center.addObserverForName(UIApplicationWillEnterForegroundNotification, null, queue) {
        pauser.enterForeground()
      },
    )
    // Resumes the tasks paused before iOS ended Ketch, if any.
    val app = UIApplication.sharedApplication
    if (app.applicationState != UIApplicationState.UIApplicationStateBackground) {
      pauser.enterForeground()
    }
    val banner = scope.launch { showBanner(api, messages, onUseComputer) }
    return DisposableHandle {
      observers.forEach { center.removeObserver(it) }
      banner.cancel()
      pauser.close()
      if (this.pauser === pauser) this.pauser = null
    }
  }

  // Keeps Ketch running in the background until [work] completes or iOS runs out of time.
  private fun holdBackgroundTime(work: Job) {
    val app = UIApplication.sharedApplication
    var taskId = UIBackgroundTaskInvalid
    val end = {
      if (taskId != UIBackgroundTaskInvalid) {
        app.endBackgroundTask(taskId)
        taskId = UIBackgroundTaskInvalid
      }
    }
    taskId = app.beginBackgroundTaskWithName(BACKGROUND_TASK) {
      log.w { "Background time ran out while pausing downloads" }
      work.cancel()
      end()
    }
    work.invokeOnCompletion { end() }
  }

  // Shown while this device downloads, until the user closes it.
  private suspend fun showBanner(
    api: KetchApi,
    messages: MessageCenter,
    onUseComputer: () -> Unit,
  ) {
    var shown: Long? = null
    var closed = false
    try {
      coroutineScope {
        launch {
          messages.active.collect { active ->
            val id = shown
            if (id != null && active.none { it.id == id }) {
              shown = null
              closed = true
            }
          }
        }
        ForegroundPolicy.observe(api.tasks, flowOf(ServerState.Stopped))
          .map { it.downloading > 0 }
          .distinctUntilChanged()
          .collect { downloading ->
            val id = shown
            if (downloading && id == null && !closed) {
              shown = messages.post(
                level = MessageLevel.Info,
                title = "Downloads pause when Ketch is in the background",
                deviceId = LOCAL_DEVICE_ID,
                actions = listOf(MessageAction("Use a computer instead", onUseComputer)),
                toast = ToastMode.Sticky,
                placement = MessagePlacement.Banner,
              ).id
            } else if (!downloading && id != null) {
              shown = null
              messages.dismiss(id)
            }
          }
      }
    } finally {
      shown?.let { messages.dismiss(it) }
    }
  }

  private const val BACKGROUND_TASK = "ketch.downloads"
}

/** Ids of the tasks paused for the background, downloading ones first. */
internal interface PausedTaskIds {
  var ids: List<String>
}

// Kept in the user defaults, so they outlive a process that iOS ended in the background.
private object SavedPausedIds : PausedTaskIds {
  private const val KEY = "ketch.background.paused"

  override var ids: List<String>
    get() = NSUserDefaults.standardUserDefaults.stringArrayForKey(KEY)
      ?.filterIsInstance<String>().orEmpty()
    set(value) {
      val defaults = NSUserDefaults.standardUserDefaults
      if (value.isEmpty()) {
        defaults.removeObjectForKey(KEY)
      } else {
        defaults.setObject(value, forKey = KEY)
      }
    }
}

/**
 * Pauses the queued and downloading tasks of [api] when Ketch goes to the background and resumes
 * them when it returns. [KetchBackground] drives it from the app's lifecycle.
 *
 * Resuming waits for pausing still in progress. Going back to the background before resuming
 * finished stops it, so the tasks it had not reached stay paused and remembered.
 *
 * @param scope runs the pausing and resuming.
 * @param saved ids of the tasks it paused, kept until they resume.
 * @param commitPending commits the operations still waiting for their Undo window and returns
 *   the job that completes with their commits.
 * @param onPaused tells the user how many downloads wait for Ketch to return.
 * @param onResumed withdraws what [onPaused] told.
 */
internal class BackgroundPauser(
  private val api: KetchApi,
  private val scope: CoroutineScope,
  private val saved: PausedTaskIds,
  private val commitPending: () -> Job = { Job().apply { complete() } },
  private val onPaused: (count: Int) -> Unit = {},
  private val onResumed: () -> Unit = {},
) {
  private val log = KetchLogger("KetchBackground")
  private var suspension: Job? = null
  private var resumption: Job? = null

  /**
   * Commits pending operations and pauses the active tasks, after stopping any resuming.
   *
   * @return the job doing it, which Ketch must stay running for, or `null` when there is
   *   nothing to do.
   */
  fun enterBackground(): Job? {
    val resuming = resumption?.takeIf { it.isActive }
    resuming?.cancel()
    resumption = null
    val pausing = suspension?.takeIf { it.isActive }
    val commits = commitPending()
    if (resuming == null && pausing == null && commits.isCompleted && !hasActiveTasks()) {
      return null
    }
    return scope.launch {
      try {
        resuming?.join()
        pausing?.join()
        commits.join()
        prepareForSuspension()
      } finally {
        // Also when iOS ran out of time, for the tasks paused until then.
        val waiting = saved.ids.size
        if (waiting > 0) onPaused(waiting)
      }
    }.also { suspension = it }
  }

  /** Resumes the tasks paused for the background once any pausing in progress has finished. */
  fun enterForeground(): Job {
    val pausing = suspension
    val previous = resumption
    previous?.cancel()
    return scope.launch {
      previous?.join()
      pausing?.join()
      resumeSaved()
    }.also { resumption = it }
  }

  /**
   * Pauses the queued and downloading tasks, remembering each as it goes, so the ones paused
   * before iOS runs out of time still resume.
   *
   * @return how many tasks it paused.
   */
  suspend fun prepareForSuspension(): Int {
    val earlier = saved.ids
    val paused = pauseActive { saved.ids = (earlier + it).distinct() }
    if (paused.isNotEmpty()) log.i { "Paused ${paused.size} downloads for the background" }
    return paused.size
  }

  /** Stops resuming; pausing in progress still finishes. */
  fun close() {
    resumption?.cancel()
  }

  // An id is forgotten once its task resumed, is no longer paused or is gone; the others are
  // kept, with the notice that they are paused, for the next time the app comes to the front.
  private suspend fun resumeSaved() {
    val ids = saved.ids
    if (ids.isEmpty()) return
    // After a relaunch, the engine restores its tasks a moment after it starts.
    val restored = withTimeoutOrNull(RESTORE_TIMEOUT) {
      api.tasks.first { tasks -> tasks.any { it.taskId in ids } }
    } != null
    val tasks = api.tasks.value.associateBy { it.taskId }
    var resumed = 0
    val left = ids.filter { id ->
      val task = tasks[id]
      when {
        task == null -> !restored
        task.state.value !is DownloadState.Paused -> false
        attempt("resume", task) { task.resume() } -> {
          resumed++
          false
        }
        else -> true
      }
    }
    saved.ids = left
    if (left.isEmpty()) onResumed()
    log.i { "Resumed $resumed downloads paused for the background, ${left.size} left" }
  }

  // Pauses the queued tasks before the downloading ones, so the queue cannot start a waiting
  // task as slots free up, and repeats for any it started meanwhile. Returns the paused ids,
  // downloading ones first, and passes them to [record] after each pause.
  private suspend fun pauseActive(record: (List<String>) -> Unit): List<String> {
    val downloading = mutableListOf<String>()
    val queued = mutableListOf<String>()
    val attempted = mutableSetOf<String>()
    repeat(MAX_PAUSE_ROUNDS) {
      val tasks = api.tasks.value.filter { it.taskId !in attempted }
      val waiting = tasks.filter { it.state.value is DownloadState.Queued }
      val running = tasks.filter { it.state.value is DownloadState.Downloading }
      if (waiting.isEmpty() && running.isEmpty()) return downloading + queued
      for ((group, paused) in listOf(waiting to queued, running to downloading)) {
        for (task in group) {
          attempted += task.taskId
          if (attempt("pause", task) { task.pause() }) {
            paused += task.taskId
            record(downloading + queued)
          }
        }
      }
    }
    return downloading + queued
  }

  private fun hasActiveTasks(): Boolean = api.tasks.value.any {
    val state = it.state.value
    state is DownloadState.Queued || state is DownloadState.Downloading
  }

  private suspend fun attempt(
    action: String,
    task: DownloadTask,
    block: suspend () -> Unit,
  ): Boolean = try {
    block()
    true
  } catch (e: CancellationException) {
    throw e
  } catch (e: Exception) {
    log.w { "Could not $action taskId=${task.taskId} for the background: ${e.describeCauses()}" }
    false
  }

  private companion object {
    val RESTORE_TIMEOUT = 10.seconds
    const val MAX_PAUSE_ROUNDS = 3
  }
}
