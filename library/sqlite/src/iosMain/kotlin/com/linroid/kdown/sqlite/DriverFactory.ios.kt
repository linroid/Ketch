package com.linroid.kdown.sqlite

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.native.NativeSqliteDriver

actual class DriverFactory(private val dbPath: String) {
  actual fun createDriver(): SqlDriver {
    return NativeSqliteDriver(KDownDatabase.Schema, dbPath)
  }
}
