package com.linroid.ketch.engine

import com.linroid.ketch.api.ProxyConfig
import io.ktor.http.Url
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * Where the redirects of a request led last time, so that the segments of a download go
 * straight to the server that answered the probe instead of following the redirects again, which
 * a mirror selector could answer with another mirror. Each [Target] keeps the headers its hop
 * was allowed, so a target on another origin never receives the original credentials. Targets
 * are kept per proxy, since servers can redirect requests from each proxy elsewhere.
 *
 * Holds at most [capacity] targets, the least recently used dropped first, each for [ttl].
 */
internal class RedirectCache(
  private val capacity: Int = 64,
  private val ttl: Duration = 30.minutes,
  private val timeSource: TimeSource = TimeSource.Monotonic,
) {
  /** A request as the caller made it: its URL, the headers sent to that URL and its proxy. */
  data class Key(
    val url: String,
    val headers: Map<String, String>,
    val proxy: ProxyConfig = ProxyConfig.System,
  )

  /** The final hop of a request: its URL and the headers it was sent with. */
  class Target(val url: Url, val headers: Map<String, String>)

  private class Entry(val target: Target, val expiresAt: TimeMark)

  private val lock = Mutex()

  // Kept in use order, the least recently used first.
  private val entries = LinkedHashMap<Key, Entry>()

  suspend fun get(key: Key): Target? = lock.withLock {
    val entry = entries.remove(key) ?: return@withLock null
    if (entry.expiresAt.hasPassedNow()) return@withLock null
    entries[key] = entry
    entry.target
  }

  suspend fun put(key: Key, target: Target) {
    lock.withLock {
      entries.remove(key)
      entries[key] = Entry(target, timeSource.markNow() + ttl)
      while (entries.size > capacity) entries.remove(entries.keys.first())
    }
  }

  suspend fun remove(key: Key) {
    lock.withLock { entries.remove(key) }
  }
}
