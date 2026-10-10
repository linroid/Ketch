package com.linroid.ketch.torrent

/**
 * The swarm loop's answers to BEP 52 hash requests: it decides which requests may be served,
 * hands those to the serve worker, which builds the proofs, and passes the worker's answers to
 * the peers' actors. Owned and called by the loop only.
 *
 * A request is rejected unless uploading is not disabled, its root and coordinates fit the
 * authenticated document, the peer has fewer than [MAX_PENDING_HASH_REQUESTS] being answered,
 * and the worker takes it. Hashes below the piece layer come from a piece read back from
 * storage, so those are proved only to peers we unchoke and only for verified pieces, and the
 * worker rejects them while the upload limits cannot pay for that read. Peers wait for an answer
 * to every request, so answers the actor has no room for yet wait in the peer's view, in order,
 * and go out on later passes.
 */
internal class TorrentV2HashRequests(
  private val layout: TorrentContentLayout,
  private val swarm: TorrentV2Swarm,
) {
  /** Answers sent and requests rejected, for the swarm summary. */
  var served = 0L
    private set
  var rejected = 0L
    private set

  /** [peer] asked for the hashes [selector] names. */
  fun request(
    peer: PeerV2Pool.Peer,
    view: TorrentV2PeerView,
    selector: PeerHashSelector,
    verified: (Int) -> Boolean,
  ): TorrentV2Uploader.Verdict {
    // A v1 route carries no hash messages, not even rejects; its transport never yields these.
    if (view.info?.mode == PeerIdentityHandshake.Mode.V1) return TorrentV2Uploader.Verdict.KEEP
    val serve = swarm.serve
    val disabled = swarm.runtime.uploadPolicy() == TorrentUploadPolicy.DISABLED
    val bounds = if (serve == null || disabled || view.hashPending >= MAX_PENDING_HASH_REQUESTS) {
      null
    } else peerHashServeBounds(swarm.document, layout, selector)
    val admitted = bounds != null &&
      (!bounds.blockPath || !view.amChoking && verified(bounds.piece(selector).toInt())) &&
      checkNotNull(serve).trySubmit(TorrentV2ServeWorker.Job.Proof(peer, selector, bounds))
    if (!admitted) return reject(view, selector)
    view.hashPending++
    return TorrentV2Uploader.Verdict.KEEP
  }

  /** A proof the worker built goes to its peer; the result is closed when the peer has gone. */
  fun proved(
    result: TorrentV2ServeWorker.Result.Hashes,
    peers: Map<PeerV2Pool.Peer, TorrentV2PeerView>,
  ): TorrentV2Uploader.Verdict {
    val view = peers[result.peer]
    if (view == null) {
      result.close()
      return TorrentV2Uploader.Verdict.KEEP
    }
    return answer(view, result.command())
  }

  /** The worker could not prove a request: no readable piece, or no budget for the proof. */
  fun unproved(
    result: TorrentV2ServeWorker.Result.HashesRejected,
    peers: Map<PeerV2Pool.Peer, TorrentV2PeerView>,
  ): TorrentV2Uploader.Verdict {
    val view = peers[result.peer] ?: return TorrentV2Uploader.Verdict.KEEP
    answered(view)
    return reject(view, result.selector)
  }

  /** The actor wrote served hashes, or without frame credit dropped them. */
  fun served(
    view: TorrentV2PeerView,
    event: PeerV2DownloadActor.Event.HashServed,
  ): TorrentV2Uploader.Verdict {
    answered(view)
    if (!event.sent) return reject(view, event.selector)
    served++
    return TorrentV2Uploader.Verdict.KEEP
  }

  /** Queues the answers waiting in [view] with its actor, oldest first, while there is room. */
  fun flush(view: TorrentV2PeerView) {
    while (view.hashAnswers.isNotEmpty()) {
      if (!view.commands.trySend(view.hashAnswers.first()).isSuccess) return
      view.hashAnswers.removeFirst()
    }
  }

  /** Some peer has answers waiting for room in its actor's queue. */
  fun waiting(peers: Map<PeerV2Pool.Peer, TorrentV2PeerView>): Boolean =
    peers.values.any { it.hashAnswers.isNotEmpty() }

  private fun answered(view: TorrentV2PeerView) {
    check(view.hashPending > 0) { "Unknown hash answer" }
    view.hashPending--
  }

  private fun reject(view: TorrentV2PeerView, selector: PeerHashSelector):
    TorrentV2Uploader.Verdict {
    rejected++
    return answer(view, PeerV2DownloadActor.Command.RejectHashes(selector))
  }

  /** Sends [command] after those already waiting; a peer that lets too many pile up stops. */
  private fun answer(
    view: TorrentV2PeerView,
    command: PeerV2DownloadActor.Command,
  ): TorrentV2Uploader.Verdict {
    if (view.hashAnswers.size >= MAX_QUEUED_HASH_ANSWERS) {
      command.close()
      return TorrentV2Uploader.Verdict.STOP
    }
    view.hashAnswers.addLast(command)
    flush(view)
    return TorrentV2Uploader.Verdict.KEEP
  }
}
