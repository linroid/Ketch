package com.linroid.ketch.sqlite

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.linroid.ketch.api.DownloadRequest
import com.linroid.ketch.api.SpeedLimit
import com.linroid.ketch.core.task.TaskRecord
import com.linroid.ketch.core.task.TaskState
import kotlinx.coroutines.test.runTest
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Instant

class SqliteTaskStoreTest {

  @Test
  fun migrate_version4Database_keepsRowsWithUnknownCompletedAt() = runTest {
    val dir = Files.createTempDirectory("ketch-sqlite").toFile()
    try {
      val path = File(dir, "ketch.db").absolutePath
      // The schema of version 4, before completed_at existed.
      JdbcSqliteDriver("jdbc:sqlite:$path").use { driver ->
        driver.execute(null, VERSION_4_TABLE, 0)
        driver.execute(
          null,
          "INSERT INTO task_records(task_id, request_json, state, total_bytes) " +
            "VALUES ('a', '{\"url\":\"https://example.com/a\"}', 'COMPLETED', 100)",
          0
        )
        driver.execute(null, "PRAGMA user_version = 4", 0)
      }

      val driver = DriverFactory(path).createDriver()
      val records = try {
        SqliteTaskStore(driver).loadAll()
      } finally {
        driver.close()
      }

      val record = records.single()
      assertEquals(TaskState.COMPLETED, record.state)
      assertNull(record.completedAt)
    } finally {
      dir.deleteRecursively()
    }
  }

  @Test
  fun save_completedAt_roundTripsWithMillisecondPrecision() = runTest {
    val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
    try {
      KetchDatabase.Schema.create(driver)
      val store = SqliteTaskStore(driver)
      val completedAt = Instant.fromEpochMilliseconds(1_700_000_000_123)
      val record = TaskRecord(
        taskId = "a",
        request = DownloadRequest(url = "https://example.com/a"),
        state = TaskState.COMPLETED,
        createdAt = Instant.fromEpochMilliseconds(1_700_000_000_000),
        updatedAt = completedAt,
        completedAt = completedAt,
      )

      store.save(record)
      assertEquals(completedAt, store.load("a")?.completedAt)

      store.save(
        record.copy(request = record.request.copy(speedLimit = SpeedLimit.kbps(100)))
      )
      assertEquals(completedAt, store.load("a")?.completedAt)
    } finally {
      driver.close()
    }
  }

  @Test
  fun loadAll_unreadableRows_skipsThemAndKeepsThemStored() = runTest {
    val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
    try {
      KetchDatabase.Schema.create(driver)
      val store = SqliteTaskStore(driver)
      store.save(
        TaskRecord(
          taskId = "good",
          request = DownloadRequest(url = "https://example.com/good"),
          state = TaskState.COMPLETED,
          createdAt = Instant.fromEpochMilliseconds(1_700_000_000_000),
          updatedAt = Instant.fromEpochMilliseconds(1_700_000_000_000),
        )
      )
      driver.insertRow("malformed", "{\"url\":", "QUEUED")
      // DownloadRequest refuses a blank URL.
      driver.insertRow("blank-url", "{\"url\":\"\"}", "QUEUED")
      driver.insertRow(
        "unknown-priority",
        "{\"url\":\"https://example.com/a\",\"priority\":\"CRITICAL\"}",
        "QUEUED"
      )
      driver.insertRow("unknown-state", "{\"url\":\"https://example.com/b\"}", "ARCHIVED")

      assertEquals(listOf("good"), store.loadAll().map { it.taskId })
      assertEquals(5, driver.countRows())
    } finally {
      driver.close()
    }
  }

  private fun JdbcSqliteDriver.insertRow(taskId: String, requestJson: String, state: String) {
    execute(null, "INSERT INTO task_records(task_id, request_json, state) VALUES (?, ?, ?)", 3) {
      bindString(0, taskId)
      bindString(1, requestJson)
      bindString(2, state)
    }
  }

  private fun JdbcSqliteDriver.countRows(): Long = executeQuery(
    null,
    "SELECT count(*) FROM task_records",
    { cursor -> QueryResult.Value(if (cursor.next().value) cursor.getLong(0) else null) },
    0,
  ).value ?: 0

  private companion object {
    val VERSION_4_TABLE = """
      CREATE TABLE IF NOT EXISTS task_records (
        task_id TEXT NOT NULL PRIMARY KEY,
        request_json TEXT NOT NULL,
        output_path TEXT,
        state TEXT NOT NULL DEFAULT 'PENDING',
        total_bytes INTEGER NOT NULL DEFAULT -1,
        source_type TEXT,
        source_resume_state_json TEXT,
        segments_json TEXT,
        created_at INTEGER NOT NULL DEFAULT (CAST(strftime('%s', 'now') AS INTEGER) * 1000),
        updated_at INTEGER NOT NULL DEFAULT (CAST(strftime('%s', 'now') AS INTEGER) * 1000),
        error_json TEXT,
        download_time_ms INTEGER
      )
    """.trimIndent()
  }
}
