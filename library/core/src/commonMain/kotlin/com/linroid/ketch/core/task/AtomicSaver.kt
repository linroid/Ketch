package com.linroid.ketch.core.task

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * A value saved with [store] on every [update], one update at a time. Once [closed][close],
 * as when its task is removed, updates neither save nor change it.
 */
internal class AtomicSaver<T>(
  value: T,
  private val store: suspend (T) -> Unit,
) {
  private val mutex = Mutex()

  // Read and written under the mutex only.
  private var closed = false

  var value: T = value
    private set

  suspend fun update(action: (T) -> T) = mutex.withLock {
    if (closed) return@withLock
    val updated = action(value)
    store(updated)
    value = updated
  }

  /**
   * Runs [last], such as deleting the saved value, after any update in progress, and closes this
   * saver once it returns, so no update saves after it. A [last] that throws leaves it open.
   */
  suspend fun close(last: suspend () -> Unit) = mutex.withLock {
    last()
    closed = true
  }
}
