package com.linroid.kdown.sqlite

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.native.NativeSqliteDriver

actual class DriverFactory {
  actual fun createDriver(): SqlDriver {
    return NativeSqliteDriver(KDownDatabase.Schema, "kdown.db")
  }
}
