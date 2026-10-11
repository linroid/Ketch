package com.linroid.ketch.torrent

/**
 * Per-connection availability and request ownership, independent of the swarm scheduler.
 *
 * With the Fast extension ([fast], BEP 6) a CHOKE keeps our requests: the peer rejects those it
 * will not serve, and may serve its allowed-fast pieces ([allowsFast]) while it chokes us. A
 * request we cancel is forgotten at once either way, so a late block or reject for it is ignored.
 */
internal class PeerProtocolState(
  private val pieceCount: Int,
  private val maxPending: Int = 32,
  private val explicitRejects: Boolean = false,
  /** Both sides set the Fast extension bit; set before the first message is read. */
  val fast: Boolean = false,
) {
  init {
    require(pieceCount in 0..1_000_000 && maxPending in 1..256)
    require(!(fast && explicitRejects)) { "Fast mode forgets canceled requests" }
  }

  private val available = ByteArray((pieceCount + 7) / 8)
  var choking: Boolean = true
    private set
  var interested: Boolean = false
    private set
  private var availabilitySeen = false
  private val pending = mutableSetOf<PeerMessage.Request>()
  private val canceled = linkedSetOf<PeerMessage.Request>()
  val requests: Set<PeerMessage.Request> get() = pending.toSet()
  /** Pieces the peer serves while it chokes us, at most [MAX_ALLOWED_FAST] of them. */
  private val allowedFast = linkedSetOf<Int>()

  /** The peer lets us request [index] while it chokes us (BEP 6 Allowed Fast). */
  fun allowsFast(index: Int): Boolean = index in allowedFast

  /** The peer named any allowed-fast piece. */
  val allowsAnyFast: Boolean get() = allowedFast.isNotEmpty()

  /** Whether the peer would accept a request for [index] now: unchoked, or allowed fast. */
  fun canRequest(index: Int): Boolean = hasPiece(index) && (!choking || allowsFast(index))

  fun hasPiece(index: Int): Boolean = index in 0 until pieceCount &&
    available[index / 8].toInt() and (128 ushr (index % 8)) != 0

  /** Legacy scheduler snapshots are transient; v2 scheduling reads packed bits directly. */
  fun availabilitySnapshot(): BooleanArray = BooleanArray(pieceCount, ::hasPiece)

  fun requested(request: PeerMessage.Request) {
    check(!choking || allowsFast(request.index)) { "Peer is choking" }
    require(hasPiece(request.index)) { "Peer lacks piece" }
    require(pending.size < maxPending && request !in pending) {
      "Peer pipeline is full or duplicated"
    }
    pending.add(request)
  }

  fun cancel(request: PeerMessage.Request) {
    if (explicitRejects) {
      if (request in pending) canceled.add(request)
    } else if (pending.remove(request)) {
      remember(request)
    }
  }

  private fun remember(request: PeerMessage.Request) {
    canceled.remove(request)
    canceled.add(request)
    while (canceled.size > maxPending * 2) canceled.remove(canceled.first())
  }

  /** V2 cancellation still requires a response; unsolicited v2 pieces always fail. */
  fun received(message: PeerMessage): Boolean {
    when (message) {
      is PeerMessage.Control -> when (message.signal) {
        PeerMessage.Signal.CHOKE -> {
          choking = true
          // A Fast peer rejects what it will not serve; the requests stay until it does.
          if (!explicitRejects && !fast) pending.toList().forEach(::cancel)
        }
        PeerMessage.Signal.UNCHOKE -> choking = false
        PeerMessage.Signal.INTERESTED -> interested = true
        PeerMessage.Signal.NOT_INTERESTED -> interested = false
      }
      is PeerMessage.Have -> {
        require(message.index in 0 until pieceCount)
        availabilitySeen = true
        val byte = message.index / 8
        available[byte] = (available[byte].toInt() or (128 ushr (message.index % 8))).toByte()
      }
      is PeerMessage.Bitfield -> {
        require(!availabilitySeen) { "Repeated or late bitfield" }
        PeerFrameLimits(pieceCount).validateBitfield(message.bytes)
        availabilitySeen = true
        message.bytes.copyInto(available)
      }
      is PeerMessage.Piece -> {
        val request = PeerMessage.Request(message.index, message.begin, message.bytes.size)
        if (pending.remove(request)) {
          if (explicitRejects) return !canceled.remove(request)
          remember(request)
          return true
        }
        if (!explicitRejects && request in canceled) return false
        throw IllegalArgumentException("Unsolicited or mismatched peer block")
      }
      is PeerMessage.Reject -> {
        val request = PeerMessage.Request(message.index, message.begin, message.length)
        if (explicitRejects) {
          require(pending.remove(request)) { "Unsolicited or mismatched reject" }
          canceled.remove(request)
          return false
        }
        if (fast) {
          // True frees a live request; a reject for one we canceled needs nothing.
          if (pending.remove(request)) return true
          require(request in canceled) { "Unsolicited or mismatched reject" }
          return false
        }
      }
      PeerMessage.HaveAll, PeerMessage.HaveNone -> if (fast) {
        require(!availabilitySeen) { "Repeated or late availability" }
        availabilitySeen = true
        if (message == PeerMessage.HaveAll) {
          for (index in 0 until pieceCount) {
            available[index / 8] = (available[index / 8].toInt() or (128 ushr (index % 8)))
              .toByte()
          }
        }
      }
      is PeerMessage.AllowedFast -> if (fast) {
        require(message.index in 0 until pieceCount)
        // More than a canonical set is noise; the first ones count.
        if (allowedFast.size < MAX_ALLOWED_FAST) allowedFast.add(message.index)
      }
      else -> Unit
    }
    return true
  }

  companion object {
    /** Allowed-fast pieces a peer may name (BEP 6 suggests 10). */
    const val MAX_ALLOWED_FAST: Int = 32
  }
}
