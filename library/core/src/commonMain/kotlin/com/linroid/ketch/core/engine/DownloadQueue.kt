package com.linroid.ketch.core.engine

import com.linroid.ketch.api.Destination
import com.linroid.ketch.api.DownloadPriority
import com.linroid.ketch.api.DownloadState
import com.linroid.ketch.api.log.KetchLogger
import com.linroid.ketch.core.task.TaskHandle
import com.linroid.ketch.core.task.TaskState
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.concurrent.Volatile
import kotlin.time.Clock

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
    private set

  /** Effective max downloads per host (0 = unlimited → Int.MAX_VALUE). */
  @Volatile
  var maxPerHost: Int = effectiveLimit(maxConnectionsPerHost)
    private set

  /**
   * Replaces the concurrency limits (`0` = unlimited). Raising a limit
   * immediately starts queued tasks that now fit. Lowering one never
   * stops running tasks: they finish normally and queued tasks wait
   * until the active count drops below the new limit.
   */
  suspend fun updateLimits(maxConcurrentDownloads: Int, maxConnectionsPerHost: Int) {
    mutex.withLock {
      maxConcurrent = effectiveLimit(maxConcurrentDownloads)
      maxPerHost = effectiveLimit(maxConnectionsPerHost)
      log.i { "Limits updated: maxConcurrent=$maxConcurrent, maxPerHost=$maxPerHost" }
      promoteNext()
    }
  }

  internal data class QueueEntry(
    val handle: TaskHandle,
    var priority: DownloadPriority = DownloadPriority.NORMAL,
    var preempted: Boolean = false,
    val destination: Destination? = null,
  ) {
    val taskId get() = handle.taskId
  }

  suspend fun enqueue(
    handle: TaskHandle,
    preferResume: Boolean = false,
    destination: Destination? = null,
  ) {
    mutex.withLock {
      if (queuedEntries.any { it.taskId == handle.taskId }) return
      if (activeEntries.containsKey(handle.taskId)) {
        if (!handle.mutableState.value.isTerminal) return
        removeActive(handle.taskId)
      }
      markQueued(handle)
      val entry = QueueEntry(
        handle = handle,
        priority = handle.request.priority,
        preempted = preferResume,
        destination = destination,
      )
      insertSorted(entry)
      promoteNext()
      if (entry.priority == DownloadPriority.URGENT && queuedEntries.remove(entry)) {
        tryPreemptAndStart(entry, extractHost(handle.request.url))
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
    host: String?,
  ) {
    val hostIsFull = !hasHostCapacity(host)
    val victim = activeEntries.values
      .filter { it.priority < DownloadPriority.URGENT }
      .filter { !hostIsFull || extractHost(it.handle.request.url) == host }
      .minByOrNull { it.priority.ordinal }

    if (victim == null) {
      insertSorted(entry)
      entry.handle.mutableState.value = DownloadState.Queued
      log.i {
        "Cannot preempt: no eligible lower-priority active task. " +
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
    markQueued(victim.handle)
    insertSorted(victim)

    if (activeEntries.size < maxConcurrent && hasHostCapacity(host)) {
      log.i {
        "Starting URGENT download: taskId=${entry.taskId}, " +
          "active=${activeEntries.size + 1}/" +
          "$maxConcurrent"
      }
      startTask(entry, host)
    } else {
      insertSorted(entry)
      entry.handle.mutableState.value = DownloadState.Queued
      log.i {
        "URGENT taskId=${entry.taskId} queued " +
          "(host limit still exceeded)"
      }
    }
  }

  suspend fun onTaskCompleted(taskId: String, expectedState: DownloadState? = null) {
    mutex.withLock {
      if (expectedState != null &&
        activeEntries[taskId]?.handle?.mutableState?.value !== expectedState
      ) return
      removeActive(taskId)
      log.d {
        "Task completed: taskId=$taskId, " +
          "active=${activeEntries.size}/" +
          "$maxConcurrent"
      }
      promoteNext()
    }
  }

  suspend fun onTaskFailed(taskId: String, expectedState: DownloadState? = null) {
    mutex.withLock {
      if (expectedState != null &&
        activeEntries[taskId]?.handle?.mutableState?.value !== expectedState
      ) return
      removeActive(taskId)
      log.d {
        "Task failed: taskId=$taskId, " +
          "active=${activeEntries.size}/" +
          "${maxConcurrent}"
      }
      promoteNext()
    }
  }

  suspend fun onTaskCanceled(taskId: String, expectedState: DownloadState? = null) {
    mutex.withLock {
      if (expectedState != null &&
        activeEntries[taskId]?.handle?.mutableState?.value !== expectedState
      ) return
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
      activeEntries[taskId]?.let { entry ->
        entry.priority = priority
        promoteNext()
        val urgentEntries = queuedEntries.filter { it.priority == DownloadPriority.URGENT }
        for (urgent in urgentEntries) {
          queuedEntries.remove(urgent)
          tryPreemptAndStart(urgent, extractHost(urgent.handle.request.url))
        }
        return
      }
      val index = queuedEntries.indexOfFirst { it.taskId == taskId }
      if (index < 0) return
      val entry = queuedEntries.removeAt(index)
      entry.priority = priority
      val host = extractHost(entry.handle.request.url)
      if (activeEntries.size < maxConcurrent && hasHostCapacity(host)) {
        startTask(entry, host)
      } else if (priority == DownloadPriority.URGENT) {
        tryPreemptAndStart(entry, host)
      } else {
        insertSorted(entry)
      }
      log.i { "Priority updated: taskId=$taskId, priority=$priority" }
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
    host: String?,
  ) {
    activeEntries[entry.taskId] = entry
    if (host != null) {
      hostConnectionCount[host] = (hostConnectionCount[host] ?: 0) + 1
      taskHostMap[entry.taskId] = host
    }
    if (entry.preempted) {
      entry.preempted = false
      val resumed = coordinator.resume(entry.handle, entry.destination)
      if (!resumed) {
        coordinator.start(entry.handle)
      }
    } else {
      coordinator.start(entry.handle)
    }
  }

  private suspend fun promoteNext() {
    while (activeEntries.size < maxConcurrent) {
      val entry = findNextEligible() ?: break
      queuedEntries.remove(entry)
      val host = extractHost(entry.handle.request.url)
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
    return queuedEntries.firstOrNull { hasHostCapacity(extractHost(it.handle.request.url)) }
  }

  /** Host-less URIs (see [extractHost]) are not subject to the per-host limit. */
  private fun hasHostCapacity(host: String?): Boolean =
    host == null || hostConnectionCount.getOrElse(host) { 0 } < maxPerHost

  private suspend fun markQueued(handle: TaskHandle) {
    handle.record.update {
      it.copy(state = TaskState.QUEUED, updatedAt = Clock.System.now())
    }
    handle.mutableState.value = DownloadState.Queued
  }

  private fun insertSorted(entry: QueueEntry) {
    val insertIndex = queuedEntries.indexOfFirst { existing ->
      entry.priority.ordinal > existing.priority.ordinal ||
        (entry.priority == existing.priority &&
          entry.handle.createdAt < existing.handle.createdAt)
    }
    if (insertIndex < 0) {
      queuedEntries.add(entry)
    } else {
      queuedEntries.add(insertIndex, entry)
    }
  }

  companion object {
    private val AUTHORITY = Regex("""^([A-Za-z][A-Za-z0-9+.\-]*)://([^/?#]*)""")

    /**
     * Returns the key used for the per-host limit: the URL host in lower
     * case, without user info, port or IPv6 brackets. Returns `null` for
     * URIs without a network host — magnet links, `torrent:` identifiers,
     * `file:` URLs and local paths — which the per-host limit ignores.
     */
    internal fun extractHost(url: String): String? {
      val match = AUTHORITY.find(url.trim()) ?: return null
      if (match.groupValues[1].equals("file", ignoreCase = true)) return null
      val hostPort = match.groupValues[2].substringAfterLast('@')
      val host = if (hostPort.startsWith('[')) {
        hostPort.substring(1).substringBefore(']')
      } else {
        hostPort.substringBefore(':')
      }
      return host.lowercase().ifEmpty { null }
    }

    private fun effectiveLimit(value: Int): Int =
      if (value > 0) value else Int.MAX_VALUE
  }
}
