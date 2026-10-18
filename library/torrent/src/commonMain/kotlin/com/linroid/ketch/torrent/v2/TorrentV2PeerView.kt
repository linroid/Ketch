package com.linroid.ketch.torrent.v2

import com.linroid.ketch.torrent.engine.TorrentBufferBudget
import com.linroid.ketch.torrent.metainfo.MAX_PIECE_LENGTH
import com.linroid.ketch.torrent.monotonicClock
import com.linroid.ketch.torrent.peer.PeerExchange
import com.linroid.ketch.torrent.peer.PeerExtensions
import com.linroid.ketch.torrent.peer.PeerMessage
import com.linroid.ketch.torrent.peer.PeerProtocolState
import com.linroid.ketch.torrent.peer.PeerServeCredit
import com.linroid.ketch.torrent.peer.PeerTraffic
import com.linroid.ketch.torrent.peer.PexIntroductions
import com.linroid.ketch.torrent.v2.peer.PeerInfo
import com.linroid.ketch.torrent.v2.peer.PeerV2DownloadActor
import kotlinx.coroutines.channels.SendChannel

/** Session-state charge for one ready peer's loop view on a swarm session. */
internal const val PEER_VIEW_BYTES = 16 * 1024

/** Requests a peer may queue with us; more are ignored and count against it. */
internal const val MAX_QUEUED_UPLOADS = 64

/** Rejects waiting to go to a Fast peer (BEP 6); more are dropped and the peer gives up itself. */
internal const val MAX_QUEUED_REJECTS = 256

/** Uploaded blocks a peer may have in its actor's queue at once. */
internal const val MAX_UPLOADS_IN_FLIGHT = 4

/** Hash requests of one peer being answered at once; more are rejected. */
internal const val MAX_PENDING_HASH_REQUESTS = 16

/** Hash answers waiting for room in one peer's actor queue; a peer that needs more is stopped. */
internal const val MAX_QUEUED_HASH_ANSWERS = 64

/** `ut_metadata` answers that may wait for room in one peer's actor queue; more go unanswered. */
internal const val MAX_QUEUED_METADATA_ANSWERS = 8

/** The least time a peer's download rate is measured over. */
private const val RATE_WINDOW_MS = 2_000L

/**
 * What the session loop knows about one ready peer. Only the loop reads or writes it. A swarm
 * session charges [PEER_VIEW_BYTES] per view; [close] returns it.
 */
