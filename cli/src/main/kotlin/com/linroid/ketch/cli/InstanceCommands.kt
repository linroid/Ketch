package com.linroid.ketch.cli

import com.linroid.ketch.api.Destination
import com.linroid.ketch.api.DownloadRequest
import com.linroid.ketch.api.DownloadState
import com.linroid.ketch.api.DownloadTask
import com.linroid.ketch.api.KetchApi
import com.linroid.ketch.api.KetchFeatures
import com.linroid.ketch.api.PauseReason
import com.linroid.ketch.api.log.describeCauses
import com.linroid.ketch.remote.ConnectionState
import com.linroid.ketch.remote.RemoteApiException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.io.PrintStream
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

/** Exit statuses of the attaching commands, for scripts that run them. */
internal object InstanceExit {
  /** The command did what it was asked; `watch` saw every task it watched complete. */
  const val OK = 0

  /**
   * The command failed: no instance, a refusal, a task that matches nothing, a watched task that
   * failed, was canceled or removed, or a lost connection.
   */
  const val FAILED = 1

  /** The arguments cannot be used. */
  const val USAGE = 2
}

/**
 * Runs [command] on the instance [locate] finds, printing results to [out] and everything else
 * to [err], and returns its exit status, one of [InstanceExit].
 */
internal suspend fun runInstanceCommand(
  command: InstanceCommand,
  locate: () -> InstanceEndpoint,
  out: PrintStream,
  err: PrintStream,
): Int = try {
  // The desktop app may still be starting: the locator waits for it on this thread.
  val endpoint = withContext(Dispatchers.IO) { locate() }
  when (command) {
    is InstanceCommand.Add -> attach(endpoint, syncTasks = false).use { add(it, command, out) }
    is InstanceCommand.ListTasks -> attach(endpoint).use { list(it, command, out) }
    is InstanceCommand.Pause -> attach(endpoint).use { pause(it, command, out, err) }
    is InstanceCommand.Resume -> attach(endpoint).use { resume(it, command, out, err) }
    is InstanceCommand.Watch -> attach(endpoint).use { attachment ->
      watchTasks(
        ketch = attachment.ketch,
        connection = attachment.ketch.connectionState,
        ids = command.ids,
        out = out,
        err = err,
        label = endpoint.label,
      )
    }
  }
} catch (e: CliFailure) {
  err.println("Error: ${e.message}")
  InstanceExit.FAILED
}

private suspend fun add(
  attachment: Attachment,
  command: InstanceCommand.Add,
  out: PrintStream,
): Int {
  val endpoint = attachment.endpoint
  val supportsRequestIds = KetchFeatures.REQUEST_ID in attachment.status.features
  if (command.requestId != null && !supportsRequestIds) {
    throw CliFailure("${endpoint.label} doesn't support --idempotency-key; update it.")
  }
  if (command.proxy != null && KetchFeatures.PROXY !in attachment.status.features) {
    throw CliFailure("${endpoint.label} doesn't support --proxy or --no-proxy; update it.")
  }
  val request = try {
    DownloadRequest(
      url = command.url,
      destination = destinationFor(command.destination, endpoint.local),
      connections = command.connections,
      headers = command.headers,
      properties = mapOf(ORIGIN_PROPERTY to CLI_ORIGIN),
      speedLimit = command.speedLimit,
      priority = command.priority,
      // Lets a submission whose reply was lost be sent again without adding it twice.
      requestId = command.requestId ?: Uuid.random().toString().takeIf { supportsRequestIds },
      proxy = command.proxy,
    )
  } catch (e: IllegalArgumentException) {
    throw CliFailure(e.message ?: "Invalid download")
  }
  val task = submitDownload(attachment.ketch, request, endpoint)
  out.println(if (command.json) taskJson(task).toString() else task.taskId)
  return InstanceExit.OK
}

/**
 * Adds [request] on [ketch], the instance at [endpoint]. A request with an ID is sent again, up
 * to [attempts] times, when the connection fails or the instance has an internal error: an
 * instance that added it the first time answers with the same task.
 *
 * @throws CliFailure if the instance refused it, or the outcome is unknown
 */
