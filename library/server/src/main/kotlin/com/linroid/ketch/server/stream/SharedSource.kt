package com.linroid.ketch.server.stream

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException

/** How a topic that streams share ended. */
internal sealed interface TopicEnd {
  /** What it follows is gone, such as a removed torrent; nothing follows. */
  data object Completed : TopicEnd

  /** It failed, with an error code and a message for clients; nothing follows. */
  data class Failed(val error: String, val message: String) : TopicEnd
}

/**
 * What a shared topic reported last: its latest value, encoded, and how it ended. A reader that
 * skips states still sees the last value before the end.
 *
 * @property version counts the values, so readers tell a new one from the one they sent
 * @property json the latest value, or `null` before the first
 * @property end how the topic ended, or `null` while it runs
 */
internal data class TopicState(
  val version: Long = 0,
  val json: String? = null,
  val end: TopicEnd? = null,
)

/**
 * Runs one [upstream] per key while any stream subscribes to it, keeping its latest state, so
 * streams that follow the same interest share one producer and one encoding of each value. A
 * failure of the upstream becomes the [TopicEnd.Failed] that [failure] makes of it.
 */
internal class SharedSource<K : Any>(
  private val scope: CoroutineScope,
  private val upstream: (K) -> Flow<String>,
  private val failure: (K, Throwable) -> TopicEnd.Failed,
) {
  private val lock = Any()
  private val entries = HashMap<K, Entry>()

  /** The states of [key]: the latest one first, then each new one. */
  fun subscribe(key: K): Flow<TopicState> = flow {
    val entry = synchronized(lock) {
      entries.getOrPut(key) {
        val state = MutableStateFlow<TopicState?>(null)
        Entry(state, scope.launch { run(key, state) })
      }.also { it.users++ }
    }
    try {
      emitAll(entry.state.filterNotNull())
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

  private suspend fun run(key: K, state: MutableStateFlow<TopicState?>) {
    val end = try {
      upstream(key).collect { json ->
        state.update { current ->
          val last = current ?: TopicState()
          // Equal values are not repeated.
          if (last.json == json) last else last.copy(version = last.version + 1, json = json)
        }
      }
      TopicEnd.Completed
    } catch (e: CancellationException) {
      throw e
    } catch (e: Throwable) {
      failure(key, e)
    }
    state.update { (it ?: TopicState()).copy(end = end) }
  }

  private class Entry(val state: MutableStateFlow<TopicState?>, val job: Job) {
    var users = 0
  }
}
