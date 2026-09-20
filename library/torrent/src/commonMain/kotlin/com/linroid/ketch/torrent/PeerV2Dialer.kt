package com.linroid.ketch.torrent

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Bounded handshakes; endpoint authorization, deduplication and retry policy are upstream. */
internal class PeerV2Dialer private constructor(
  val connections: ReceiveChannel<PeerV2Connector.Connected>,
  val lastFailure: StateFlow<Failure?>,
) {
  data class Failure(val endpoint: PeerEndpoint, val cause: Throwable)

  companion object {
    /**
     * Connect returns an owned handle, or null before opening a socket when admission is exhausted.
     * Ordinary connection failures are recorded and isolated; a failed endpoint stream is fatal.
     * Caller owns the endpoint producer. This scope joins dial workers and closes queued handles.
     */
    @OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class)
    suspend fun <T> run(
      endpoints: ReceiveChannel<PeerEndpoint>,
      state: TorrentBufferBudget,
      parallelism: Int = 4,
      capacity: Int = 1,
      connect: suspend (PeerEndpoint) -> PeerV2Connector.Connected?,
      body: suspend (PeerV2Dialer) -> T,
    ): T = coroutineScope {
      require(parallelism in 1..32 && capacity in 1..32)
      val lease = checkNotNull(state.reserve((parallelism + capacity) * 2048 + 1024)) {
        "Dial worker state budget exhausted"
      }
      val output = Channel<PeerV2Connector.Connected>(
        capacity = capacity,
        onUndeliveredElement = { it.close() },
      )
      val failure = MutableStateFlow<Failure?>(null)
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
                      failure.value = Failure(endpoint, error)
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
