package com.linroid.kdown

import com.linroid.kdown.model.TaskRecord

/**
 * Pluggable persistence layer for download task records. Implementations
 * store [TaskRecord] objects so that tasks can be enumerated and restored
 * after a process restart.
 *
 * The default implementation is [com.linroid.kdown.internal.InMemoryTaskStore],
 * which keeps records in memory and loses them on restart. For true persistence,
 * use a durable implementation such as the SQLite-backed store from the
 * `library:sqlite` module.
 *
 * @see MetadataStore for segment-level download metadata persistence
 */
interface TaskStore {
  /** Save or update a task record. */
  suspend fun save(record: TaskRecord)

  /** Load a single task record by its ID, or null if not found. */
  suspend fun load(taskId: String): TaskRecord?

  /** Load all stored task records. */
  suspend fun loadAll(): List<TaskRecord>

  /** Remove a task record by its ID. */
  suspend fun remove(taskId: String)
}
