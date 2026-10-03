package com.linroid.ketch.sqlite

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.linroid.ketch.api.log.KetchLogger
import okio.FileSystem
import okio.IOException
import java.io.File
import java.sql.SQLException
import java.util.Properties

/**
 * Opens the database at [dbPath], creating it or bringing its schema up to date, or an
 * in-memory one for `":memory:"`.
 *
 * A file SQLite reports as corrupt or as not a database is moved aside to
 * `<dbPath>.broken-<time>`, with its journal, and an empty database takes its place. Other
 * failures, such as a locked or unwritable file, are thrown, as is the original failure when the
 * file cannot be moved.
 *
 * @param onUnreadable hears of a database that was moved aside, before [createDriver] returns.
 */
actual class DriverFactory(
  private val dbPath: String,
  private val onUnreadable: (UnreadableDatabase) -> Unit = {},
) {
  private val log = KetchLogger("SqliteDriver")

  actual fun createDriver(): SqlDriver {
    if (dbPath == IN_MEMORY_PATH) {
      return JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY, Properties(), KetchDatabase.Schema)
    }
    File(dbPath).parentFile?.mkdirs()
    return try {
      open()
    } catch (e: Exception) {
      if (!e.isUnreadableDatabase()) throw e
      val movedTo = try {
        moveDatabaseAside(FileSystem.SYSTEM, dbPath)
      } catch (moveFailure: IOException) {
        e.addSuppressed(moveFailure)
        throw e
      }
      log.w(e) { "Moved the unreadable database $dbPath aside to $movedTo, starting empty" }
      onUnreadable(UnreadableDatabase(dbPath, movedTo, e))
      open()
    }
  }

  /**
   * Opens the file and brings its schema up to date on a driver that is closed again if that
   * fails, so an unreadable file is not held open while it is moved aside.
   */
  private fun open(): SqlDriver {
    val driver = JdbcSqliteDriver("jdbc:sqlite:$dbPath", Properties())
    try {
      migrate(driver)
    } catch (e: Throwable) {
      driver.close()
      throw e
    }
    return driver
  }

  private fun migrate(driver: SqlDriver) {
    val schema = KetchDatabase.Schema
    KetchDatabase(driver).transaction {
      var version = driver.userVersion()
      log.i { "Existing DB version: $version, schema: ${schema.version}" }
      // Databases created before migration support have user_version=0 too, but already hold
      // the table, so 1.sqm and later must still run on them.
      if (version == 0L && driver.hasTaskRecords()) {
        log.i { "Legacy DB detected, migrating from version 1" }
        version = 1
      }
      when {
        version == 0L -> schema.create(driver)
        version < schema.version -> schema.migrate(driver, version, schema.version)
        else -> return@transaction
      }
      driver.execute(null, "PRAGMA user_version = ${schema.version}", 0)
    }
  }

  private companion object {
    const val IN_MEMORY_PATH = ":memory:"

    // SQLITE_CORRUPT and SQLITE_NOTADB, the primary result codes sqlite-jdbc reports.
    val UNREADABLE_CODES = setOf(11, 26)

    fun Throwable.isUnreadableDatabase(): Boolean = generateSequence(this) { it.cause }
      .any { it is SQLException && it.errorCode in UNREADABLE_CODES }

    fun SqlDriver.userVersion(): Long = executeQuery(
      null,
      "PRAGMA user_version",
      { cursor -> QueryResult.Value(if (cursor.next().value) cursor.getLong(0) else null) },
      0,
    ).value ?: 0L

    fun SqlDriver.hasTaskRecords(): Boolean = executeQuery(
      null,
      "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = 'task_records'",
      { cursor -> QueryResult.Value(cursor.next().value) },
      0,
    ).value
  }
}
