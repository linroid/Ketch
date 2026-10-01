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
import com.linroid.ketch.app.feedback.OngoingDownloads
import com.linroid.ketch.app.feedback.ToastMode
import com.linroid.ketch.app.instance.ServerState
import com.linroid.ketch.app.state.ForegroundPolicy
import com.linroid.ketch.app.state.LOCAL_DEVICE_ID
import com.linroid.ketch.app.state.PendingOps
import com.linroid.ketch.app.util.displayName
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DisposableHandle
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
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
import kotlin.time.Duration.Companion.ZERO
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
 *
 * Where iOS can keep them running instead (iOS 26 and later), the app sets
 * [continuedProcessing]: downloads started while Ketch is in front then go on in the background,
 * with their progress in the system's UI, and pause only once iOS stops that work.
 */
object KetchBackground {
  private val log = KetchLogger("KetchBackground")
  private val scope = MainScope()
  private var pauser: BackgroundPauser? = null
  private var continued: ContinuedDownloads? = null

  /**
   * Keeps downloads running in the background where iOS allows it; set by the app before its
   * UI starts, on iOS 26 and later. `null` pauses them as Ketch leaves the screen.
   */
  var continuedProcessing: ContinuedProcessing? = null

  /**
   * Tells Ketch that iOS stopped the work [continuedProcessing] began before the downloads
   * finished. Ketch pauses them if it is in the background, as it would without that work.
   */
  fun continuedProcessingExpired() {
    val continued = continued ?: return
    continued.expire()
    log.i { "iOS stopped the downloads running in the background" }
    if (!isInFront()) pauser?.enterBackground()?.let(::holdBackgroundTime)
  }

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
    val continued = continuedProcessing?.let { ContinuedDownloads(it, ::isInFront) }
    this.continued = continued
    val center = NSNotificationCenter.defaultCenter
    val queue = NSOperationQueue.mainQueue
    val observers = listOf(
      center.addObserverForName(UIApplicationDidEnterBackgroundNotification, null, queue) {
        // Downloads iOS keeps running need no pausing.
        val work = if (continued?.running?.value == true) {
          pauser.keepRunningInBackground()
        } else {
          pauser.enterBackground()
        }
        work?.let(::holdBackgroundTime)
      },
      center.addObserverForName(UIApplicationWillEnterForegroundNotification, null, queue) {
        pauser.enterForeground()
      },
    )
    // Resumes the tasks paused before iOS ended Ketch, if any.
    if (isInFront()) pauser.enterForeground()
    val progress = continued?.let { scope.launch { it.follow(api) } }
    val banner = scope.launch { showBanner(api, messages, continued?.running, onUseComputer) }
    return DisposableHandle {
      observers.forEach { center.removeObserver(it) }
      banner.cancel()
      progress?.cancel()
      continued?.close()
      pauser.close()
      if (this.pauser === pauser) this.pauser = null
      if (this.continued === continued) this.continued = null
    }
  }

  // Whether Ketch is on the screen; iOS suspends it soon after it leaves.
  private fun isInFront(): Boolean {
    val state = UIApplication.sharedApplication.applicationState
    return state != UIApplicationState.UIApplicationStateBackground
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

  // Shown while this device downloads and iOS would not keep it running in the background
  // ([continuing]; `null` where it never does), until the user closes it.
  @OptIn(FlowPreview::class)
  private suspend fun showBanner(
    api: KetchApi,
    messages: MessageCenter,
    continuing: Flow<Boolean>?,
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
        val downloading = ForegroundPolicy.observe(api.tasks, flowOf(ServerState.Stopped))
          .map { it.downloading > 0 }
        combine(downloading, continuing ?: flowOf(false)) { active, kept -> active && !kept }
          .distinctUntilChanged()
          // iOS takes a moment to take on the downloads as they start.
          .debounce { pausing -> if (pausing && continuing != null) ACCEPT_WAIT else ZERO }
          .collect { pausing ->
            val id = shown
            if (pausing && id == null && !closed) {
              shown = messages.post(
                level = MessageLevel.Info,
                title = "Downloads pause when Ketch is in the background",
                deviceId = LOCAL_DEVICE_ID,
                actions = listOf(MessageAction("Use a computer instead", onUseComputer)),
                toast = ToastMode.Sticky,
                placement = MessagePlacement.Banner,
              ).id
            } else if (!pausing && id != null) {
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
  private val ACCEPT_WAIT = 2.seconds
}

/**
 * What the system's progress UI shows for this device's downloads while they run in the
 * background.
 *
 * @property title "Downloading ubuntu-24.04.iso", "Downloading 3 files", or "Waiting to download
 *   2 files" while every task waits.
 * @property subtitle "1.2 GB of 3.4 GB · about 6 min left", only the bytes so far while a size
 *   is unknown, or empty while every task waits.
 * @property permille overall progress out of 1000, or -1 while a size is unknown or every task
 *   waits.
 */
data class BackgroundProgress(
  val title: String,
  val subtitle: String,
  val permille: Int,
)

/**
 * Asks iOS to keep Ketch running in the background while downloads run, showing their progress;
 * on iOS 26 and later, the app implements it with a continued processing task. Ketch calls it on
 * the main thread.
 */
interface ContinuedProcessing {
  /**
   * Asks iOS to let the downloads go on in the background, showing [progress]. Ketch asks only
   * while it is in front, as downloads start.
   *
   * @return whether iOS accepted the request.
   */
  fun begin(progress: BackgroundProgress): Boolean

  /** Shows the downloads' latest [progress]. */
  fun update(progress: BackgroundProgress)

  /**
   * Ends the work begun, as nothing downloads or waits any more ([success]), or as Ketch stops
   * following the downloads.
   */
  fun end(success: Boolean)
}

/**
 * Follows this device's downloads for [processing]: begins its work when downloads start while
 * Ketch is in front, which is when iOS accepts it, updates their progress once a second, and ends
 * it once nothing downloads or waits.
 *
 * @param inFront whether Ketch is on the screen.
 */
internal class ContinuedDownloads(
  private val processing: ContinuedProcessing,
  private val inFront: () -> Boolean,
) {
  private val _running = MutableStateFlow(false)

  // Set once iOS refused or stopped the work, so it is not asked again every second until the
  // downloads stop.
  private var declined = false

  /** Whether iOS keeps the downloads running in the background now. */
  val running: StateFlow<Boolean> = _running.asStateFlow()

  /** Reports the progress of [api]'s downloads once a second until cancelled. */
  suspend fun follow(api: KetchApi) {
    while (true) {
      update(backgroundProgress(api.tasks.value))
      delay(UPDATE_PERIOD)
    }
  }

  /** Reports the downloads' [progress], `null` when nothing downloads or waits. */
  fun update(progress: BackgroundProgress?) {
    when {
      progress == null -> {
        declined = false
        if (_running.value) {
          _running.value = false
          processing.end(success = true)
        }
      }
      _running.value -> processing.update(progress)
      !declined && inFront() -> {
        val accepted = processing.begin(progress)
        _running.value = accepted
        declined = !accepted
      }
    }
  }

  /** Records that iOS stopped the work before the downloads finished. */
  fun expire() {
    _running.value = false
    declined = true
  }

  /** Ends the work begun, as Ketch stops following the downloads. */
  fun close() {
    if (!_running.value) return
    _running.value = false
    processing.end(success = false)
  }

  private companion object {
    val UPDATE_PERIOD = 1.seconds
  }
}

/** What the system shows for [tasks] in the background; `null` when none downloads or waits. */
internal fun backgroundProgress(tasks: List<DownloadTask>): BackgroundProgress? {
  val ongoing = OngoingDownloads.of(tasks) ?: return null
  val downloading = tasks.filter { it.state.value is DownloadState.Downloading }
  val title = when (downloading.size) {
    0 -> ongoing.title
    1 -> downloading.single().let {
      "Downloading ${displayName(it.requestState.value, it.state.value)}"
    }
    else -> "Downloading ${downloading.size} files"
  }
  return BackgroundProgress(
    title = title,
    subtitle = ongoing.text.orEmpty(),
    permille = ongoing.permille ?: -1,
  )
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

  /**
   * Commits pending operations as Ketch goes to the background while iOS keeps its downloads
   * running: iOS may still end Ketch once they finish.
   *
   * @return the job committing them, which Ketch must stay running for, or `null` when none
   *   wait for their Undo window.
   */
  fun keepRunningInBackground(): Job? = commitPending().takeUnless { it.isCompleted }

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

  // The ids are forgotten only once every task has been resumed.
  private suspend fun resumeSaved() {
    val ids = saved.ids
    if (ids.isEmpty()) return
    // After a relaunch, the engine restores its tasks a moment after it starts.
    withTimeoutOrNull(RESTORE_TIMEOUT) {
      api.tasks.first { tasks -> tasks.any { it.taskId in ids } }
    }
    val tasks = api.tasks.value.associateBy { it.taskId }
    val resumed = ids.count { id ->
      val task = tasks[id]
      task != null && task.state.value is DownloadState.Paused && attempt("resume", task) {
        task.resume()
      }
    }
    saved.ids = emptyList()
    onResumed()
    log.i { "Resumed $resumed downloads paused for the background" }
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
