package com.linroid.ketch.torrent

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Reserves swarm ownership before starting I/O and routes snapshots by task identity. */
internal class TorrentSessionRegistry {
  private data class Entry(val infoHash: String, var session: TorrentSession? = null)

  private val mutex = Mutex()
  private val entries = mutableMapOf<String, Entry>()

  suspend fun reserve(taskId: String, infoHash: String) = mutex.withLock {
    check(taskId !in entries) { "Torrent task is already active: $taskId" }
    check(entries.values.none { it.infoHash == infoHash }) {
      "Torrent already has an active owner: $infoHash"
    }
    entries[taskId] = Entry(infoHash)
  }

  suspend fun attach(taskId: String, session: TorrentSession) = mutex.withLock {
    val entry = checkNotNull(entries[taskId]) { "Torrent task has no reservation" }
    check(entry.infoHash == session.infoHash) { "Torrent session identity mismatch" }
    check(entry.session == null) { "Torrent task already has a session" }
    entry.session = session
  }

  suspend fun session(taskId: String): TorrentSession? = mutex.withLock {
    entries[taskId]?.session
  }

  suspend fun isReserved(taskId: String): Boolean = mutex.withLock { taskId in entries }

  suspend fun release(taskId: String) = mutex.withLock {
    entries.remove(taskId)
    Unit
  }
}
