package com.linroid.ketch.torrent

import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.selects.onTimeout
import kotlinx.coroutines.selects.select

/** Serializes session decisions while peer actors and the storage worker perform their own I/O. */
internal object TorrentV2SessionLoop {
  private class PeerState(val commands: SendChannel<PeerV2DownloadActor.Command>) {
    var choked = true
    var needed = 0
    var interested = false
    var interestPending = false
    var inFlight = 0
    var admissionBlocked = false
  }

  private sealed interface Event {
    data class Peer(val value: PeerV2Pool.Event) : Event
    data class Commit(val value: TorrentV2CommitWorker.Completion) : Event
    data object Retry : Event
  }

  /**
   * Downloads selected files using an initialized matching store and already attached peers.
   * Caller owns pool/worker scopes and joins them on return or failure. Initial verification comes
   * only from the store. This full-metainfo path does not resolve magnet metadata or serve hashes.
   */
  @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
  suspend fun download(
    layout: TorrentContentLayout,
    selectedIds: Set<String>,
    store: TorrentV2PieceStore,
    pool: PeerV2Pool,
    worker: TorrentV2CommitWorker,
    buffers: TorrentBufferBudget,
    state: TorrentBufferBudget,
    maxPeers: Int = 100,
    maxActive: Int = 2,
    pipeline: Int = 32,
  ) {
    require(maxPeers in 1..500 && pipeline in 1..256)
    val lease = checkNotNull(state.reserve(maxPeers * 512 + 1024)) {
      "Session peer state budget exhausted"
    }
    var scheduler: TorrentV2PieceScheduler? = null
    var picker: TorrentV2RarityPicker<PeerV2Pool.Peer>? = null
    try {
      val pieces = checkNotNull(TorrentV2PieceScheduler.create(layout, selectedIds,
        store.verifiedPieces(), buffers, state, maxActive)) { "Piece scheduler budget exhausted" }
      scheduler = pieces
      val rarity = checkNotNull(TorrentV2RarityPicker.create<PeerV2Pool.Peer>(
        layout.pieceCount.toInt(), state, maxPeers)) { "Rarity index budget exhausted" }
      picker = rarity
      val peers = linkedMapOf<PeerV2Pool.Peer, PeerState>()

      fun wakeAdmission() { peers.values.forEach { it.admissionBlocked = false } }

      fun pump() {
        pieces.submitReady(worker)
        for ((peer, view) in peers) {
          if (view.admissionBlocked) continue
          val interested = view.needed > 0
          if (interested != view.interested && !view.interestPending) {
            if (view.commands.trySend(PeerV2DownloadActor.Command.Interest(interested)).isSuccess) {
              view.interestPending = true
            } else view.admissionBlocked = true
          }
          if (!interested || !view.interested || view.interestPending || view.choked ||
            view.inFlight == pipeline || view.admissionBlocked) continue
          var plan = pieces.planNext(peer.blocks) { rarity.has(peer, it) }
          if (plan == null) {
            val index = rarity.pick(peer) { pieces.canBegin(it) }
            if (index != null) {
              if (pieces.begin(index)) plan = pieces.planNext(peer.blocks) { rarity.has(peer, it) }
              else view.admissionBlocked = true
            }
          }
          if (plan != null) {
            if (view.commands.trySend(PeerV2DownloadActor.Command.Request(plan)).isSuccess) {
              view.inFlight++
            } else {
              check(pieces.resolve(plan, null))
              view.admissionBlocked = true
            }
          }
        }
      }

      fun message(peer: PeerV2Pool.Peer, value: PeerV2DownloadActor.Event) {
        val view = peers[peer] ?: return
        when (value) {
          is PeerV2DownloadActor.Event.Update -> when (val update = value.frame.message) {
            is PeerMessage.Bitfield -> {
              if (!rarity.update(peer, update.bytes)) pool.stop(peer)
              else view.needed = pieces.neededCount { rarity.has(peer, it) }
            }
            is PeerMessage.Have -> {
              val newlyNeeded = !rarity.has(peer, update.index) && pieces.isNeeded(update.index)
              if (!rarity.have(peer, update.index)) pool.stop(peer)
              else if (newlyNeeded) view.needed++
            }
            is PeerMessage.Control -> when (update.signal) {
              PeerMessage.Signal.CHOKE -> view.choked = true
              PeerMessage.Signal.UNCHOKE -> view.choked = false
              else -> Unit
            }
            else -> Unit
          }
          is PeerV2DownloadActor.Event.Interest -> {
            view.interestPending = false
            if (value.sent) view.interested = value.interested else view.admissionBlocked = true
          }
          is PeerV2DownloadActor.Event.Requested -> {
            if (!pieces.resolve(value.plan, value.ticket)) pool.stop(peer)
            if (value.ticket == null) {
              check(view.inFlight > 0)
              view.inFlight--
              view.admissionBlocked = true
            }
          }
          is PeerV2DownloadActor.Event.Response -> {
            check(view.inFlight > 0)
            view.inFlight--
            pieces.receive(peer.blocks, value.value)
            wakeAdmission()
          }
          is PeerV2DownloadActor.Event.Hash -> {
            val hash = value.value
            if (hash is PeerHashTransport.Event.Request &&
              view.commands.trySend(PeerV2DownloadActor.Command.RejectHashes(
                hash.request.selector)).isFailure) pool.stop(peer)
          }
          else -> Unit
        }
      }

      var preferCommits = true
      while (!pieces.completed()) {
        pump()
        check(pool.size > 0 || pieces.pendingCommitCount > 0) {
          "All torrent peers disconnected before completion"
        }
        val event = select<Event> {
          if (preferCommits) worker.completions.onReceive { Event.Commit(it) }
          pool.events.onReceive { Event.Peer(it) }
          if (!preferCommits) worker.completions.onReceive { Event.Commit(it) }
          // Only admission pressure enables a retry timer; healthy idle peers do not poll.
          if (peers.values.any { it.admissionBlocked }) onTimeout(250) { Event.Retry }
        }
        preferCommits = event !is Event.Commit
        when (event) {
          is Event.Commit -> {
            check(pieces.completed(event.value)) { "Unknown commit completion" }
            if (event.value is TorrentV2CommitWorker.Completion.Failed) throw event.value.cause
            if (event.value is TorrentV2CommitWorker.Completion.Committed && event.value.verified) {
              for ((peer, view) in peers) if (rarity.has(peer, event.value.ticket.index)) {
                check(view.needed > 0)
                view.needed--
              }
            }
            wakeAdmission()
          }
          is Event.Retry -> wakeAdmission()
          is Event.Peer -> try {
            when (val peerEvent = event.value) {
              is PeerV2Pool.Event.Ready -> {
                check(peers.size < maxPeers && peerEvent.peer !in peers)
                peers[peerEvent.peer] = PeerState(peerEvent.commands)
              }
              is PeerV2Pool.Event.Message -> message(peerEvent.peer, peerEvent.value)
              is PeerV2Pool.Event.Closed -> {
                peers.remove(peerEvent.peer)
                rarity.remove(peerEvent.peer)
                pieces.detachPeer(peerEvent.peer.blocks)
                pieces.evictUnavailable { rarity.hasAny(it) }
                check(pool.retire(peerEvent))
                wakeAdmission()
              }
            }
          } finally { event.value.close() }
        }
      }
      check(store.completed()) { "Selected store completion does not match session state" }
    } finally {
      scheduler?.close()
      picker?.close()
      lease.close()
    }
  }
}
