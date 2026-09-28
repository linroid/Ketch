package com.linroid.ketch.torrent

import com.linroid.ketch.api.DownloadPriority
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Admission to the engine's [TorrentConfig.maxActiveTorrents] slots.
 *
 * A download that finds every slot taken waits instead of failing: higher [DownloadPriority]
 * first, then arrival order. Waiting is cancellable and holds no engine resources. Seeding is
 * optional background work, so the oldest seeder's slot goes to the next download, and a
 * finished download does not start seeding while another download is waiting.
 */
@OptIn(ExperimentalAtomicApi::class)
internal class TorrentActiveSlots(private val capacity: Int) {
  private class Waiter(val priority: DownloadPriority, val signal: CompletableDeferred<Unit>) {
    var granted = false
  }

  private val mutex = Mutex()
  private val lifetime = Job()
  private val closed = AtomicBoolean(false)
  private var used = 0
  // Seeders hold counted slots. Insertion order is lending order, so the first is the oldest.
  private val seeders = linkedSetOf<String>()
  // Invariant: waiters exist only while every slot is used and none is lent to a seeder.
  private val waiters = mutableListOf<Waiter>()

  init { require(capacity > 0) }

  /**
   * Suspends until the caller owns a slot and returns the task ID of the seeder whose slot it
   * took, if any; the caller must stop that seeder before starting its own session. [onWait]
   * runs once before suspending, and only when no slot is available.
   */
  suspend fun acquire(
    priority: DownloadPriority = DownloadPriority.NORMAL,
    onWait: suspend () -> Unit = {},
  ): String? {
    val waiter = mutex.withLock {
      check(!closed.load()) { "Torrent source is closed" }
      if (waiters.isEmpty()) {
        if (used < capacity) {
          used++
          return null
        }
        seeders.firstOrNull()?.let { seeder ->
          seeders.remove(seeder)
          return seeder
        }
      }
      Waiter(priority, CompletableDeferred(lifetime)).also { waiter ->
        val index = waiters.indexOfFirst { it.priority < priority }
        if (index < 0) waiters.add(waiter) else waiters.add(index, waiter)
      }
    }
    var acquired = false
    try {
      onWait()
      waiter.signal.await()
      acquired = true
      return null
    } catch (e: CancellationException) {
      // Closing cancels the waiter's signal, not the caller; report that as a failure.
      currentCoroutineContext().ensureActive()
      check(!closed.load()) { "Torrent source is closed" }
      throw e
    } finally {
      if (!acquired) withContext(NonCancellable) {
        mutex.withLock {
          // A slot granted concurrently with cancellation passes on instead of leaking.
          if (waiter.granted) releaseLocked() else waiters.remove(waiter)
        }
        waiter.signal.cancel()
      }
    }
  }

  /**
   * Lends the caller's slot to its seeding session. False when a download is waiting: the caller
   * keeps the slot and must stop the session, then [release] the slot.
   */
  suspend fun lend(taskId: String): Boolean = mutex.withLock {
    if (closed.load() || waiters.isNotEmpty()) return@withLock false
    seeders.add(taskId)
    true
  }

  /**
   * Takes back the slot lent to [taskId]. True when the caller now owns it and must [release] it
   * after stopping the seeder; false when a download already took it.
   */
  suspend fun reclaim(taskId: String): Boolean = mutex.withLock { seeders.remove(taskId) }

  /** Returns an owned slot, handing it straight to the first waiter. */
  suspend fun release() = mutex.withLock { releaseLocked() }

  private fun releaseLocked() {
    val next = waiters.removeFirstOrNull()
    if (next == null) {
      check(used > 0) { "Torrent slot released twice" }
      used--
    } else {
      next.granted = true
      next.signal.complete(Unit)
    }
  }

  /** Fails current and future waiters; slots already owned are simply released later. */
  fun close() {
    closed.store(true)
    lifetime.cancel()
  }
}
