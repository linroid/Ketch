package com.linroid.ketch.sqlite

import app.cash.sqldelight.db.SqlDriver
import com.linroid.ketch.api.DownloadRequest
import com.linroid.ketch.api.KetchError
import com.linroid.ketch.api.Segment
import com.linroid.ketch.api.log.KetchLogger
import com.linroid.ketch.core.engine.SourceResumeState
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
  private val errorSerializer = KetchError.serializer()
  private val segmentListSerializer = ListSerializer(Segment.serializer())
  private val resumeStateSerializer = SourceResumeState.serializer()

  /**
   * Saves a [TaskRecord] to the SQLite database. If a record with the same
   * task ID already exists, it will be replaced.
   */
  override suspend fun save(record: TaskRecord): Unit = mutex.withLock {
    log.d { "Saving task: ${record.taskId}" }
    val requestJson = json.encodeToString(
      DownloadRequest.serializer(), record.request
    )
    val segmentsJson = record.segments?.let {
      json.encodeToString(segmentListSerializer, it)
    }
    val errorJson = record.error?.let {
      json.encodeToString(errorSerializer, it)
    }
    val resumeStateJson = record.sourceResumeState?.let {
      json.encodeToString(resumeStateSerializer, it)
    }
    queries.transaction {
      queries.insertOrIgnore(
        task_id = record.taskId,
        request_json = requestJson,
        output_path = record.outputPath,
        state = record.state.name,
        total_bytes = record.totalBytes,
        source_type = record.sourceType,
        source_resume_state_json = resumeStateJson,
        segments_json = segmentsJson,
        error_json = errorJson,
      )
      queries.update(
        task_id = record.taskId,
        request_json = requestJson,
        output_path = record.outputPath,
        state = record.state.name,
        total_bytes = record.totalBytes,
        source_type = record.sourceType,
        source_resume_state_json = resumeStateJson,
        segments_json = segmentsJson,
        error_json = errorJson,
      )
    }
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
      error = error_json?.let {
        try {
          json.decodeFromString(errorSerializer, it)
        } catch (e: Exception) {
          log.w(e) { "Failed to deserialize error for task: $task_id" }
          null
        }
      },
      segments = segments_json?.let {
        try {
          json.decodeFromString(segmentListSerializer, it)
        } catch (e: Exception) {
          log.w(e) { "Failed to deserialize segments for task: $task_id" }
          null
        }
      },
      sourceType = source_type,
      sourceResumeState = source_resume_state_json?.let {
        try {
          json.decodeFromString(resumeStateSerializer, it)
        } catch (e: Exception) {
          log.w(e) {
            "Failed to deserialize resume state for task: $task_id"
          }
          null
        }
      },
      createdAt = Instant.fromEpochMilliseconds(created_at),
      updatedAt = Instant.fromEpochMilliseconds(updated_at),
    )
  }
}
