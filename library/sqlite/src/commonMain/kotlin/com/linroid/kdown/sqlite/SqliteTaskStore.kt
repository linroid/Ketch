package com.linroid.kdown.sqlite

import app.cash.sqldelight.db.SqlDriver
import com.linroid.kdown.TaskStore
import com.linroid.kdown.model.TaskRecord
import com.linroid.kdown.model.TaskState
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.io.files.Path
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * A [TaskStore] backed by SQLite via SQLDelight. Persists [TaskRecord]s
 * across process restarts.
 *
 * @param driver a platform-specific [SqlDriver] instance
 */
class SqliteTaskStore(driver: SqlDriver) : TaskStore {
  private val database = KDownDatabase(driver)
  private val queries = database.taskRecordsQueries
  private val mutex = Mutex()
  private val json = Json { ignoreUnknownKeys = true }
  private val headersSerializer = MapSerializer(String.serializer(), String.serializer())

  override suspend fun save(record: TaskRecord): Unit = mutex.withLock {
    queries.save(
      task_id = record.taskId,
      url = record.url,
      dest_path = record.destPath.toString(),
      connections = record.connections.toLong(),
      headers = json.encodeToString(headersSerializer, record.headers),
      state = record.state.name,
      total_bytes = record.totalBytes,
      downloaded_bytes = record.downloadedBytes,
      error_message = record.errorMessage,
      created_at = record.createdAt,
      updated_at = record.updatedAt
    )
  }

  override suspend fun load(taskId: String): TaskRecord? = mutex.withLock {
    queries.load(taskId).executeAsOneOrNull()?.toTaskRecord()
  }

  override suspend fun loadAll(): List<TaskRecord> = mutex.withLock {
    queries.loadAll().executeAsList().map { it.toTaskRecord() }
  }

  override suspend fun remove(taskId: String): Unit = mutex.withLock {
    queries.remove(taskId)
  }

  private fun Task_records.toTaskRecord(): TaskRecord {
    return TaskRecord(
      taskId = task_id,
      url = url,
      destPath = Path(dest_path),
      connections = connections.toInt(),
      headers = json.decodeFromString(headersSerializer, headers),
      state = TaskState.valueOf(state),
      totalBytes = total_bytes,
      downloadedBytes = downloaded_bytes,
      errorMessage = error_message,
      createdAt = created_at,
      updatedAt = updated_at
    )
  }
}
