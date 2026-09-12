package com.linroid.ketch.torrent

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** One storage worker; queued assemblies already hold admission and transfer ownership on submit. */
internal class TorrentV2CommitWorker private constructor(
  private val input: Channel<TorrentV2PieceAssembly>,
  val completions: ReceiveChannel<Completion>,
) {
  sealed interface Completion {
    data class Committed(val index: Int, val verified: Boolean) : Completion
    data class Failed(val index: Int, val cause: Throwable) : Completion
  }

  /** Success transfers the complete assembly; failure leaves it owned by the actor for retry. */
  fun trySubmit(assembly: TorrentV2PieceAssembly): Boolean {
    check(assembly.complete) { "Only complete assemblies can be submitted" }
    return input.trySend(assembly).isSuccess
  }

  companion object {
    /** Exit cancels and joins the worker before returning, then reclaims every queued assembly. */
    suspend fun <T> run(
      store: TorrentV2PieceStore,
      capacity: Int = 1,
      dispatcher: CoroutineDispatcher = Dispatchers.Default,
      body: suspend (TorrentV2CommitWorker) -> T,
    ): T = coroutineScope {
      require(capacity in 1..16)
      val input = Channel<TorrentV2PieceAssembly>(capacity, onUndeliveredElement = { it.close() })
      val output = Channel<Completion>(capacity)
      val worker = launch(dispatcher) {
        try {
          for (assembly in input) {
            val result = try {
              Completion.Committed(assembly.index, assembly.commit(store))
            } catch (error: CancellationException) {
              throw error
            } catch (error: Throwable) {
              Completion.Failed(assembly.index, error)
            } finally {
              assembly.close()
            }
            output.send(result)
          }
        } finally {
          output.close()
        }
      }
      try {
        body(TorrentV2CommitWorker(input, output))
      } finally {
        withContext(NonCancellable) {
          try { worker.cancelAndJoin() } finally {
            input.cancel()
            output.cancel()
          }
        }
      }
    }
  }
}
