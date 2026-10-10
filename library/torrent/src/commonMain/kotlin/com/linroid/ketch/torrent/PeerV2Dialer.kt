package com.linroid.ketch.torrent

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedSendChannelException
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Bounded handshakes for dialed targets [E] and, optionally, peers that dialed us. Target
 * authorization, deduplication and retry policy are upstream.
 */
internal class PeerV2Dialer<E : Any> private constructor(
  val connections: ReceiveChannel<PeerV2Connector.Connected>,
  val lastFailure: StateFlow<Failure<E>?>,
) {
  data class Failure<E>(val endpoint: E, val cause: Throwable)

  /** A connection a peer opened to us, with its handshake unread, admitted in [generation]. */
  class Incoming(val connection: TorrentConnection, val generation: Long) {
    fun close() = connection.close()
  }

  companion object {
    private const val RESPOND_HANDOFF_MS = 5_000L

    /** Hands [failure] on; once the consumer closed its queue, nobody counts it any more. */
    private suspend fun <E> SendChannel<Failure<E>>.report(failure: Failure<E>) {
      try {
        send(failure)
      } catch (_: ClosedSendChannelException) {
        // Closed by its consumer.
      } catch (error: CancellationException) {
        // A cancelled queue throws its cause; only the worker's own cancellation propagates.
        currentCoroutineContext().ensureActive()
      }
    }

    /**
     * Connect returns an owned handle, or null before opening a socket when admission is exhausted.
     * Ordinary connection failures are recorded, sent to [failures] when given (waiting for room,
     * so a consumer that counts its targets never loses one), and isolated; a failed target
     * stream is fatal. [respond] answers each [incoming] connection once, on
     * [respondParallelism] workers sharing the output; a null or failed answer closes it, and
     * so does an answer the consumer does not take within [handoffMs].
     * Caller owns both producers. This scope joins every worker and closes queued handles.
     */
    @OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class)
    suspend fun <E : Any, T> run(
      endpoints: ReceiveChannel<E>,
      state: TorrentBufferBudget,
      parallelism: Int = 4,
      capacity: Int = 1,
      connect: suspend (E) -> PeerV2Connector.Connected?,
      incoming: ReceiveChannel<Incoming>? = null,
      respondParallelism: Int = 4,
      respond: (suspend (Incoming) -> PeerV2Connector.Connected?)? = null,
      failures: SendChannel<Failure<E>>? = null,
      handoffMs: Long = RESPOND_HANDOFF_MS,
      body: suspend (PeerV2Dialer<E>) -> T,
    ): T = coroutineScope {
      require(parallelism in 1..32 && capacity in 1..32 && respondParallelism in 1..32)
      require((incoming == null) == (respond == null)) { "Incoming peers need a responder" }
      val responders = if (incoming != null) respondParallelism else 0
      val lease = checkNotNull(state.reserve((parallelism + responders + capacity) * 2048 + 1024)) {
        "Dial worker state budget exhausted"
      }
      val output = Channel<PeerV2Connector.Connected>(
        capacity = capacity,
        onUndeliveredElement = { it.close() },
      )
      val failure = MutableStateFlow<Failure<E>?>(null)
      val workers = launch {
        try {
          coroutineScope {
            repeat(parallelism) {
              launch {
                for (endpoint in endpoints) {
                  var connected: PeerV2Connector.Connected? = null
                  while (connected == null) {
                    try {
                      connected = connect(endpoint)
                    } catch (error: Exception) {
                      if (error is CancellationException && !currentCoroutineContext().isActive) {
                        throw error
                      }
                      Failure(endpoint, error).let {
                        failure.value = it
                        failures?.report(it)
                      }
                      break
                    }
                    if (connected == null) {
                      delay(250)
                      // A drained stream can fail while every worker is retrying admission.
                      // Normal closure still allows its already consumed endpoints to connect.
                      if (endpoints.isClosedForReceive) {
                        endpoints.receiveCatching().exceptionOrNull()?.let { throw it }
                      }
                    }
                  }
                  if (connected != null) output.send(connected)
                }
              }
            }
            if (incoming != null && respond != null) repeat(respondParallelism) {
              launch {
                for (peer in incoming) {
                  val connected = try {
                    respond(peer)
                  } catch (error: Exception) {
                    if (error is CancellationException && !currentCoroutineContext().isActive) {
                      peer.close()
                      throw error
                    }
                    null
                  }
                  // Responders never retry: the peer that dialed us can dial again. An answered
                  // peer the consumer does not take in time is closed: it holds a connection
                  // slot and saw our handshake, so it must not wait in silence.
                  if (connected == null) peer.close()
                  else if (withTimeoutOrNull(handoffMs) { output.send(connected) } == null) {
                    connected.close()
                  }
                }
              }
            }
          }
        } catch (error: Throwable) {
          output.close(error)
        } finally { output.close() }
      }
      try { body(PeerV2Dialer(output, failure)) } finally {
        withContext(NonCancellable) {
          try { workers.cancelAndJoin() } finally {
            try { output.cancel() } finally { lease.close() }
          }
        }
      }
    }
  }
}
