package com.linroid.ketch.torrent

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Reserves swarm ownership before starting I/O and routes snapshots by task identity. */
internal class TorrentSessionRegistry {
  private class Entry(val infoHash: String) {
    var session: TorrentSession? = null
    val released = CompletableDeferred<Unit>()
  }

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

  /** The task that owns [infoHash]'s swarm, if any. */
  suspend fun ownerOf(infoHash: String): String? = mutex.withLock {
    entries.entries.firstOrNull { it.value.infoHash == infoHash }?.key
  }

  suspend fun isReserved(taskId: String): Boolean = mutex.withLock { taskId in entries }

  /** How many tasks hold a reservation; for leak checks. */
  suspend fun size(): Int = mutex.withLock { entries.size }

  suspend fun release(taskId: String) = mutex.withLock {
    entries.remove(taskId)?.released?.complete(Unit)
    Unit
  }

  /** Suspends until [taskId]'s current reservation, if any, is released. */
  suspend fun awaitRelease(taskId: String) {
    val entry = mutex.withLock { entries[taskId] } ?: return
    entry.released.await()
  }
}
