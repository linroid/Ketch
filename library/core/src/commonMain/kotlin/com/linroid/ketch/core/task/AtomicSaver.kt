package com.linroid.ketch.core.task

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal class AtomicSaver<T>(
  value: T,
  private val store: suspend (T) -> Unit,
) {
  private val mutex = Mutex()

  var value: T = value
    private set

  suspend fun update(action: (T) -> T) = mutex.withLock {
    val updated = action(value)
    store(updated)
    value = updated
  }
}
