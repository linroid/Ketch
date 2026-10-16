package com.linroid.ketch.server.stream

import com.linroid.ketch.api.DownloadTask
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong

/**
 * Follows [tasks] and keeps a [TaskFrame] of each, telling its listeners the ID of every task
 * that was added, changed or removed. It starts with the tasks as they are, so a stream opened
 * right after it already sees them, and follows them until [stop].
 */
internal class TaskTracker(
  private val tasks: StateFlow<List<DownloadTask>>,
  scope: CoroutineScope,
) {
  private val versions = AtomicLong()
  private val frames = ConcurrentHashMap<String, TaskFrame>()
  private val listeners = CopyOnWriteArrayList<(String) -> Unit>()

  @Volatile
  private var order: List<String>
  private val job: Job

  init {
    val current = tasks.value
    for (task in current) frames[task.taskId] = TaskFrame.of(task, versions.incrementAndGet())
    order = current.map { it.taskId }
    job = scope.launch { follow() }
  }

  /** The frame of the task with [taskId], or `null` when there is no such task. */
  fun frame(taskId: String): TaskFrame? = frames[taskId]

  /** The frames of every task, in the order of [tasks]. */
  fun frames(): List<TaskFrame> = order.mapNotNull { frames[it] }

  fun addListener(listener: (String) -> Unit) {
    listeners += listener
  }

  fun removeListener(listener: (String) -> Unit) {
    listeners -= listener
  }

  fun stop() {
    job.cancel()
  }

  private suspend fun follow(): Nothing = coroutineScope {
    val followers = HashMap<String, Job>()
    tasks.collect { list ->
      val ids = list.mapTo(LinkedHashSet()) { it.taskId }
      order = list.map { it.taskId }
      for (taskId in followers.keys - ids) {
        followers.remove(taskId)?.cancel()
        // The follower may still be writing; only this removal is final.
        frames.remove(taskId)
        notify(taskId)
      }
      for (task in list) {
        if (task.taskId in followers) continue
        if (frames.putIfAbsent(task.taskId, TaskFrame.of(task, nextVersion())) == null) {
          notify(task.taskId)
        }
        followers[task.taskId] = launch { followTask(task) }
      }
    }
  }

  private suspend fun followTask(task: DownloadTask) {
    combine(
      task.requestState,
      task.state,
      task.segments,
      task.queuePosition,
    ) { request, state, segments, position ->
      var changed = false
      // Never brings back the frame of a task that was removed meanwhile.
      frames.computeIfPresent(task.taskId) { _, frame ->
        frame.next(request, state, segments, position, ::nextVersion)
          .also { changed = it !== frame }
      }
      changed
    }.collect { changed ->
      if (changed) notify(task.taskId)
    }
  }

  private fun nextVersion(): Long = versions.incrementAndGet()

  private fun notify(taskId: String) {
    for (listener in listeners) listener(taskId)
  }
}
