package com.linroid.ketch.sqlite

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import java.io.File

actual class DriverFactory(private val dbPath: String) {
  actual fun createDriver(): SqlDriver {
    val url = if (dbPath == ":memory:") {
      JdbcSqliteDriver.IN_MEMORY
    } else {
      File(dbPath).parentFile?.mkdirs()
      "jdbc:sqlite:$dbPath"
    }
    val driver = JdbcSqliteDriver(url)
    KetchDatabase.Schema.create(driver)
    return driver
  }
}
