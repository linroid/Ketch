package com.linroid.kdown.core.task


/**
 * Pluggable persistence layer for download task records. Implementations
 * store [TaskRecord] objects (including segment-level progress) so that
 * tasks can be enumerated and resumed after a process restart.
 *
 * The default implementation is [InMemoryTaskStore],
 * which keeps records in memory and loses them on restart. For true persistence,
 * use a durable implementation such as the SQLite-backed store from the
 * `library:sqlite` module.
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
