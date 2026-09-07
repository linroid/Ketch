package com.linroid.ketch.torrent

import kotlinx.coroutines.delay
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/** Counts live TCP sockets across sessions, including handshakes and metadata fetches. */
@OptIn(ExperimentalAtomicApi::class)
internal class TorrentConnectionBudget(
  private val delegate: TorrentNetwork,
  initialLimit: Int,
) : TorrentNetwork by delegate {
  private val limit = AtomicInt(initialLimit)
  private val count = AtomicInt(0)
  private val live = AtomicReference<List<TorrentConnection>>(emptyList())

  fun set(value: Int) {
    require(value in 1..4096)
    limit.store(value)
    live.load().drop(value).forEach { it.close() }
  }

  private fun reserve(): Boolean {
    while (true) {
      val current = count.load()
      if (current >= limit.load()) return false
      if (count.compareAndSet(current, current + 1)) return true
    }
  }

  override suspend fun connect(remote: PeerEndpoint): TorrentConnection {
    while (!reserve()) delay(25)
    try {
      return wrap(delegate.connect(remote))
    } catch (e: Throwable) {
      count.fetchAndAdd(-1)
      throw e
    }
  }

  override suspend fun listen(local: PeerEndpoint): TorrentListener {
    val listener = delegate.listen(local)
    return object : TorrentListener by listener {
      override suspend fun accept(): TorrentConnection {
        while (true) {
          val connection = listener.accept()
          if (reserve()) return wrap(connection)
          connection.close()
        }
      }
    }
  }

  private fun wrap(connection: TorrentConnection): TorrentConnection {
    val closed = AtomicBoolean(false)
    val wrapped = object : TorrentConnection by connection {
      override fun close() {
        if (closed.compareAndSet(false, true)) {
          try { connection.close() } finally {
            count.fetchAndAdd(-1)
            while (true) {
              val previous = live.load()
              if (live.compareAndSet(previous, previous.filterNot { it === this })) break
            }
          }
        }
      }
    }
    while (true) {
      val previous = live.load()
      if (live.compareAndSet(previous, previous + wrapped)) break
    }
    if (count.load() > limit.load()) wrapped.close()
    return wrapped
  }

  override fun close() {
    live.exchange(emptyList()).forEach { it.close() }
    delegate.close()
  }
}