internal class TorrentV2PeerView(
  val commands: SendChannel<PeerV2DownloadActor.Command>,
  /** Null only for peers attached without a negotiated identity (pipeline tests). */
  val info: PeerInfo?,
  private val lease: TorrentBufferBudget.Lease? = null,
  private val nowMs: () -> Long = monotonicClock(),
  /** This peer among the task's live connections; closed with the view. */
  val traffic: PeerTraffic? = null,
  /** The swarm's piece length, which sizes [serveCredit]; the largest a piece may be. */
  pieceLength: Long = MAX_PIECE_LENGTH,
) {
  /** Pieces read back and hashes folded for this peer, beyond what it takes of them. */
  val serveCredit = PeerServeCredit(PeerServeCredit.capacityFor(pieceLength), nowMs)

  // Download
  var choked = true
  var needed = 0
  var interested = false
  var interestPending = false
  var inFlight = 0
  var admissionBlocked = false
  var rateRetryAt = 0L

  /** Payload bytes this peer delivered since the last rechoke; ranks peers to drop first. */
  var receivedSinceRechoke = 0L

  /** No sooner than this does the peer look again for blocks others are late with. */
  var duplicateScanAt = 0L

  // Payload delivered since [rateWindowStart], for [rate].
  private var rateWindowStart = nowMs()
  private var rateWindowBytes = 0L

  /**
   * Payload bytes a second the peer delivered over its last window of at least
   * [RATE_WINDOW_MS]; zero until one passed. How late its requests may be follows from it.
   */
  var rate = 0L
    private set

  /** The peer delivered [bytes] of payload. */
  fun delivered(bytes: Int) {
    val now = nowMs()
    rateWindowBytes += bytes
    val elapsed = now - rateWindowStart
    if (elapsed >= RATE_WINDOW_MS) {
      rate = rateWindowBytes * 1000 / elapsed
      rateWindowStart = now
      rateWindowBytes = 0
    }
  }

  // Upload
  /** The peer wants payload from us. */
  var remoteInterested = false

  /** What we last told the peer, or are telling it: true until an UNCHOKE goes out. */
  var amChoking = true

  /** A CHOKE or UNCHOKE is queued; the next waits for its outcome. */
  var chokeInFlight = false

  /** Requests to serve, oldest first, at most [MAX_QUEUED_UPLOADS]. */
  val uploads = ArrayDeque<PeerMessage.Request>()

  /** Blocks queued with the actor, at most [MAX_UPLOADS_IN_FLIGHT]. */
  var uploadsInFlight = 0

  /** Uploading waits until then: rate limits, or no budget or queue room. */
  var uploadRetryAt = 0L

  /** Requests we could not serve in a row for want of budget. */
  var refusals = 0

  /** Requests that were never valid to ask for; too many stop the peer. */
  var strikes = 0

  // Fast extension (BEP 6), only for peers that negotiated it
  /** Both sides set the Fast extension bit. */
  val fast: Boolean get() = info?.fast == true

  /**
   * Pieces the peer lets us request while it chokes us, the first
   * [PeerProtocolState.MAX_ALLOWED_FAST] it named, as its block exchange counts them.
   */
  val allowedFast = mutableSetOf<Int>()

  /** Rejects per piece of blocks we asked for, since the peer last unchoked us. */
  val rejects = mutableMapOf<Int, Int>()

  /**
   * Pieces the peer rejected as many times as they have blocks, which other peers fetch until
   * it unchokes us anew.
   */
  val refused = mutableSetOf<Int>()

  /**
   * Requests the peer rejected while it served us since the last block it delivered; an unchoke
   * leaves the count. Once it reaches a piece's worth, its requests' speed limit waits are no
   * progress any more.
   */
  var rejectsSinceBlock = 0

  /** Pieces we let the peer download while we choke it, offered only while we upload. */
  var offeredFast: Set<Int> = emptySet()

  /** Requests we will not serve, to reject, oldest first, at most [MAX_QUEUED_REJECTS]. */
  val rejectQueue = ArrayDeque<PeerMessage.Request>()

  // Availability
  /** How many entries of the session's Have log this peer was told. */
  var haveCursor = 0

  /** Pieces the peer announced; a peer with all of them is a seed. */
  var remoteHaveCount = 0

  // Extensions (BEP 10), only for peers that negotiated them
  /** What the peer told us in its extension handshakes: its message IDs, `p` and `reqq`. */
  val extensions = PeerExtensions()

  /** Peer exchange with this peer, both directions. */
  val exchange = PeerExchange(nowMs)

  /** Hosts this peer introduced by peer exchange, bounded over its connection. */
  val pexIntroductions = PexIntroductions()

  /** Metadata blocks served in the window that began at [metadataWindowStart]. */
  var metadataServed = 0
  var metadataWindowStart = nowMs()

  /**
   * Answers to the peer's `ut_metadata` requests that its actor's queue had no room for yet,
   * oldest first, at most [MAX_QUEUED_METADATA_ANSWERS]; each is built when it goes out.
   */
  val metadataAnswers = ArrayDeque<MetadataAnswer>()

  /** After an answer was dropped unsent, the waiting answers go out no sooner than this. */
  var metadataRetryAt = 0L

  /** Hash requests accepted and not yet answered, at most [MAX_PENDING_HASH_REQUESTS]. */
  var hashPending = 0

  /** The piece this peer last paid [serveCredit] to have read back, so it pays once for it. */
  var paidRead = -1

  /**
   * Hashes and rejects for this peer that its actor's queue had no room for yet, oldest first;
   * each holds what it sends until [close].
   */
  val hashAnswers = ArrayDeque<PeerV2DownloadActor.Command>()

  /** Block [piece] of the info dictionary, or a reject when [serve] is false. */
  class MetadataAnswer(val piece: Int, val serve: Boolean)

  /** Reports the choke and interest state, which only changes as the loop handles events. */
  fun reportState() {
    traffic?.state(peerChoking = choked, uploadSlot = !amChoking,
      peerInterested = remoteInterested)
  }

  fun close() {
    try {
      traffic?.close()
      while (hashAnswers.isNotEmpty()) hashAnswers.removeFirst().close()
    } finally { lease?.close() }
  }
}
