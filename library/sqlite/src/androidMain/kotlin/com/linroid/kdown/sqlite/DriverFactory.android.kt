package com.linroid.kdown.sqlite

import android.content.Context
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.android.AndroidSqliteDriver

actual class DriverFactory(
  private val context: Context,
  private val dbName: String = "kdown.db",
) {
  actual fun createDriver(): SqlDriver {
    return AndroidSqliteDriver(KDownDatabase.Schema, context, dbName)
  }
}
