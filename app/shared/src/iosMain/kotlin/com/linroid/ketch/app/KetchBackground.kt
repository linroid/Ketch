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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
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
 * it paused are remembered across launches, so they also resume when iOS ended Ketch meanwhile.
 * While downloads run, a banner tells the user that they pause in the background.
 */
object KetchBackground {
  private val log = KetchLogger("KetchBackground")
  private val scope = MainScope()
  private var api: KetchApi? = null
  private var suspension: Job? = null

  // Ids of the tasks paused for the background, downloading ones first.
  private var pausedIds: List<String>
    get() = NSUserDefaults.standardUserDefaults.stringArrayForKey(PAUSED_KEY)
      ?.filterIsInstance<String>().orEmpty()
    set(value) {
      val defaults = NSUserDefaults.standardUserDefaults
      if (value.isEmpty()) {
        defaults.removeObjectForKey(PAUSED_KEY)
      } else {
        defaults.setObject(value, forKey = PAUSED_KEY)
      }
    }

  /**
   * Pauses this device's queued and downloading tasks and waits until their progress is saved.
   * They resume when Ketch returns to the foreground.
   *
   * @return how many tasks it paused.
   */
  suspend fun prepareForSuspension(): Int {
    val api = api ?: return 0
    val earlier = pausedIds
    // Recorded as it goes, so the tasks paused before iOS runs out of time still resume.
    val paused = pauseActive(api) { pausedIds = (earlier + it).distinct() }
    if (paused.isNotEmpty()) log.i { "Paused ${paused.size} downloads for the background" }
    return paused.size
  }

  /**
   * Follows [api], the embedded device, until the returned handle is disposed: pauses its
   * downloads in the background, resumes them in front, and posts the background banner to
   * [messages], whose button runs [onUseComputer].
   */
  internal fun attach(
    api: KetchApi,
    messages: MessageCenter,
    onUseComputer: () -> Unit,
  ): DisposableHandle {
    this.api = api
    val center = NSNotificationCenter.defaultCenter
    val queue = NSOperationQueue.mainQueue
    val observers = listOf(
      center.addObserverForName(UIApplicationDidEnterBackgroundNotification, null, queue) {
        enterBackground()
      },
      center.addObserverForName(UIApplicationWillEnterForegroundNotification, null, queue) {
        enterForeground()
      },
    )
    val jobs = listOf(
      scope.launch { resumeAfterRelaunch(api) },
      scope.launch { showBanner(api, messages, onUseComputer) },
    )
    return DisposableHandle {
      observers.forEach { center.removeObserver(it) }
      jobs.forEach { it.cancel() }
      if (this.api === api) this.api = null
    }
  }

  // Holds off suspension with a background task until the downloads are paused.
  private fun enterBackground() {
    val api = api ?: return
    if (suspension?.isActive == true) return
    if (api.tasks.value.none { it.state.value.isQueuedOrDownloading }) return
    val app = UIApplication.sharedApplication
    var taskId = UIBackgroundTaskInvalid
    val end = {
      if (taskId != UIBackgroundTaskInvalid) {
        app.endBackgroundTask(taskId)
        taskId = UIBackgroundTaskInvalid
      }
    }
    val job = scope.launch(start = CoroutineStart.LAZY) {
      try {
        val paused = prepareForSuspension()
        if (paused > 0) IosNotifier.notifyPaused(pausedIds.size)
      } finally {
        end()
      }
    }
    taskId = app.beginBackgroundTaskWithName(BACKGROUND_TASK) {
      log.w { "Background time ran out while pausing downloads" }
      job.cancel()
      end()
    }
    suspension = job
    job.start()
  }

  private fun enterForeground() {
    val pending = suspension
    scope.launch {
      pending?.join()
      resumeSaved()
    }
  }

  private suspend fun resumeSaved() {
    val api = api ?: return
    IosNotifier.clearPaused()
    val ids = pausedIds
    if (ids.isEmpty()) return
    pausedIds = emptyList()
    val resumed = resumePaused(api, ids)
    log.i { "Resumed $resumed downloads paused for the background" }
  }

  // Tasks paused before iOS ended Ketch resume once the engine has restored them.
  private suspend fun resumeAfterRelaunch(api: KetchApi) {
    val ids = pausedIds.toSet()
    if (ids.isEmpty()) return
    withTimeoutOrNull(RESTORE_TIMEOUT) {
      api.tasks.first { tasks -> tasks.any { it.taskId in ids } }
    }
    val app = UIApplication.sharedApplication
    if (app.applicationState == UIApplicationState.UIApplicationStateBackground) return
    resumeSaved()
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

  /**
   * Pauses the queued tasks of [api], then the downloading ones, so the queue cannot start a
   * waiting task as slots free up, and repeats for any it started meanwhile.
   *
   * @param onPaused receives the ids paused so far after each pause.
   * @return the ids of the paused tasks, downloading ones first.
   */
  internal suspend fun pauseActive(
    api: KetchApi,
    onPaused: (List<String>) -> Unit = {},
  ): List<String> {
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
          if (pause(task)) {
            paused += task.taskId
            onPaused(downloading + queued)
          }
        }
      }
    }
    return downloading + queued
  }

  /**
   * Resumes the tasks of [api] with [ids], in that order, that are still paused.
   *
   * @return how many it resumed.
   */
  internal suspend fun resumePaused(api: KetchApi, ids: List<String>): Int {
    val tasks = api.tasks.value.associateBy { it.taskId }
    return ids.count { id ->
      val task = tasks[id]
      task != null && task.state.value is DownloadState.Paused && resume(task)
    }
  }

  private val DownloadState.isQueuedOrDownloading: Boolean
    get() = this is DownloadState.Queued || this is DownloadState.Downloading

  private suspend fun pause(task: DownloadTask): Boolean =
    attempt("pause", task) { task.pause() }

  private suspend fun resume(task: DownloadTask): Boolean =
    attempt("resume", task) { task.resume() }

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

  private const val PAUSED_KEY = "ketch.background.paused"
  private const val BACKGROUND_TASK = "ketch.downloads"
  private val RESTORE_TIMEOUT = 10.seconds
  private const val MAX_PAUSE_ROUNDS = 3
}
