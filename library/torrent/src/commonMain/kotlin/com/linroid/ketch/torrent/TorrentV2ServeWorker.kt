package com.linroid.ketch.torrent

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Reads verified pieces for uploading and answers hash requests off the session loop, one job at
 * a time, so a slow disk or a large proof never stalls peer traffic. The loop submits jobs and
 * owns every result it receives; results it never receives are closed with the worker.
 */
internal class TorrentV2ServeWorker private constructor(
  private val input: SendChannel<Job>,
  val results: ReceiveChannel<Result>,
) {
  sealed interface Job {
    /** Reads committed piece [index] again from disk, authenticated. */
    data class ReadPiece(val index: Int) : Job

    /** Answers [peer]'s hash request [selector], whose [bounds] the loop already checked. */
    class Proof(
      val peer: PeerV2Pool.Peer,
      val selector: PeerHashSelector,
      val bounds: HashServeBounds,
    ) : Job
  }

  sealed interface Result {
    /** Releases what the result holds; the loop calls it unless it keeps or forwards it. */
    fun close() = Unit

    /** What reading piece [index] found; a [TorrentV2PieceStore.ReadOutcome.Read] holds bytes. */
    class Piece(val index: Int, val outcome: TorrentV2PieceStore.ReadOutcome) : Result {
      override fun close() {
        (outcome as? TorrentV2PieceStore.ReadOutcome.Read)?.buffer?.close()
      }
    }

    /** The hashes answering one of [peer]'s requests; a lease of the uploads covers them. */
    class Hashes(
      val peer: PeerV2Pool.Peer,
      val message: PeerHashMessage.Hashes,
      lease: TorrentBufferBudget.Lease,
    ) : Result {
      private var lease: TorrentBufferBudget.Lease? = lease

      /** Hands the hashes and their lease to the command that sends them; once only. */
      fun command(): PeerV2DownloadActor.Command.ServeHashes {
        val owned = checkNotNull(lease) { "Hashes already handed on" }
        lease = null
        return PeerV2DownloadActor.Command.ServeHashes(message, owned)
      }

      override fun close() {
        lease?.close()
        lease = null
      }
    }

    /** [peer]'s request [selector] cannot be answered now, so it is rejected. */
    data class HashesRejected(val peer: PeerV2Pool.Peer, val selector: PeerHashSelector) : Result
  }

  /** False when the queue is full; the loop tries again later or rejects. */
  fun trySubmit(job: Job): Boolean = input.trySend(job).isSuccess

  companion object {
    /**
     * Exit cancels and joins the worker, closes [server] and every result the loop did not
     * receive. Without a [server] every proof job is rejected. Everything peers ask of us is
     * charged to [uploads], a session's upload partition: pieces are read into it, and proofs
     * are copied into its leases until their actor writes them, rejected while it is full.
     * Without it, pieces are read into the store's own budget; a [server] needs it.
     */
    suspend fun <T> run(
      store: TorrentV2PieceStore,
      server: TorrentV2HashServer? = null,
      uploads: TorrentBufferBudget? = null,
      capacity: Int = 8,
      dispatcher: CoroutineDispatcher = Dispatchers.Default,
      body: suspend (TorrentV2ServeWorker) -> T,
    ): T = coroutineScope {
      require(capacity in 1..64)
      require(server == null || uploads != null) { "Proofs need a budget" }
      val input = Channel<Job>(capacity)
      val output = Channel<Result>(capacity, onUndeliveredElement = { it.close() })
      suspend fun deliver(result: Result) {
        // The channel closes a result it could not deliver; closing twice is harmless.
        try { output.send(result) } catch (error: Throwable) {
          result.close()
          throw error
        }
      }
      suspend fun read(index: Int) =
        if (uploads == null) store.tryRead(index) else store.tryRead(index, uploads)
      val worker = launch(dispatcher) {
        try {
          for (job in input) {
            when (job) {
              is Job.ReadPiece -> deliver(Result.Piece(job.index, read(job.index)))
              is Job.Proof -> {
                // A verified piece found changed on disk fails the session like an upload read.
                var revoked: Result.Piece? = null
                val hashes = server?.respond(job.selector, job.bounds) { index ->
                  read(index).also {
                    if (it is TorrentV2PieceStore.ReadOutcome.Revoked) {
                      revoked = Result.Piece(index, it)
                    }
                  }
                }
                revoked?.let { deliver(it) }
                val lease = hashes?.let { checkNotNull(uploads).reserve(it.size + 256) }
                deliver(if (hashes != null && lease != null) {
                  Result.Hashes(job.peer, PeerHashMessage.Hashes(job.selector, hashes), lease)
                } else Result.HashesRejected(job.peer, job.selector))
              }
            }
          }
        } finally {
          output.close()
        }
      }
      try {
        body(TorrentV2ServeWorker(input, output))
      } finally {
        withContext(NonCancellable) {
          try { worker.cancelAndJoin() } finally {
            // The worker has stopped, so nothing else touches the server's caches.
            try { server?.close() } finally {
              input.cancel()
              output.cancel()
            }
          }
        }
      }
    }
  }
}
