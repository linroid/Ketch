package com.linroid.kdown.engine

import com.linroid.kdown.DownloadPriority
import com.linroid.kdown.DownloadRequest
import com.linroid.kdown.DownloadState
import com.linroid.kdown.QueueConfig
import com.linroid.kdown.log.KDownLogger
import com.linroid.kdown.segment.Segment
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Instant

internal class DownloadScheduler(
  private val queueConfig: QueueConfig,
  private val coordinator: DownloadCoordinator,
  private val scope: CoroutineScope
) {
  private val mutex = Mutex()
  private val activeTaskIds = mutableSetOf<String>()
  private val queuedEntries = mutableListOf<QueueEntry>()
  private val hostConnectionCount = mutableMapOf<String, Int>()

  internal data class QueueEntry(
    val taskId: String,
    val request: DownloadRequest,
    val createdAt: Instant,
    val stateFlow: MutableStateFlow<DownloadState>,
    val segmentsFlow: MutableStateFlow<List<Segment>>,
    var priority: DownloadPriority = DownloadPriority.NORMAL
  )

  suspend fun enqueue(
    taskId: String,
    request: DownloadRequest,
    createdAt: Instant,
    stateFlow: MutableStateFlow<DownloadState>,
    segmentsFlow: MutableStateFlow<List<Segment>>
  ) {
    mutex.withLock {
      val host = extractHost(request.url)
      val hostCount = hostConnectionCount.getOrElse(host) { 0 }

      if (queueConfig.autoStart &&
        activeTaskIds.size < queueConfig.maxConcurrentDownloads &&
        hostCount < queueConfig.maxConnectionsPerHost
      ) {
        KDownLogger.i("Scheduler") {
          "Starting download immediately: taskId=$taskId, " +
            "active=${activeTaskIds.size}/${queueConfig.maxConcurrentDownloads}"
        }
        startTask(taskId, request, stateFlow, segmentsFlow, host)
      } else {
        val entry = QueueEntry(
          taskId = taskId,
          request = request,
          createdAt = createdAt,
          stateFlow = stateFlow,
          segmentsFlow = segmentsFlow,
          priority = request.priority
        )
        insertSorted(entry)
        stateFlow.value = DownloadState.Queued
        KDownLogger.i("Scheduler") {
          "Download queued: taskId=$taskId, priority=${request.priority}, " +
            "position=${queuedEntries.indexOfFirst { it.taskId == taskId } + 1}" +
            "/${queuedEntries.size}"
        }
      }
    }
  }

  suspend fun onTaskCompleted(taskId: String) {
    mutex.withLock {
      removeActive(taskId)
      KDownLogger.d("Scheduler") {
        "Task completed: taskId=$taskId, " +
          "active=${activeTaskIds.size}/${queueConfig.maxConcurrentDownloads}"
      }
      promoteNext()
    }
  }

  suspend fun onTaskFailed(taskId: String) {
    mutex.withLock {
      removeActive(taskId)
      KDownLogger.d("Scheduler") {
        "Task failed: taskId=$taskId, " +
          "active=${activeTaskIds.size}/${queueConfig.maxConcurrentDownloads}"
      }
      promoteNext()
    }
  }

  suspend fun onTaskCanceled(taskId: String) {
    mutex.withLock {
      removeActive(taskId)
      KDownLogger.d("Scheduler") {
        "Task canceled: taskId=$taskId, " +
          "active=${activeTaskIds.size}/${queueConfig.maxConcurrentDownloads}"
      }
      promoteNext()
    }
  }

  suspend fun setPriority(taskId: String, priority: DownloadPriority) {
    mutex.withLock {
      val index = queuedEntries.indexOfFirst { it.taskId == taskId }
      if (index < 0) {
        KDownLogger.d("Scheduler") {
          "setPriority: taskId=$taskId not in queue (may be active)"
        }
        return
      }
      val entry = queuedEntries.removeAt(index)
      entry.priority = priority
      insertSorted(entry)
      KDownLogger.i("Scheduler") {
        "Priority updated: taskId=$taskId, priority=$priority, " +
          "newPosition=${queuedEntries.indexOfFirst { it.taskId == taskId } + 1}" +
          "/${queuedEntries.size}"
      }
      promoteNext()
    }
  }

  suspend fun dequeue(taskId: String) {
    mutex.withLock {
      val removed = queuedEntries.removeAll { it.taskId == taskId }
      if (removed) {
        KDownLogger.i("Scheduler") {
          "Dequeued: taskId=$taskId"
        }
      } else if (activeTaskIds.contains(taskId)) {
        removeActive(taskId)
        coordinator.cancel(taskId)
        KDownLogger.i("Scheduler") {
          "Canceled active download: taskId=$taskId"
        }
        promoteNext()
      }
    }
  }

  private fun removeActive(taskId: String) {
    if (activeTaskIds.remove(taskId)) {
      val record = findHostForTask(taskId)
      if (record != null) {
        val count = hostConnectionCount.getOrElse(record) { 0 }
        if (count <= 1) {
          hostConnectionCount.remove(record)
        } else {
          hostConnectionCount[record] = count - 1
        }
      }
    }
  }

  private var taskHostMap = mutableMapOf<String, String>()

  private fun findHostForTask(taskId: String): String? {
    return taskHostMap.remove(taskId)
  }

  private suspend fun startTask(
    taskId: String,
    request: DownloadRequest,
    stateFlow: MutableStateFlow<DownloadState>,
    segmentsFlow: MutableStateFlow<List<Segment>>,
    host: String
  ) {
    activeTaskIds.add(taskId)
    hostConnectionCount[host] = (hostConnectionCount[host] ?: 0) + 1
    taskHostMap[taskId] = host
    coordinator.start(taskId, request, scope, stateFlow, segmentsFlow)
  }

  private suspend fun promoteNext() {
    if (!queueConfig.autoStart) return

    while (activeTaskIds.size < queueConfig.maxConcurrentDownloads) {
      val entry = findNextEligible() ?: break
      queuedEntries.remove(entry)
      val host = extractHost(entry.request.url)
      KDownLogger.i("Scheduler") {
        "Promoting queued task: taskId=${entry.taskId}, " +
          "priority=${entry.priority}, " +
          "active=${activeTaskIds.size + 1}/${queueConfig.maxConcurrentDownloads}"
      }
      startTask(
        entry.taskId, entry.request,
        entry.stateFlow, entry.segmentsFlow, host
      )
    }
  }

  private fun findNextEligible(): QueueEntry? {
    for (entry in queuedEntries) {
      val host = extractHost(entry.request.url)
      val hostCount = hostConnectionCount.getOrElse(host) { 0 }
      if (hostCount < queueConfig.maxConnectionsPerHost) {
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
  }
}
