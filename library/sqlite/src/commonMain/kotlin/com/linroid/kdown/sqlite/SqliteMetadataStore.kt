package com.linroid.kdown.sqlite

import app.cash.sqldelight.db.SqlDriver
import com.linroid.kdown.MetadataStore
import com.linroid.kdown.model.DownloadMetadata
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json

/**
 * A [MetadataStore] backed by SQLite via SQLDelight. Stores serialized
 * [DownloadMetadata] (including segment progress) as JSON in a text column,
 * enabling segment-level resume after process restarts.
 *
 * @param driver a platform-specific [SqlDriver] instance
 */
class SqliteMetadataStore(driver: SqlDriver) : MetadataStore {
  private val database = KDownDatabase(driver)
  private val queries = database.metadataRecordsQueries
  private val mutex = Mutex()
  private val json = Json {
    prettyPrint = false
    ignoreUnknownKeys = true
  }

  /**
   * Saves [DownloadMetadata] for the given task ID. Serializes the metadata
   * to JSON and stores it in the SQLite database.
   */
  override suspend fun save(taskId: String, metadata: DownloadMetadata): Unit = mutex.withLock {
    val metadataJson = json.encodeToString(DownloadMetadata.serializer(), metadata)
    queries.save(task_id = taskId, metadata_json = metadataJson)
  }

  /**
   * Loads [DownloadMetadata] for the given task ID. Returns `null` if not
   * found or if deserialization fails.
   */
  override suspend fun load(taskId: String): DownloadMetadata? = mutex.withLock {
    val row = queries.load(taskId).executeAsOneOrNull() ?: return@withLock null
    try {
      json.decodeFromString(DownloadMetadata.serializer(), row)
    } catch (_: Exception) {
      null
    }
  }

  /**
   * Removes the metadata record for the given task ID from the database.
   */
  override suspend fun clear(taskId: String): Unit = mutex.withLock {
    queries.remove(taskId)
  }
}
