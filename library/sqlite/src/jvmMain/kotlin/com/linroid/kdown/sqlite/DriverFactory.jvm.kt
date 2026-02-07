package com.linroid.kdown.sqlite

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver

actual class DriverFactory(private val dbPath: String = "kdown.db") {
  actual fun createDriver(): SqlDriver {
    val url = if (dbPath == ":memory:") {
      JdbcSqliteDriver.IN_MEMORY
    } else {
      "jdbc:sqlite:$dbPath"
    }
    val driver = JdbcSqliteDriver(url)
    KDownDatabase.Schema.create(driver)
    return driver
  }
}
