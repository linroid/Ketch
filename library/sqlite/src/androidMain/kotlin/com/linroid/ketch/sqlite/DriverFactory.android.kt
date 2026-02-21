package com.linroid.ketch.sqlite

import android.content.Context
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.android.AndroidSqliteDriver

actual class DriverFactory(
  private val context: Context,
  private val dbName: String = "ketch.db",
) {
  actual fun createDriver(): SqlDriver {
    return AndroidSqliteDriver(KetchDatabase.Schema, context, dbName)
  }
}
