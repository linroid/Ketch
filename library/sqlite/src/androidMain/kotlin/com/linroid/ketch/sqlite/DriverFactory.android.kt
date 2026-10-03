package com.linroid.ketch.sqlite

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import com.linroid.ketch.api.log.KetchLogger
import com.linroid.ketch.api.log.describeCauses
import okio.FileSystem
import okio.IOException

/**
 * Opens the database [dbName] in the app's databases folder.
 *
 * When SQLite reports it corrupt, as it opens or later, it is moved aside to
 * `<name>.broken-<time>`, with its journal, rather than deleted, and Android opens an empty
 * database in its place. A file that cannot be moved is deleted, as Android does by default.
 *
 * @param onUnreadable hears of a database that was moved aside or deleted.
 */
actual class DriverFactory(
  private val context: Context,
  private val dbName: String = "ketch.db",
  private val onUnreadable: (UnreadableDatabase) -> Unit = {},
) {
  actual fun createDriver(): SqlDriver {
    return AndroidSqliteDriver(
      schema = KetchDatabase.Schema,
      context = context,
      name = dbName,
      callback = MoveAsideCallback(onUnreadable),
    )
  }
}

private class MoveAsideCallback(
  private val onUnreadable: (UnreadableDatabase) -> Unit,
) : AndroidSqliteDriver.Callback(KetchDatabase.Schema) {
  private val log = KetchLogger("SqliteDriver")

  override fun onCorruption(db: SupportSQLiteDatabase) {
    val path = db.path
    if (path == null || path == IN_MEMORY_PATH) {
      super.onCorruption(db)
      return
    }
    log.w { "SQLite reported the database $path corrupt" }
    if (db.isOpen) {
      // Later operations on it fail, as they do after Android's own handling.
      try {
        db.close()
      } catch (e: IOException) {
        log.w { "Couldn't close the corrupt database: ${e.describeCauses()}" }
      }
    }
    val movedTo = try {
      moveDatabaseAside(FileSystem.SYSTEM, path).also {
        log.w { "Moved the corrupt database aside to $it, starting empty" }
      }
    } catch (e: IOException) {
      log.w { "Couldn't move the corrupt database aside, deleting it: ${e.describeCauses()}" }
      super.onCorruption(db)
      null
    }
    onUnreadable(UnreadableDatabase(path, movedTo, cause = null))
  }

  private companion object {
    const val IN_MEMORY_PATH = ":memory:"
  }
}
