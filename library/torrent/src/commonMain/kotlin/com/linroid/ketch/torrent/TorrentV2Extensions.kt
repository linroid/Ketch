package com.linroid.ketch.torrent

/**
 * The swarm loop's side of BEP 10 on v2 and hybrid connections, v1 routes included: our extension
 * handshake, the info dictionary for `ut_metadata` (BEP 9) and peer exchange both ways (BEP 11).
 * Owned and called by the loop only, for peers that negotiated extensions.
 *
 * The info dictionary is offered and served only while [servesMetadata] allows it, read again for
 * every answer, so switching uploads off withholds it at once. Answers wait in the peer's view
 * while its actor's queue is full: our own backlog never costs a peer its connection. Restricted
 * torrents (private metainfo or tracker-only privacy) never offer peer exchange, and a peer that
 * sends it anyway is banned (BEP 27). Peer exchange shares only listen endpoints, each with the
 * flags [pexFlags] gives it, and only public addresses unless the runtime allows local peers; a
 * message counts as sent only once its actor took it and wrote it.
 */
internal class TorrentV2Extensions(
  private val swarm: TorrentV2Swarm,
  private val candidates: TorrentV2Candidates,
  private val nowMs: () -> Long,
) {
  private val document = swarm.document
  private val runtime = swarm.runtime
  private val privateTorrent = document.info.privateTorrent
  private val pex = !swarm.restricted
  // Peer exchange is looked at again from then on; zero means at the next pass.
  private var pexCheckAt = 0L

  /** Our extension handshake, queued before anything else; false when it could not be. */
  fun ready(view: TorrentV2PeerView): Boolean {
    val info = view.info ?: return true
    if (!info.extensions) return true
    val handshake = PeerExtensions.handshake(
      utMetadata = servesMetadata(privateTorrent, runtime.uploadPolicy()),
      metadataSize = document.info.rawInfo.size,
      pex = pex,
      listenPort = runtime.listenPortFor(info.link.remote),
      requestQueue = MAX_QUEUED_UPLOADS,
    )
    return view.commands.trySend(PeerV2DownloadActor.Command.Send(handshake)).isSuccess
  }

  /** One extension message from [view]'s peer; malformed or forbidden ones ban it. */
  fun receive(view: TorrentV2PeerView, message: PeerMessage.Extended): TorrentV2Uploader.Verdict {
    val info = view.info
    // Extension messages need both sides to have set the BEP 10 bit.
    if (info == null || !info.extensions) return TorrentV2Uploader.Verdict.BAN
    return try {
      when (message.id) {
        0 -> {
          view.extensions.receive(message.payload, MAX_METADATA_BYTES)
          // A peer that takes peer exchange hears of the others without waiting for a check.
          if (pex && view.extensions.id(UT_PEX) != 0) pexCheckAt = 0
          TorrentV2Uploader.Verdict.KEEP
        }
        PeerExtensions.METADATA -> metadata(view, message.payload)
        PeerExtensions.PEX -> exchange(view, message.payload)
        // Extensions we never offered are ignored, as BEP 10 asks.
        else -> TorrentV2Uploader.Verdict.KEEP
      }
    } catch (_: IllegalArgumentException) {
      TorrentV2Uploader.Verdict.BAN
    }
  }

  private fun metadata(view: TorrentV2PeerView, payload: ByteArray): TorrentV2Uploader.Verdict {
    val header = Bencode.parsePrefix(payload, PeerWire.MAX_FRAME_SIZE)
    // We never ask for the info dictionary, so only requests (type 0) need an answer.
    if (header["msg_type"]?.integer != 0L) return TorrentV2Uploader.Verdict.KEEP
    val piece = requireNotNull(header["piece"]?.integer)
    require(piece in 0..Int.MAX_VALUE.toLong())
    if (view.extensions.id(UT_METADATA) == 0) return TorrentV2Uploader.Verdict.KEEP
    // Clients keep two to four requests out; one that piles up more goes unanswered.
    if (view.metadataAnswers.size >= MAX_QUEUED_METADATA_ANSWERS) {
      return TorrentV2Uploader.Verdict.KEEP
    }
    val now = nowMs()
    if (now - view.metadataWindowStart >= METADATA_WINDOW_MS) {
      view.metadataServed = 0
      view.metadataWindowStart = now
    }
    // Three times the dictionary a minute, as v1 serves it; then rejects until the window ends.
    val limit = (document.info.rawInfo.size / TorrentMetadataExchange.BLOCK_SIZE + 1) * 3
    val serve = view.metadataServed < limit
    if (serve) view.metadataServed++
    view.metadataAnswers.addLast(TorrentV2PeerView.MetadataAnswer(piece.toInt(), serve))
    flush(view)
    return TorrentV2Uploader.Verdict.KEEP
  }

  /**
   * Queues [view]'s waiting metadata answers with its actor, oldest first, while there is room.
   * Each is built now, so one the policy no longer allows goes out as a reject. After one was
   * dropped unsent, they wait until its retry is due.
   */
  fun flush(view: TorrentV2PeerView) {
    if (nowMs() < view.metadataRetryAt) return
    val remoteId = view.extensions.id(UT_METADATA)
    while (view.metadataAnswers.isNotEmpty()) {
      val answer = view.metadataAnswers.first()
      val serve = answer.serve && servesMetadata(privateTorrent, runtime.uploadPolicy())
      val response = if (serve) {
        TorrentMetadataExchange.response(remoteId, answer.piece, document.info.rawInfo)
      } else TorrentMetadataExchange.metadataMessage(remoteId, 2, answer.piece)
      if (!view.commands.trySend(PeerV2DownloadActor.Command.Send(response)).isSuccess) return
      view.metadataAnswers.removeFirst()
    }
  }

  /**
   * What became of one of our extension messages to [view]'s peer. The actor dropped it unsent
   * for want of frame credit: peer exchange tells the peer again, and a metadata answer waits to
   * go out again, each after [RETRY_MS] so a short budget is not met with a busy loop.
   */
  fun sent(view: TorrentV2PeerView, event: PeerV2DownloadActor.Event.Sent) {
    val message = event.message as? PeerMessage.Extended ?: return
    if (event.sent) return
    val retryAt = nowMs() + RETRY_MS
    if (view.exchange.unsent(message)) {
      pexCheckAt = minOf(pexCheckAt, retryAt)
      return
    }
    val remoteId = view.extensions.id(UT_METADATA)
    if (remoteId == 0 || message.id != remoteId) return
    if (view.metadataAnswers.size >= MAX_QUEUED_METADATA_ANSWERS) return
    val header = try {
      Bencode.parsePrefix(message.payload, PeerWire.MAX_FRAME_SIZE)
    } catch (_: IllegalArgumentException) {
      return
    }
    val piece = header["piece"]?.integer?.takeIf { it in 0..Int.MAX_VALUE.toLong() } ?: return
    view.metadataAnswers.addFirst(
      TorrentV2PeerView.MetadataAnswer(piece.toInt(), header["msg_type"]?.integer == 1L))
    view.metadataRetryAt = retryAt
  }

  private fun exchange(view: TorrentV2PeerView, payload: ByteArray): TorrentV2Uploader.Verdict {
    // We never offered it: peer exchange would leak the swarm of a restricted torrent.
    if (!pex) return TorrentV2Uploader.Verdict.BAN
    val update = view.exchange.receive(payload)
    val topic = if (view.info?.mode == PeerIdentityHandshake.Mode.V1) PeerTopic.V1 else PeerTopic.V2
    // One endpoint per host from each peer, and a bounded number of them over its connection,
    // so no peer can point us at many ports of one host or flood the candidates. Peers it drops
    // stay counted: the candidates keep them, so dropping must not make room.
    for (endpoint in update.added) {
      if (!shareable(endpoint) || !view.pexIntroductions.admit(endpoint)) continue
      candidates.offer(endpoint, topic, PeerOrigin.PEX, update.flags[endpoint] ?: 0)
    }
    return TorrentV2Uploader.Verdict.KEEP
  }

  /**
   * Sends the peer exchange messages that are due: each peer hears of the other peers' listen
   * endpoints, never of a source port. [listenOf] is where a peer listens, when known.
   */
  fun pump(
    peers: Map<PeerV2Pool.Peer, TorrentV2PeerView>,
    listenOf: (PeerV2Pool.Peer) -> PeerEndpoint?,
  ) {
    if (!pex) return
    val now = nowMs()
    if (now < pexCheckAt) return
    pexCheckAt = now + PEX_CHECK_MS
    val targets = peers.filter { (_, view) ->
      view.info?.extensions == true && view.extensions.id(UT_PEX) != 0 && view.exchange.due()
    }
    if (targets.isEmpty()) return
    // The loop owns every view, so the flags are decided when they are sent.
    val contacts = peers.mapNotNull { (peer, view) ->
      val info = view.info ?: return@mapNotNull null
      val listen = listenOf(peer)?.takeIf(::shareable) ?: return@mapNotNull null
      Contact(peer, listen,
        pexFlags(info.origin is PeerV2Origin.Outgoing, info.link, view.extensions))
    }
    for ((peer, view) in targets) {
      val own = listenOf(peer)
      val others = contacts.filter { it.peer !== peer && it.listen != own }
        .associate { it.listen to it.flags }
      val outgoing = view.exchange.message(view.extensions.id(UT_PEX), others) ?: continue
      // A full queue sends nothing and commits nothing: the next check builds it again.
      if (view.commands.trySend(PeerV2DownloadActor.Command.Send(outgoing.message)).isSuccess) {
        view.exchange.commit(outgoing)
      }
    }
  }

  /**
   * Milliseconds until peer exchange is looked at again or waiting metadata answers are tried
   * again, or null while neither waits. An answer the actor dropped unsent is due when its retry
   * is; answers waiting for room in a full queue are tried every [RETRY_MS], as a queue that
   * drains tells the loop nothing.
   */
  fun nextDueMs(peers: Map<PeerV2Pool.Peer, TorrentV2PeerView>): Long? {
    val now = nowMs()
    val answers = peers.values.filter { it.metadataAnswers.isNotEmpty() }
      .minOfOrNull { if (it.metadataRetryAt > now) it.metadataRetryAt - now else RETRY_MS }
    val exchange = if (!pex || peers.values.none { it.extensions.id(UT_PEX) != 0 }) null
      else (pexCheckAt - now).coerceAtLeast(0)
    return listOfNotNull(answers, exchange).minOrNull()
  }

  private fun shareable(endpoint: PeerEndpoint): Boolean = runtime.allowLocalPeers ||
    numericAddress(endpoint.host)?.let(::publicTorrentAddress) == true

  private class Contact(val peer: PeerV2Pool.Peer, val listen: PeerEndpoint, val flags: Int)

  private companion object {
    const val UT_METADATA = "ut_metadata"
    const val UT_PEX = "ut_pex"
    const val MAX_METADATA_BYTES = 4 * 1024 * 1024
    const val METADATA_WINDOW_MS = 60_000L
    /** How often due peer exchange is looked for; each peer still hears at most once a minute. */
    const val PEX_CHECK_MS = 5_000L
    /**
     * How long a message the actor dropped for want of frame credit waits to go out again, and
     * how often answers waiting for room in a full queue are tried.
     */
    const val RETRY_MS = 250L
  }
}
