package com.linroid.ketch.server.stream

import com.linroid.ketch.endpoints.stream.StreamError
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch

/** An item of a topic that streams share: a value, the end of its subject, or a failure. */
internal sealed interface SourceItem<out T> {
  data class Value<T>(val value: T) : SourceItem<T>

  /** The subject is gone, such as a removed torrent; nothing follows. */
  data object Removed : SourceItem<Nothing>

  /** The topic failed; nothing follows. */
  data class Failed(val error: StreamError) : SourceItem<Nothing>
}

/**
 * Runs one [upstream] per key while any stream subscribes to it, keeping its latest item, so
 * streams that ask for the same torrent or the same connection limit share one producer. The
 * upstream must turn its failures into items: it runs in [scope].
 */
internal class SharedSource<K : Any, T : Any>(
  private val scope: CoroutineScope,
  private val upstream: (K) -> Flow<SourceItem<T>>,
) {
  private val lock = Any()
  private val entries = HashMap<K, Entry<T>>()

  /** The items of [key]: the latest one first, then each new one; equal ones are not repeated. */
  fun subscribe(key: K): Flow<SourceItem<T>> = flow {
    val entry = synchronized(lock) {
      entries.getOrPut(key) {
        val latest = MutableStateFlow<SourceItem<T>?>(null)
        Entry(latest, scope.launch { upstream(key).collect { latest.value = it } })
      }.also { it.users++ }
    }
    try {
      emitAll(entry.latest.filterNotNull())
    } finally {
      synchronized(lock) {
        if (--entry.users == 0) {
          entries.remove(key)
          entry.job.cancel()
        }
      }
    }
  }

  /** Keys with an upstream running, for tests. */
  internal val activeKeys: Set<K> get() = synchronized(lock) { entries.keys.toSet() }

  private class Entry<T>(val latest: MutableStateFlow<SourceItem<T>?>, val job: Job) {
    var users = 0
  }
}
