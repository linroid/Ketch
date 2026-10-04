package com.linroid.ketch.app.state

/**
 * Keeps the Discover sessions between runs of the app.
 *
 * The app has one store per process, which every [AiDiscoverController] of the process shares,
 * so [load] returns what the last [save] passed even before it reaches the disk.
 */
interface DiscoverHistoryStore {
  /** The saved sessions, newest first; empty when there are none or they cannot be read. */
  fun load(): List<DiscoverSession>

  /** Replaces the saved sessions with [sessions], newest first; returns without waiting. */
  fun save(sessions: List<DiscoverSession>)

  /** Waits until what [save] was last given is stored. */
  suspend fun flush() {}

  /** Stores what [save] was last given and stops; later saves are dropped. */
  suspend fun close() {}
}

/** Keeps the sessions in memory only, starting from [initial]. */
class InMemoryDiscoverHistoryStore(
  initial: List<DiscoverSession> = emptyList(),
) : DiscoverHistoryStore {
  private var sessions = initial

  override fun load(): List<DiscoverSession> = sessions

  override fun save(sessions: List<DiscoverSession>) {
    this.sessions = sessions
  }
}
