package com.linroid.ketch.torrent

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.onTimeout
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext

/** One actor receives frames; one child reads, with at most one frame queued ahead of dispatch. */
internal class PeerV2Inbox private constructor(
  private val frames: Channel<PeerHashTransport.Frame>,
  private val transport: PeerHashTransport,
  private val blocks: PeerBlockExchange,
) {
  private var preferCommands = true

  sealed interface Event<out C> {
    /** The actor must close the frame after dispatch, including when dispatch throws. */
    data class Frame(val value: PeerHashTransport.Frame) : Event<Nothing>
    data class HashTimeout(val tickets: List<PeerHashExchange.Ticket>) : Event<Nothing>
    data class Command<C>(val value: C) : Event<C>
  }

  /** Wait for a frame or response expiry; peer silence cannot postpone a pending deadline. */
  suspend fun next(): Event<Nothing> = receive(null)

  /** Commands come from an actor-owned bounded queue; its sender defines payload ownership. */
  suspend fun <C> next(commands: ReceiveChannel<C>): Event<C> = receive(commands)

  @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
  private suspend fun <C> receive(commands: ReceiveChannel<C>?): Event<C> {
    while (true) {
      check(!blocks.expire()) { "Peer block response deadline expired" }
      val expired = transport.expire()
      if (expired.isNotEmpty()) return Event.HashTimeout(expired)
      val blockDelay = blocks.nextDeadlineMs()
      val hashDelay = transport.nextDeadlineMs()
      val delay = when {
        blockDelay == null -> hashDelay
        hashDelay == null -> blockDelay
        else -> minOf(blockDelay, hashDelay)
      }
      // Channel cancellation owns any undelivered frame. Avoid a withTimeout return boundary
      // that could discard a successfully received frame without releasing its reservation.
      val event = select<Event<C>?> {
        // Alternate ready queues so neither peer traffic nor completion bursts starve the other.
        if (preferCommands && commands != null) commands.onReceive { Event.Command(it) }
        frames.onReceive { Event.Frame(it) }
        if (!preferCommands && commands != null) commands.onReceive { Event.Command(it) }
        if (delay != null) onTimeout(delay) { null }
      }
      if (event != null) {
        preferCommands = event !is Event.Command
        return event
      }
    }
  }

  companion object {
    /**
     * Owns transport and block exchange shutdown. The actor performs writes/dispatch serially
     * inside body and calls next while idle. It must offload long storage work to admitted jobs.
     * Connection admission can be released only after this scope returns and its reader is joined.
     */
    suspend fun <T> run(
      transport: PeerHashTransport,
      blocks: PeerBlockExchange,
      body: suspend (PeerV2Inbox) -> T,
    ): T = coroutineScope {
      val frames = Channel<PeerHashTransport.Frame>(1, onUndeliveredElement = { it.close() })
      val reader = launch {
        try {
          while (true) {
            val frame = transport.read()
            try { frames.send(frame) } catch (error: Throwable) {
              frame.close()
              throw error
            }
          }
        } catch (error: Throwable) {
          frames.close(error)
        } finally {
          frames.close()
        }
      }
      try {
        body(PeerV2Inbox(frames, transport, blocks))
      } finally {
        withContext(NonCancellable) {
          try { blocks.close() } finally {
            // Also close directly if the supplied block exchange was already closed.
            try { transport.close() } finally {
              reader.cancelAndJoin()
              frames.cancel()
            }
          }
        }
      }
    }
  }
}
