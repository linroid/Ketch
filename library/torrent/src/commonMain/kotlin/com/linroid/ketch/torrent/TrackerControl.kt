package com.linroid.ketch.torrent

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** One outstanding manual request, serialized with periodic discovery and owned by its lifetime. */
internal class TrackerControl private constructor(
  private val scope: CoroutineScope,
  private var operation: (suspend (Boolean) -> TrackerResponse?)?,
  private var publish: (suspend (TrackerResponse) -> Unit)?,
  private var readStatus: (() -> List<TrackerStatus>)?,
  private var publishStatus: ((List<TrackerStatus>) -> Unit)?,
) {
  private val mutex = Mutex()
  private val manual = Semaphore(1)

  suspend fun poll(): Boolean = perform(false)

  suspend fun reannounce(): Boolean {
    if (!manual.tryAcquire()) return false
    try {
      val pending = scope.async { perform(true) }
      try { return pending.await() } finally {
        withContext(NonCancellable) { pending.cancelAndJoin() }
      }
    } finally { manual.release() }
  }

  private suspend fun perform(requested: Boolean): Boolean = mutex.withLock {
    val action = checkNotNull(operation) { "Tracker control is closed" }
    try {
      val response = action(requested) ?: return@withLock false
      checkNotNull(publish).invoke(response)
      true
    } finally {
      checkNotNull(publishStatus).invoke(checkNotNull(readStatus).invoke())
    }
  }

  companion object {
    suspend fun <T> run(
      state: TorrentBufferBudget,
      operation: suspend (Boolean) -> TrackerResponse?,
      publish: suspend (TrackerResponse) -> Unit,
      readStatus: () -> List<TrackerStatus>,
      publishStatus: (List<TrackerStatus>) -> Unit,
      body: suspend (TrackerControl) -> T,
    ): T = coroutineScope {
      val bytes = (readStatus().size.toLong() + 1) * 256 + 8192
      require(bytes <= Int.MAX_VALUE)
      val lease = checkNotNull(state.reserve(bytes.toInt())) { "Tracker control budget exhausted" }
      val job = SupervisorJob(coroutineContext[Job])
      val control = TrackerControl(CoroutineScope(coroutineContext + job), operation, publish,
        readStatus, publishStatus)
      try { body(control) } finally {
        withContext(NonCancellable) {
          try { job.cancelAndJoin() } finally {
            control.operation = null
            control.publish = null
            control.readStatus = null
            control.publishStatus = null
            lease.close()
          }
        }
      }
    }
  }
}
