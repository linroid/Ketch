package com.linroid.ketch.sqlite

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.native.NativeSqliteDriver

actual class DriverFactory(private val dbName: String = "ketch.db") {
  actual fun createDriver(): SqlDriver {
    return NativeSqliteDriver(KetchDatabase.Schema, dbName)
  }
}