internal suspend fun submitDownload(
  ketch: KetchApi,
  request: DownloadRequest,
  endpoint: InstanceEndpoint,
  attempts: Int = 3,
  retryDelay: Duration = 1.seconds,
): DownloadTask {
  var attempt = 1
  while (true) {
    val failure = try {
      return ketch.download(request)
    } catch (e: CancellationException) {
      throw e
    } catch (e: RemoteApiException) {
      if (e.status < 500) throw refused(endpoint, e)
      e
    } catch (e: Exception) {
      e
    }
    if (request.requestId == null || attempt >= attempts) {
      val retry = request.requestId?.let {
        " To try again without adding it twice, run the command again with " +
          "--idempotency-key $it."
      }.orEmpty()
      throw CliFailure(
        "Couldn't tell whether ${endpoint.label} added the download " +
          "(${failure.describeCauses()}).$retry"
      )
    }
    delay(retryDelay * attempt)
    attempt++
  }
}

/**
 * The [Destination] for the `add` argument [argument]. For an instance on this machine
 * ([local]), a relative path is resolved against [workingDir], as other commands do, and an
 * existing directory or a path ending in a separator keeps the name from the source. Another
 * device's paths go as they are. No argument saves to the instance's download directory.
 */
internal fun destinationFor(
  argument: String?,
  local: Boolean,
  workingDir: File = File("").absoluteFile,
  isDirectory: (File) -> Boolean = File::isDirectory,
): Destination? {
  if (argument == null) return null
  if (!local) return Destination(argument)
  val given = File(argument)
  val file = (if (given.isAbsolute) given else File(workingDir, argument)).toPath().normalize()
    .toFile()
  val directory = argument.endsWith('/') || argument.endsWith('\\') || isDirectory(file)
  return Destination(if (directory) file.path + File.separatorChar else file.path)
}

private fun list(
  attachment: Attachment,
  command: InstanceCommand.ListTasks,
  out: PrintStream,
): Int {
  val tasks = attachment.ketch.tasks.value.sortedBy { it.createdAt }
  if (command.json) {
    out.println(buildJsonArray { tasks.forEach { add(taskJson(it)) } })
  } else {
    out.println(formatTaskTable(tasks))
  }
  return InstanceExit.OK
}

private suspend fun pause(
  attachment: Attachment,
  command: InstanceCommand.Pause,
  out: PrintStream,
  err: PrintStream,
): Int {
  val tasks = attachment.ketch.tasks.value
  val (targets, unmatched) = if (command.all) {
    // Waiting tasks first, so none of them starts in the slot of a running one paused before it.
    val waiting = tasks.filter { it.state.value.waitsInQueue }
    val running = tasks.filter { it.state.value is DownloadState.Downloading }
    (waiting + running) to emptyList()
  } else {
    matchTasks(tasks, command.ids)
  }
  return act(targets, unmatched, out, err, verb = "pause") { it.pause() }
}

private suspend fun resume(
  attachment: Attachment,
  command: InstanceCommand.Resume,
  out: PrintStream,
  err: PrintStream,
): Int {
  val tasks = attachment.ketch.tasks.value
  val (targets, unmatched) = if (command.all) {
    tasks.filter { it.state.value.isPausedUntilResumed } to emptyList()
  } else {
    matchTasks(tasks, command.ids)
  }
  return act(targets, unmatched, out, err, verb = "resume") { it.resume() }
}

/** Runs [action] on each of [targets], printing each one's state after it. */
private suspend fun act(
  targets: List<DownloadTask>,
  unmatched: List<String>,
  out: PrintStream,
  err: PrintStream,
  verb: String,
  action: suspend (DownloadTask) -> Unit,
): Int {
  var failed = unmatched.isNotEmpty()
  unmatched.forEach { err.println("Error: $it") }
  for (task in targets) {
    try {
      action(task)
      val name = displayName(task).printable()
      out.println("${task.taskId.take(SHORT_ID_LENGTH)}  ${stateLabel(task)}  $name")
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      failed = true
      val reason = (e as? RemoteApiException)?.message ?: e.describeCauses()
      err.println("Error: couldn't $verb ${task.taskId.take(SHORT_ID_LENGTH)}: $reason")
    }
  }
  return if (failed) InstanceExit.FAILED else InstanceExit.OK
}

/**
 * The tasks [ids] name, each by its full ID or a prefix only it starts with, in the order given
 * and without repeats, and why each ID that names no single task cannot be used.
 */
