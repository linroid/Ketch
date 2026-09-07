package com.linroid.ketch.torrent

/** Per-connection availability and request ownership, independent of the swarm scheduler. */
internal class PeerProtocolState(pieceCount: Int, private val maxPending: Int = 32) {
  init {
    require(pieceCount in 0..209_715 && maxPending in 1..256)
  }

  val available: BooleanArray = BooleanArray(pieceCount)
  var choking: Boolean = true
    private set
  var interested: Boolean = false
    private set
  private var availabilitySeen = false
  private val pending = mutableSetOf<PeerMessage.Request>()
  private val canceled = linkedSetOf<PeerMessage.Request>()
  val requests: Set<PeerMessage.Request> get() = pending.toSet()

  fun requested(request: PeerMessage.Request) {
    check(!choking) { "Peer is choking" }
    require(request.index in available.indices && available[request.index]) { "Peer lacks piece" }
    require(pending.size < maxPending && request !in pending) {
      "Peer pipeline is full or duplicated"
    }
    canceled.remove(request)
    pending.add(request)
  }

  fun cancel(request: PeerMessage.Request) {
    if (pending.remove(request)) {
      canceled.add(request)
      while (canceled.size > maxPending * 2) canceled.remove(canceled.first())
    }
  }

  /** Returns false for a late response to a canceled request. */
  fun received(message: PeerMessage): Boolean {
    when (message) {
      is PeerMessage.Control -> when (message.signal) {
        PeerMessage.Signal.CHOKE -> {
          choking = true
          pending.toList().forEach(::cancel)
        }
        PeerMessage.Signal.UNCHOKE -> choking = false
        PeerMessage.Signal.INTERESTED -> interested = true
        PeerMessage.Signal.NOT_INTERESTED -> interested = false
      }
      is PeerMessage.Have -> {
        require(message.index in available.indices)
        availabilitySeen = true
        available[message.index] = true
      }
      is PeerMessage.Bitfield -> {
        require(!availabilitySeen) { "Repeated or late bitfield" }
        require(message.bytes.size == (available.size + 7) / 8)
        availabilitySeen = true
        for (index in available.indices) {
          available[index] = message.bytes[index / 8].toInt() and (128 ushr (index % 8)) != 0
        }
      }
      is PeerMessage.Piece -> {
        val request = PeerMessage.Request(message.index, message.begin, message.bytes.size)
        if (pending.remove(request)) return true
        if (request in canceled) return false
        throw IllegalArgumentException("Unsolicited or mismatched peer block")
      }
      else -> Unit
    }
    return true
  }
}
