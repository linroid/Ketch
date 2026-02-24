package com.linroid.ketch.sqlite

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.linroid.ketch.api.log.KetchLogger
import java.io.File
import java.util.Properties

actual class DriverFactory(private val dbPath: String) {
  private val log = KetchLogger("SqliteDriver")

  actual fun createDriver(): SqlDriver {
    val url = if (dbPath == ":memory:") {
      JdbcSqliteDriver.IN_MEMORY
    } else {
      File(dbPath).parentFile?.mkdirs()
      "jdbc:sqlite:$dbPath"
    }
    // Legacy databases created before migration support have
    // user_version=0. JdbcSqliteDriver treats 0 as "empty" and
    // calls schema.create() (a no-op due to IF NOT EXISTS),
    // skipping migrations. Stamp version=1 so 1.sqm runs.
    if (dbPath != ":memory:" && File(dbPath).exists()) {
      val probe = JdbcSqliteDriver(url, Properties())
      probe.use { probe ->
        val version = probe.executeQuery(
          null, "PRAGMA user_version",
          { QueryResult.Value(it.getLong(0)) }, 0,
        ).value ?: 0L
        log.i { "Existing DB version: $version, schema: ${KetchDatabase.Schema.version}" }
        if (version == 0L) {
          log.i { "Legacy DB detected, stamping version=1 for migration" }
          probe.execute(null, "PRAGMA user_version = 1", 0)
        }
      }
    }
    return JdbcSqliteDriver(
      url = url,
      properties = Properties(),
      schema = KetchDatabase.Schema,
    )
  }
}
