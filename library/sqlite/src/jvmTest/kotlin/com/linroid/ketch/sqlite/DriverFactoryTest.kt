package com.linroid.ketch.sqlite

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.linroid.ketch.api.DownloadRequest
import com.linroid.ketch.core.task.TaskRecord
import kotlinx.coroutines.test.runTest
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Instant

class DriverFactoryTest {
  private val dir = Files.createTempDirectory("ketch-sqlite").toFile()
  private val db = File(dir, "ketch.db")

  @AfterTest
  fun deleteDir() {
    dir.deleteRecursively()
  }

  @Test
  fun createDriver_notADatabase_movesItAsideAndStartsEmpty() = runTest {
    val garbage = "This file is not a SQLite database. ".repeat(64)
    db.writeText(garbage)
    val reported = mutableListOf<UnreadableDatabase>()

    val driver = DriverFactory(db.path) { reported += it }.createDriver()
    val records = try {
      val store = SqliteTaskStore(driver)
      store.save(record("a"))
      store.loadAll()
    } finally {
      driver.close()
    }

    assertEquals(listOf("a"), records.map { it.taskId })
    val unreadable = reported.single()
    assertEquals(db.path, unreadable.path)
    val movedTo = assertNotNull(unreadable.movedTo)
    assertTrue(movedTo.startsWith("${db.path}.broken-"), movedTo)
    assertEquals(garbage, File(movedTo).readText())
  }

  @Test
  fun createDriver_emptyFile_createsTheSchema() = runTest {
    db.createNewFile()

    val driver = DriverFactory(db.path).createDriver()
    val records = try {
      val store = SqliteTaskStore(driver)
      store.save(record("a"))
      store.loadAll()
    } finally {
      driver.close()
    }

    assertEquals(listOf("a"), records.map { it.taskId })
  }

  @Test
  fun createDriver_legacyDatabaseWithoutVersion_migratesIt() = runTest {
    JdbcSqliteDriver("jdbc:sqlite:${db.path}").use { driver ->
      driver.execute(null, LEGACY_TABLE, 0)
      driver.execute(
        null,
        "INSERT INTO task_records(task_id, request_json, state, etag) " +
          "VALUES ('a', '{\"url\":\"https://example.com/a\"}', 'COMPLETED', 'abc')",
        0
      )
    }

    // Opened twice: the second open finds the schema up to date.
    repeat(2) {
      val driver = DriverFactory(db.path).createDriver()
      val records = try {
        SqliteTaskStore(driver).loadAll()
      } finally {
        driver.close()
      }
      assertEquals(listOf("a"), records.map { it.taskId })
    }
  }

  private fun record(taskId: String) = TaskRecord(
    taskId = taskId,
    request = DownloadRequest(url = "https://example.com/$taskId"),
    createdAt = Instant.fromEpochMilliseconds(1_700_000_000_000),
    updatedAt = Instant.fromEpochMilliseconds(1_700_000_000_000),
  )

  private companion object {
    // The table before migrations existed, which left user_version at 0.
    val LEGACY_TABLE = """
      CREATE TABLE task_records (
        task_id TEXT NOT NULL PRIMARY KEY,
        request_json TEXT NOT NULL,
        output_path TEXT,
        state TEXT NOT NULL DEFAULT 'PENDING',
        total_bytes INTEGER NOT NULL DEFAULT -1,
        accept_ranges INTEGER,
        etag TEXT,
        last_modified TEXT,
        segments_json TEXT,
        created_at INTEGER NOT NULL DEFAULT (CAST(strftime('%s', 'now') AS INTEGER) * 1000),
        updated_at INTEGER NOT NULL DEFAULT (CAST(strftime('%s', 'now') AS INTEGER) * 1000)
      )
    """.trimIndent()
  }
}
