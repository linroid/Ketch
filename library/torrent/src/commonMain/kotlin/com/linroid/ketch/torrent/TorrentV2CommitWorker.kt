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

/** One storage worker; admitted assemblies transfer ownership on successful submission. */
internal class TorrentV2CommitWorker private constructor(
  private val input: Channel<Submission>,
  val completions: ReceiveChannel<Completion>,
) {
  /** Opaque identity for one submission; the actor associates it with its session generation. */
  class Ticket internal constructor(val index: Int)
  private class Submission(val ticket: Ticket, val claim: TorrentV2PieceAssembly.Claim)

  sealed interface Completion {
    val ticket: Ticket
    data class Committed(override val ticket: Ticket, val verified: Boolean) : Completion
    data class Failed(override val ticket: Ticket, val cause: Throwable) : Completion

    /** The selection no longer wants the piece: nothing was written, and nobody did wrong. */
    data class Discarded(override val ticket: Ticket) : Completion
  }

  /** Non-null transfers ownership; saturation restores actor ownership without creating work. */
  fun trySubmit(assembly: TorrentV2PieceAssembly): Ticket? {
    val claim = assembly.transfer()
    try {
      val ticket = Ticket(claim.index)
      if (input.trySend(Submission(ticket, claim)).isSuccess) return ticket
      claim.restore()
      return null
    } catch (error: Throwable) {
      claim.restore()
      throw error
    }
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
      val input = Channel<Submission>(capacity, onUndeliveredElement = { it.claim.close() })
      val output = Channel<Completion>(capacity)
      val worker = launch(dispatcher) {
        // Closing with the cause the worker stopped with lets the loop tell a stop it must
        // report from a cancellation it may see before its own job is marked cancelled.
        var stopped: Throwable? = null
        try {
          for (submission in input) {
            val result = try {
              when (submission.claim.commit(store)) {
                CommitOutcome.VERIFIED -> Completion.Committed(submission.ticket, verified = true)
                CommitOutcome.CORRUPT -> Completion.Committed(submission.ticket, verified = false)
                CommitOutcome.NOT_WANTED -> Completion.Discarded(submission.ticket)
              }
            } catch (error: CancellationException) {
              throw error
            } catch (error: Throwable) {
              Completion.Failed(submission.ticket, error)
            } finally {
              submission.claim.close()
            }
            output.send(result)
          }
        } catch (error: Throwable) {
          stopped = error
          throw error
        } finally {
          output.close(stopped)
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
