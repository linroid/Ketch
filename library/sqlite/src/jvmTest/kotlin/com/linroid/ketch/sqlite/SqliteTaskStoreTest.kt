package com.linroid.ketch.sqlite

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.linroid.ketch.api.DownloadRequest
import com.linroid.ketch.api.SpeedLimit
import com.linroid.ketch.core.task.TaskCommandRecord
import com.linroid.ketch.core.task.TaskControl
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

  @Test
  fun save_control_roundTrips() = runTest {
    val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
    try {
      KetchDatabase.Schema.create(driver)
      val store = SqliteTaskStore(driver)
      val control = TaskControl(
        selectionGeneration = 3,
        seeding = true,
        commands = listOf(
          TaskCommandRecord(
            key = "retry-key",
            digest = "0".repeat(64),
            epoch = "epoch",
            sequence = 7,
            selectionGeneration = 3,
            recordedAtMs = 1_700_000_000_000,
          )
        ),
      )
      val record = record("a").copy(control = control)

      store.save(record)
      assertEquals(control, store.load("a")?.control)

      store.save(record.copy(control = null))
      assertNull(store.load("a")?.control)
    } finally {
      driver.close()
    }
  }

  @Test
  fun load_unreadableControl_keepsTheRecord() = runTest {
    val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
    try {
      KetchDatabase.Schema.create(driver)
      val store = SqliteTaskStore(driver)
      store.save(record("a").copy(control = TaskControl(selectionGeneration = 1)))
      driver.execute(null, "UPDATE task_records SET control_json = '{\"selectionGeneration\":'", 0)
      store.save(record("b"))
      driver.execute(
        null,
        "UPDATE task_records SET control_json = '{\"selectionGeneration\":-1}' " +
          "WHERE task_id = 'b'",
        0
      )

      val records = store.loadAll().associateBy { it.taskId }

      assertEquals(setOf("a", "b"), records.keys)
      assertNull(records.getValue("a").control)
      assertNull(records.getValue("b").control)
      assertEquals(TaskState.COMPLETED, records.getValue("a").state)
    } finally {
      driver.close()
    }
  }

  @Test
  fun migrate_fromVersion5_addsTheControlColumn() = runTest {
    val dir = Files.createTempDirectory("ketch-sqlite").toFile()
    try {
      val path = File(dir, "ketch.db").absolutePath
      // The schema of version 5, before control_json existed.
      JdbcSqliteDriver("jdbc:sqlite:$path").use { driver ->
        driver.execute(null, VERSION_5_TABLE, 0)
        driver.execute(
          null,
          "INSERT INTO task_records(task_id, request_json, state, total_bytes, completed_at) " +
            "VALUES ('old', '{\"url\":\"https://example.com/a\"}', 'COMPLETED', 100, 5)",
          0
        )
        driver.execute(null, "PRAGMA user_version = 5", 0)
      }

      val driver = DriverFactory(path).createDriver()
      val records = try {
        val store = SqliteTaskStore(driver)
        store.save(record("new").copy(control = TaskControl(selectionGeneration = 2)))
        store.loadAll().associateBy { it.taskId }
      } finally {
        driver.close()
      }

      val old = records.getValue("old")
      assertNull(old.control)
      assertEquals(Instant.fromEpochMilliseconds(5), old.completedAt)
      assertEquals(2, records.getValue("new").control?.selectionGeneration)
    } finally {
      dir.deleteRecursively()
    }
  }

  private fun record(taskId: String) = TaskRecord(
    taskId = taskId,
    request = DownloadRequest(url = "https://example.com/$taskId"),
    state = TaskState.COMPLETED,
    createdAt = Instant.fromEpochMilliseconds(1_700_000_000_000),
    updatedAt = Instant.fromEpochMilliseconds(1_700_000_000_000),
  )

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
    val VERSION_5_TABLE = """
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
        download_time_ms INTEGER,
        completed_at INTEGER
      )
    """.trimIndent()

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
