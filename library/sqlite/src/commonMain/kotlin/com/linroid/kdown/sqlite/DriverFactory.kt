package com.linroid.kdown.sqlite

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
 * Convenience function that creates both a [SqliteTaskStore] and a
 * [SqliteMetadataStore] sharing the same underlying SQLite database.
 *
 * ```kotlin
 * val (taskStore, metadataStore) = createSqliteStores(driverFactory)
 * val kdown = KDown(
 *   httpEngine = KtorHttpEngine(),
 *   metadataStore = metadataStore,
 *   taskStore = taskStore,
 * )
 * ```
 */
fun createSqliteStores(
  driverFactory: DriverFactory
): Pair<SqliteTaskStore, SqliteMetadataStore> {
  val driver = driverFactory.createDriver()
  return SqliteTaskStore(driver) to SqliteMetadataStore(driver)
}