internal fun matchTasks(
  tasks: List<DownloadTask>,
  ids: List<String>,
): Pair<List<DownloadTask>, List<String>> {
  val matched = LinkedHashMap<String, DownloadTask>()
  val problems = mutableListOf<String>()
  for (id in ids) {
    val exact = tasks.find { it.taskId.equals(id, ignoreCase = true) }
    val candidates = if (exact != null) listOf(exact) else {
      tasks.filter { it.taskId.startsWith(id, ignoreCase = true) }
    }
    when {
      id.isBlank() || candidates.isEmpty() -> problems += "no task matches '$id'"
      candidates.size > 1 ->
        problems += "'$id' matches ${candidates.size} tasks; give more of the task ID"
      else -> matched.getOrPut(candidates.single().taskId) { candidates.single() }
    }
  }
  return matched.values.toList() to problems
}

/**
 * Prints the tasks of [ketch] as JSON lines while they change: each task as it is (`snapshot`),
 * then tasks `added`, their `state` and `progress` changes, and the IDs of those `removed`. With
 * [ids], it follows only the tasks they name and returns once each has finished: completed,
 * failed, canceled or removed. Without, it runs until the connection is lost.
 *
 * @return [InstanceExit.OK] when every watched task completed, [InstanceExit.FAILED] when one did
 *   not or the [connection] was lost
 * @throws CliFailure if an ID names no single task
 */
internal suspend fun watchTasks(
  ketch: KetchApi,
  connection: StateFlow<ConnectionState>,
  ids: List<String>,
  out: PrintStream,
  err: PrintStream,
  label: String,
): Int = coroutineScope {
  val watched: Set<String>? = if (ids.isEmpty()) null else {
    val (tasks, problems) = matchTasks(ketch.tasks.value, ids)
    if (problems.isNotEmpty()) throw CliFailure(problems.joinToString("; "))
    tasks.map { it.taskId }.toSet()
  }
  val result = CompletableDeferred<Int>()
  // Whether each watched task that finished completed.
  val finished = mutableMapOf<String, Boolean>()
  fun finish(taskId: String, completed: Boolean) {
    if (watched == null || taskId !in watched || taskId in finished) return
    finished[taskId] = completed
    if (finished.keys == watched) {
      result.complete(if (finished.values.all { it }) InstanceExit.OK else InstanceExit.FAILED)
    }
  }

  launch {
    connection.first { it !is ConnectionState.Connected }
    err.println("Error: lost the connection to $label")
    result.complete(InstanceExit.FAILED)
  }
  launch {
    val trackers = mutableMapOf<String, Job>()
    var initial = true
    ketch.tasks.collect { tasks ->
      val shown = tasks.filter { watched == null || it.taskId in watched }
      val ids = shown.map { it.taskId }.toSet()
      for (removed in trackers.keys - ids) {
        trackers.remove(removed)?.cancel()
        out.println(buildJsonObject {
          put("event", "removed")
          put("taskId", removed)
        })
        finish(removed, completed = false)
      }
      for (task in shown) {
        if (task.taskId in trackers) continue
        val firstEvent = if (initial) "snapshot" else "added"
        trackers[task.taskId] = launch {
          var previous: DownloadState? = null
          combine(task.state, task.queuePosition) { state, position -> state to position }
            .distinctUntilChanged()
            .collect { (state, _) ->
              val event = when {
                previous == null -> firstEvent
                previous is DownloadState.Downloading && state is DownloadState.Downloading ->
                  "progress"
                else -> "state"
              }
              previous = state
              out.println(buildJsonObject {
                put("event", event)
                putTask(task)
              })
              if (state.isTerminal) finish(task.taskId, state is DownloadState.Completed)
            }
        }
      }
      initial = false
    }
  }
  result.await().also { coroutineContext.cancelChildren() }
}

/** Whether a task in this state waits in the queue: queued, or paused for an urgent download. */
private val DownloadState.waitsInQueue: Boolean
  get() = this is DownloadState.Queued ||
    (this is DownloadState.Paused && reason is PauseReason.Preempted)

/** Whether a task in this state stays paused until someone resumes it. */
private val DownloadState.isPausedUntilResumed: Boolean
  get() = this is DownloadState.Paused && reason !is PauseReason.Preempted
