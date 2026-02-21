package com.linroid.ketch.sqlite

import app.cash.sqldelight.db.SqlDriver
import com.linroid.ketch.api.DownloadRequest
import com.linroid.ketch.api.Segment
import com.linroid.ketch.api.log.KetchLogger
import com.linroid.ketch.core.task.TaskRecord
import com.linroid.ketch.core.task.TaskState
import com.linroid.ketch.core.task.TaskStore
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlin.time.Instant

/**
 * A [TaskStore] backed by SQLite via SQLDelight. Persists [TaskRecord]s
 * across process restarts.
 *
 * @param driver a platform-specific [SqlDriver] instance
 */
class SqliteTaskStore(driver: SqlDriver) : TaskStore {
  private val log = KetchLogger("SqliteStore")
  private val database = KetchDatabase(driver)
  private val queries = database.taskRecordsQueries
  private val mutex = Mutex()
  private val json = Json { ignoreUnknownKeys = true }
  private val segmentListSerializer = ListSerializer(Segment.serializer())

  /**
   * Saves a [TaskRecord] to the SQLite database. If a record with the same
   * task ID already exists, it will be replaced.
   */
  override suspend fun save(record: TaskRecord): Unit = mutex.withLock {
    log.d { "Saving task: ${record.taskId}" }
    queries.save(
      task_id = record.taskId,
      request_json = json.encodeToString(
        DownloadRequest.serializer(), record.request
      ),
      output_path = record.outputPath,
      state = record.state.name,
      total_bytes = record.totalBytes,
      downloaded_bytes = record.downloadedBytes,
      error_message = record.errorMessage,
      accept_ranges = record.acceptRanges?.let { if (it) 1L else 0L },
      etag = record.etag,
      last_modified = record.lastModified,
      segments_json = record.segments?.let {
        json.encodeToString(segmentListSerializer, it)
      },
      created_at = record.createdAt.toEpochMilliseconds(),
      updated_at = record.updatedAt.toEpochMilliseconds(),
    )
  }

  /**
   * Loads a [TaskRecord] for the given task ID. Returns `null` if not found.
   */
  override suspend fun load(taskId: String): TaskRecord? = mutex.withLock {
    val record = queries.load(taskId).executeAsOneOrNull()?.toTaskRecord()
    log.d { "Loaded task: $taskId, found=${record != null}" }
    record
  }

  /**
   * Loads all [TaskRecord]s from the database.
   */
  override suspend fun loadAll(): List<TaskRecord> = mutex.withLock {
    val records = queries.loadAll().executeAsList().map { it.toTaskRecord() }
    log.d { "Loaded all tasks: ${records.size} records" }
    records
  }

  /**
   * Removes the task record for the given task ID from the database.
   */
  override suspend fun remove(taskId: String): Unit = mutex.withLock {
    log.d { "Removing task: $taskId" }
    queries.remove(taskId)
  }

  private fun Task_records.toTaskRecord(): TaskRecord {
    return TaskRecord(
      taskId = task_id,
      request = json.decodeFromString(
        DownloadRequest.serializer(), request_json
      ),
      outputPath = output_path,
      state = TaskState.valueOf(state),
      totalBytes = total_bytes,
      downloadedBytes = downloaded_bytes,
      errorMessage = error_message,
      acceptRanges = accept_ranges?.let { it != 0L },
      etag = etag,
      lastModified = last_modified,
      segments = segments_json?.let {
        try {
          json.decodeFromString(segmentListSerializer, it)
        } catch (e: Exception) {
          log.w(e) { "Failed to deserialize segments for task: $task_id" }
          null
        }
      },
      createdAt = Instant.fromEpochMilliseconds(created_at),
      updatedAt = Instant.fromEpochMilliseconds(updated_at),
    )
  }
}
