package com.linroid.kdown.core.task

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * In-memory [TaskStore] implementation. Task records are lost when the
 * process exits. Suitable for testing or ephemeral downloads where
 * cross-restart persistence is not needed.
 */
internal class InMemoryTaskStore : TaskStore {
  private val mutex = Mutex()
  private val storage = mutableMapOf<String, TaskRecord>()

  override suspend fun save(record: TaskRecord): Unit = mutex.withLock {
    storage[record.taskId] = record
  }

  override suspend fun load(taskId: String): TaskRecord? = mutex.withLock {
    storage[taskId]
  }

  override suspend fun loadAll(): List<TaskRecord> = mutex.withLock {
    storage.values.toList()
  }

  override suspend fun remove(taskId: String): Unit = mutex.withLock {
    storage.remove(taskId)
    Unit
  }

  suspend fun clearAll(): Unit = mutex.withLock {
    storage.clear()
  }
}
