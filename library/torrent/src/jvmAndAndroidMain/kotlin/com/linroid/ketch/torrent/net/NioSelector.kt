package com.linroid.ketch.torrent.net

import com.linroid.ketch.api.log.KetchLogger
import com.linroid.ketch.api.log.describeCauses
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine
import java.nio.channels.CancelledKeyException
import java.nio.channels.ClosedChannelException
import java.nio.channels.SelectableChannel
import java.nio.channels.SelectionKey
import java.nio.channels.Selector
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Waits for every torrent socket's readiness on one thread of its own, so connections take no
 * thread while they wait: [Handle.await] suspends until the channel can read, write, connect or
 * accept, and resumes the caller on its own dispatcher, which then does the I/O. The thread only
 * selects; it never reads, writes or runs a caller's code.
 *
 * Every registration and change runs on the selector thread, queued by [submit], so the keys and
 * waiters need no locks.
 */
internal class NioSelector private constructor(name: String) {
  private val log = KetchLogger("TorrentEngine")
  private val selector: Selector = Selector.open()
  private val tasks = ConcurrentLinkedQueue<Runnable>()
  private val wakeupPending = AtomicBoolean(false)

  init {
    Thread(::run, name).apply {
      isDaemon = true
      start()
    }
  }

  /** Registers [channel], which must be non-blocking; [Handle.close] closes it. */
  fun handle(channel: SelectableChannel): Handle = Handle(channel)

  /** A channel's waiters, one per operation; touched only on the selector thread. */
  inner class Handle internal constructor(val channel: SelectableChannel) {
    private val waiters = arrayOfNulls<Waiter>(OPS.size)
    private var key: SelectionKey? = null
    private val closed = AtomicBoolean(false)

    /**
     * Suspends until [channel] is ready for [op] (one `SelectionKey.OP_*`), or fails once the
     * handle is closed. Readiness is a hint: the caller retries its operation, which may still
     * find nothing to do. At most one caller waits for each operation at a time.
     */
    suspend fun await(op: Int) {
      if (closed.get()) throw ClosedChannelException()
      suspendCancellableCoroutine { continuation ->
        val waiter = Waiter(op, continuation)
        submit { register(waiter) }
        continuation.invokeOnCancellation { submit { unregister(waiter) } }
      }
    }

    /** Closes [channel] and fails its waiters; idempotent. */
    fun close() {
      if (!closed.compareAndSet(false, true)) return
      try {
        channel.close()
      } catch (e: Exception) {
        log.d { "Closing a torrent socket failed: ${e.describeCauses()}" }
      }
      submit { failAll(ClosedChannelException()) }
    }

    private fun register(waiter: Waiter) {
      if (closed.get()) return waiter.fail(ClosedChannelException())
      val index = OPS.indexOf(waiter.op)
      if (waiters[index] != null) {
        return waiter.fail(IllegalStateException("Another caller already waits for this socket"))
      }
      val current = try {
        key ?: channel.register(selector, 0, this).also { key = it }
      } catch (e: Exception) {
        return waiter.fail(e)
      }
      if (!current.isValid) return waiter.fail(ClosedChannelException())
      waiters[index] = waiter
      updateInterest()
    }

    private fun unregister(waiter: Waiter) {
      val index = OPS.indexOf(waiter.op)
      if (waiters[index] === waiter) {
        waiters[index] = null
        updateInterest()
      }
    }

    internal fun ready() {
      val ready = try {
        key?.readyOps() ?: return
      } catch (_: CancelledKeyException) {
        return failAll(ClosedChannelException())
      }
      for (index in OPS.indices) {
        if (ready and OPS[index] == 0) continue
        val waiter = waiters[index] ?: continue
        waiters[index] = null
        waiter.resume()
      }
      updateInterest()
    }

    private fun failAll(error: Exception) {
      for (index in OPS.indices) {
        val waiter = waiters[index] ?: continue
        waiters[index] = null
        waiter.fail(error)
      }
    }

    private fun updateInterest() {
      val current = key ?: return
      var interest = 0
      for (index in OPS.indices) if (waiters[index] != null) interest = interest or OPS[index]
      try {
        current.interestOps(interest)
      } catch (_: CancelledKeyException) {
        failAll(ClosedChannelException())
      }
    }
  }

  internal class Waiter(val op: Int, private val continuation: CancellableContinuation<Unit>) {
    fun resume() {
      if (continuation.isActive) continuation.resume(Unit)
    }

    fun fail(error: Throwable) {
      if (continuation.isActive) continuation.resumeWithException(error)
    }
  }

  private fun submit(task: Runnable) {
    tasks.add(task)
    if (wakeupPending.compareAndSet(false, true)) selector.wakeup()
  }

  private fun run() {
    while (true) {
      try {
        wakeupPending.set(false)
        while (true) (tasks.poll() ?: break).run()
        if (selector.select() == 0) continue
        val selected = selector.selectedKeys().iterator()
        while (selected.hasNext()) {
          val key = selected.next()
          selected.remove()
          (key.attachment() as Handle).ready()
        }
      } catch (e: Exception) {
        // A failure here must not stop the thread every torrent socket waits on.
        log.w { "Torrent socket selector failed: ${e.describeCauses()}" }
      }
    }
  }

  companion object {
    private val OPS = intArrayOf(
      SelectionKey.OP_READ,
      SelectionKey.OP_WRITE,
      SelectionKey.OP_CONNECT,
      SelectionKey.OP_ACCEPT,
    )

    /** The selector every torrent network shares, started on first use and kept for the process. */
    val shared: NioSelector by lazy { NioSelector("ketch-torrent-selector") }
  }
}
