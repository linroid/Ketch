package com.linroid.ketch.core.engine

import com.linroid.ketch.api.DownloadPriority
import com.linroid.ketch.api.DownloadRequest
import com.linroid.ketch.api.DownloadState
import com.linroid.ketch.api.Segment
import com.linroid.ketch.api.log.KetchLogger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.concurrent.Volatile
import kotlin.time.Instant

internal class DownloadQueue(
  maxConcurrentDownloads: Int,
  maxConnectionsPerHost: Int,
  private val coordinator: DownloadCoordinator,
) {
  private val log = KetchLogger("DownloadQueue")
  private val mutex = Mutex()
  private val activeEntries = mutableMapOf<String, QueueEntry>()
  private val queuedEntries = mutableListOf<QueueEntry>()
  private val hostConnectionCount = mutableMapOf<String, Int>()

  /** Effective max concurrent downloads (0 = unlimited → Int.MAX_VALUE). */
  @Volatile
  var maxConcurrent: Int = effectiveLimit(maxConcurrentDownloads)
    internal set(value) {
      field = effectiveLimit(value)
    }

  /** Effective max connections per host (0 = unlimited → Int.MAX_VALUE). */
  @Volatile
  var maxPerHost: Int = effectiveLimit(maxConnectionsPerHost)
    internal set(value) {
      field = effectiveLimit(value)
    }

  internal data class QueueEntry(
    val taskId: String,
    val request: DownloadRequest,
    val createdAt: Instant,
    val stateFlow: MutableStateFlow<DownloadState>,
    val segmentsFlow: MutableStateFlow<List<Segment>>,
    var priority: DownloadPriority = DownloadPriority.NORMAL,
    var preempted: Boolean = false,
  )

  suspend fun enqueue(
    taskId: String,
    request: DownloadRequest,
    createdAt: Instant,
    stateFlow: MutableStateFlow<DownloadState>,
    segmentsFlow: MutableStateFlow<List<Segment>>,
    preferResume: Boolean = false,
  ) {
    mutex.withLock {
      val host = extractHost(request.url)
      val hostCount = hostConnectionCount.getOrElse(host) { 0 }

      val entry = QueueEntry(
        taskId = taskId,
        request = request,
        createdAt = createdAt,
        stateFlow = stateFlow,
        segmentsFlow = segmentsFlow,
        priority = request.priority,
        preempted = preferResume,
      )

      if (activeEntries.size < maxConcurrent &&
        hostCount < maxPerHost
      ) {
        log.i {
          "Starting download immediately: taskId=$taskId, " +
            "active=${activeEntries.size}/" +
            "$maxConcurrent"
        }
        startTask(entry, host)
      } else if (request.priority == DownloadPriority.URGENT) {
        tryPreemptAndStart(entry, host)
      } else {
        insertSorted(entry)
        stateFlow.value = DownloadState.Queued
        log.i {
          "Download queued: taskId=$taskId, " +
            "priority=${request.priority}, " +
            "position=${
              queuedEntries.indexOfFirst {
                it.taskId == taskId
              } + 1
            }/${queuedEntries.size}"
        }
      }
    }
  }

  /**
   * Preempts the lowest-priority active download to make room for
   * an [DownloadPriority.URGENT] task. The preempted task is paused
   * and re-queued so it resumes automatically when a slot opens.
   */
  private suspend fun tryPreemptAndStart(
    entry: QueueEntry,
    host: String,
  ) {
    val victim = activeEntries.values
      .filter { it.priority < DownloadPriority.URGENT }
      .minByOrNull { it.priority.ordinal }

    if (victim == null) {
      insertSorted(entry)
      entry.stateFlow.value = DownloadState.Queued
      log.i {
        "Cannot preempt: all active tasks are URGENT. " +
          "Queuing taskId=${entry.taskId}"
      }
      return
    }

    log.i {
      "Preempting taskId=${victim.taskId} " +
        "(priority=${victim.priority}) for URGENT " +
        "taskId=${entry.taskId}"
    }

    coordinator.pause(victim.taskId)
    removeActive(victim.taskId)

    victim.preempted = true
    victim.stateFlow.value = DownloadState.Queued
    insertSorted(victim)

    val hostCount = hostConnectionCount.getOrElse(host) { 0 }
    if (activeEntries.size < maxConcurrent &&
      hostCount < maxPerHost
    ) {
      log.i {
        "Starting URGENT download: taskId=${entry.taskId}, " +
          "active=${activeEntries.size + 1}/" +
          "$maxConcurrent"
      }
      startTask(entry, host)
    } else {
      insertSorted(entry)
      entry.stateFlow.value = DownloadState.Queued
      log.i {
        "URGENT taskId=${entry.taskId} queued " +
          "(host limit still exceeded)"
      }
    }
  }

  suspend fun onTaskCompleted(taskId: String) {
    mutex.withLock {
      removeActive(taskId)
      log.d {
        "Task completed: taskId=$taskId, " +
          "active=${activeEntries.size}/" +
          "$maxConcurrent"
      }
      promoteNext()
    }
  }

  suspend fun onTaskFailed(taskId: String) {
    mutex.withLock {
      removeActive(taskId)
      log.d {
        "Task failed: taskId=$taskId, " +
          "active=${activeEntries.size}/" +
          "${maxConcurrent}"
      }
      promoteNext()
    }
  }

  suspend fun onTaskCanceled(taskId: String) {
    mutex.withLock {
      removeActive(taskId)
      log.d {
        "Task canceled: taskId=$taskId, " +
          "active=${activeEntries.size}/" +
          "${maxConcurrent}"
      }
      promoteNext()
    }
  }

  suspend fun setPriority(taskId: String, priority: DownloadPriority) {
    mutex.withLock {
      val index = queuedEntries.indexOfFirst { it.taskId == taskId }
      if (index < 0) {
        log.d {
          "setPriority: taskId=$taskId not in queue " +
            "(may be active)"
        }
        return
      }
      val entry = queuedEntries.removeAt(index)
      entry.priority = priority
      insertSorted(entry)
      log.i {
        "Priority updated: taskId=$taskId, " +
          "priority=$priority, " +
          "newPosition=${
            queuedEntries.indexOfFirst {
              it.taskId == taskId
            } + 1
          }/${queuedEntries.size}"
      }
      promoteNext()
    }
  }

  suspend fun dequeue(taskId: String) {
    mutex.withLock {
      val removed = queuedEntries.removeAll { it.taskId == taskId }
      if (removed) {
        log.i { "Dequeued: taskId=$taskId" }
      } else if (activeEntries.containsKey(taskId)) {
        removeActive(taskId)
        log.i {
          "Removed active download from tracking: " +
            "taskId=$taskId"
        }
        promoteNext()
      }
    }
  }

  private fun removeActive(taskId: String) {
    if (activeEntries.remove(taskId) != null) {
      val host = findHostForTask(taskId)
      if (host != null) {
        val count = hostConnectionCount.getOrElse(host) { 0 }
        if (count <= 1) {
          hostConnectionCount.remove(host)
        } else {
          hostConnectionCount[host] = count - 1
        }
      }
    }
  }

  private var taskHostMap = mutableMapOf<String, String>()

  private fun findHostForTask(taskId: String): String? {
    return taskHostMap.remove(taskId)
  }

  private suspend fun startTask(
    entry: QueueEntry,
    host: String,
  ) {
    activeEntries[entry.taskId] = entry
    hostConnectionCount[host] = (hostConnectionCount[host] ?: 0) + 1
    taskHostMap[entry.taskId] = host
    if (entry.preempted) {
      entry.preempted = false
      val resumed = coordinator.resume(
        entry.taskId, entry.stateFlow, entry.segmentsFlow,
      )
      if (!resumed) {
        coordinator.start(
          entry.taskId, entry.request,
          entry.stateFlow, entry.segmentsFlow,
        )
      }
    } else {
      coordinator.start(
        entry.taskId, entry.request,
        entry.stateFlow, entry.segmentsFlow,
      )
    }
  }

  private suspend fun promoteNext() {
    while (activeEntries.size < maxConcurrent) {
      val entry = findNextEligible() ?: break
      queuedEntries.remove(entry)
      val host = extractHost(entry.request.url)
      log.i {
        "Promoting queued task: taskId=${entry.taskId}, " +
          "priority=${entry.priority}, " +
          "active=${activeEntries.size + 1}/" +
          "${maxConcurrent}"
      }
      startTask(entry, host)
    }
  }

  private fun findNextEligible(): QueueEntry? {
    for (entry in queuedEntries) {
      val host = extractHost(entry.request.url)
      val hostCount = hostConnectionCount.getOrElse(host) { 0 }
      if (hostCount < maxPerHost) {
        return entry
      }
    }
    return null
  }

  private fun insertSorted(entry: QueueEntry) {
    val insertIndex = queuedEntries.indexOfFirst { existing ->
      entry.priority.ordinal > existing.priority.ordinal ||
        (entry.priority == existing.priority &&
          entry.createdAt < existing.createdAt)
    }
    if (insertIndex < 0) {
      queuedEntries.add(entry)
    } else {
      queuedEntries.add(insertIndex, entry)
    }
  }

  companion object {
    internal fun extractHost(url: String): String {
      val afterScheme = url.substringAfter("://", "")
      if (afterScheme.isEmpty()) return url
      val hostPort = afterScheme.substringBefore("/")
      return hostPort.substringBefore(":")
    }

    private fun effectiveLimit(value: Int): Int =
      if (value > 0) value else Int.MAX_VALUE
  }
}
