package com.linroid.ketch.sqlite

import app.cash.sqldelight.db.SqlDriver

/**
 * Creates the SQLDelight [SqlDriver] for the current platform.
 *
 * Platform-specific implementations are provided in `androidMain`,
 * `iosMain`, and `jvmMain` source sets.
 */
expect class DriverFactory {
  fun createDriver(): SqlDriver
}

/**
 * Convenience function that creates a [SqliteTaskStore] backed by
 * the platform-specific SQLite database.
 *
 * ```kotlin
 * val taskStore = createSqliteTaskStore(driverFactory)
 * val ketch = Ketch(
 *   httpEngine = KtorHttpEngine(),
 *   taskStore = taskStore,
 * )
 * ```
 */
fun createSqliteTaskStore(driverFactory: DriverFactory): SqliteTaskStore {
  return SqliteTaskStore(driverFactory.createDriver())
}
