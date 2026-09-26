package com.linroid.ketch.torrent

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext

/** The session owns membership; peer children forward ordered events into one bounded queue. */
internal class PeerV2Pool private constructor(
  private val scope: CoroutineScope,
  private val state: TorrentBufferBudget,
  private val maxPeers: Int,
  private val output: Channel<Event>,
) {
  class Peer internal constructor(
    val blocks: PeerBlockExchange,
    internal val transport: PeerHashTransport,
    internal val admission: TorrentBufferBudget.Lease? = null,
    internal val slot: TorrentBufferBudget.Lease? = null,
  ) {
    internal val stop = CompletableDeferred<Unit>()
    internal val terminal = CompletableDeferred<Event.Closed>()
  }

  sealed interface Event {
    val peer: Peer
    fun close() = Unit
    data class Ready(
      override val peer: Peer,
      val commands: SendChannel<PeerV2DownloadActor.Command>,
    ) : Event
    data class Message(override val peer: Peer, val value: PeerV2DownloadActor.Event) : Event {
      override fun close() = value.close()
    }
    /** All peer I/O has stopped and its reader has joined before this event is published. */
    class Closed internal constructor(override val peer: Peer, val cause: Throwable?) : Event
  }

  private class RequestedStop : CancellationException("Peer stopped by session")

  private val members = mutableMapOf<Peer, Job>()
  private var closed = false
  val events: ReceiveChannel<Event> get() = output
  val size: Int get() = members.size
  val remainingCapacity: Int get() = maxPeers - members.size

  /**
   * Caller admits availability before constructing blocks. Null leaves both objects caller-owned;
   * success transfers them and optional availability admission, including pre-start cancellation.
   * Availability admission is released only on joined retirement or joined pool shutdown.
   */
  fun attach(
    transport: PeerHashTransport,
    blocks: PeerBlockExchange,
    admission: TorrentBufferBudget.Lease? = null,
  ): Peer? {
    check(!closed)
    require(members.keys.none { it.blocks === blocks || it.transport === transport }) {
      "Connection already belongs to this pool"
    }
    if (members.size == maxPeers) return null
    // Charge the forwarded-event slot per attached peer, not for every possible peer up front.
    val slot = state.reserve(PEER_SLOT_BYTES) ?: return null
    val peer = Peer(blocks, transport, admission, slot)
    val job = scope.launch(start = CoroutineStart.LAZY) {
      var cause = coroutineScope {
        val outcome = CompletableDeferred<Throwable?>()
        val owner = async {
          var failure: Throwable? = null
          try {
            PeerV2DownloadActor.run(transport, blocks, state) { actor ->
              output.send(Event.Ready(peer, actor.commands))
              for (event in actor.events) output.send(Event.Message(peer, event))
            }
          } catch (error: Throwable) {
            failure = error
          } finally {
            try { closePeer(peer) } catch (error: Throwable) {
              if (failure == null) failure = error else failure.addSuppressed(error)
            }
            outcome.complete(failure)
          }
          failure
        }
        select {
          owner.onAwait { it }
          peer.stop.onAwait {
            owner.cancel(RequestedStop())
            owner.join()
            val failure = if (outcome.isCompleted) outcome.await() else null
            if (failure is RequestedStop && failure.suppressedExceptions.isEmpty()) null else failure
          }
        }
      }
      // The owner may have been canceled before entering its own cleanup scope.
      try { closePeer(peer) } catch (error: Throwable) {
        if (cause == null) cause = error else cause.addSuppressed(error)
      }
      val terminal = Event.Closed(peer, cause)
      peer.terminal.complete(terminal)
      output.send(terminal)
    }
    members[peer] = job
    job.start()
    return peer
  }

  /** Nonblocking: wait for Closed before detaching assignments or releasing connection credit. */
  fun stop(peer: Peer): Boolean {
    check(!closed)
    if (peer !in members) return false
    return peer.stop.complete(Unit)
  }

  /** Only the exact terminal event retires a connection; delayed duplicates cannot remove it. */
  @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
  fun retire(event: Event.Closed): Boolean {
    check(!closed)
    if (!event.peer.terminal.isCompleted ||
      event.peer.terminal.getCompleted() !== event) return false
    if (members.remove(event.peer) == null) return false
    event.peer.admission?.close()
    event.peer.slot?.close()
    return true
  }

  private suspend fun shutdown() {
    closed = true
    val jobs = members.values.toList()
    jobs.forEach { it.cancel() }
    jobs.forEach { it.join() }
    var failure: Throwable? = null
    for (peer in members.keys) {
      try { closePeer(peer) } catch (error: Throwable) {
        if (failure == null) failure = error else failure.addSuppressed(error)
      } finally {
        peer.admission?.close()
        peer.slot?.close()
      }
    }
    members.clear()
    try { output.cancel() } finally { failure?.let { throw it } }
  }

  companion object {
    private const val PEER_SLOT_BYTES = 8192

    private fun closePeer(peer: Peer) {
      try { peer.blocks.close() } finally { peer.transport.close() }
    }

    /** Delivered events remain consumer-owned after this scope; every event must be closed. */
    suspend fun <T> run(
      state: TorrentBufferBudget,
      maxPeers: Int = 100,
      capacity: Int = 16,
      body: suspend (PeerV2Pool) -> T,
    ): T = coroutineScope {
      require(maxPeers in 1..500 && capacity in 1..256)
      // The shared queue, including hash timeout ticket lists. Each attached peer adds its own
      // forwarded-event slot in attach().
      val lease = checkNotNull(state.reserve(capacity * PEER_SLOT_BYTES + 2048)) {
        "Peer pool state budget exhausted"
      }
      val output = Channel<Event>(capacity, onUndeliveredElement = { it.close() })
      val pool = PeerV2Pool(this, state, maxPeers, output)
      try { body(pool) } finally {
        withContext(NonCancellable) {
          try { pool.shutdown() } finally { lease.close() }
        }
      }
    }
  }
}
