package com.linroid.ketch.sqlite

/**
 * A database file SQLite reported as corrupt or not a database, which [DriverFactory] moved
 * aside, keeping it, to start with an empty database in its place.
 *
 * @property path where the database was.
 * @property movedTo where the unreadable file is now, or `null` when it could not be moved and
 *   was deleted instead.
 * @property cause what SQLite reported, when the platform passes it on.
 */
class UnreadableDatabase(
  val path: String,
  val movedTo: String?,
  val cause: Throwable?,
)
