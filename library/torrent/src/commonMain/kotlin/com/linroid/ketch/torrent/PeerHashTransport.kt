package com.linroid.ketch.torrent

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import okio.Buffer

/**
 * Framed hash exchange on an already negotiated v2 connection. The actor owns request/accept/
 * expire/close; one separate reader may call read. Close unblocks the reader; the runtime must
 * then join that reader before releasing its connection admission.
 */
internal class PeerHashTransport(
  private val connection: TorrentConnection,
  private val exchange: PeerHashExchange,
  private val frames: TorrentBufferBudget,
  private val timeoutMs: Long = 180_000,
) {
  class Frame internal constructor(
    val message: PeerMessage,
    private val lease: TorrentBufferBudget.Lease,
  ) {
    fun close() = lease.close()
  }

  sealed interface Event {
    data class Request(val request: PeerHashMessage.Request) : Event
    data class Rejected(val selector: PeerHashSelector, val matched: Boolean) : Event
    data class Verified(val result: PeerHashExchange.Verified) : Event
  }

  private val reads = Mutex()
  private var closed = false

  init { require(timeoutMs in 1..180_000) }

  suspend fun request(selector: PeerHashSelector): PeerHashExchange.Ticket? {
    check(!closed)
    val ticket = exchange.request(selector) ?: return null
    var lease: TorrentBufferBudget.Lease? = null
    try {
      lease = checkNotNull(frames.reserve(512)) { "Hash request frame budget exhausted" }
      val remaining = exchange.remainingMs(ticket)
      check(remaining > 0) { "Hash request expired before write" }
      withTimeout(minOf(timeoutMs, remaining)) {
        connection.write(PeerWire.encode(PeerHashWire.encode(PeerHashMessage.Request(selector))))
      }
      check(exchange.remainingMs(ticket) > 0) { "Hash request expired during write" }
      return ticket
    } catch (error: Throwable) {
      exchange.cancel(ticket)
      // A canceled or failed write may have emitted a partial frame; this stream cannot be reused.
      close()
      throw error
    } finally {
      lease?.close()
    }
  }

  /** Reserve before reading a body; the consumer retains frame credit through dispatch. */
  suspend fun read(): Frame = reads.withLock {
    var lease: TorrentBufferBudget.Lease? = null
    try {
      val message = withTimeout(timeoutMs) {
        val size = Buffer().write(connection.readExactly(4)).readInt()
        require(size in 0..PeerWire.MAX_FRAME_SIZE) { "Peer frame exceeds limit" }
        // Covers raw body, generic decode, hash decode and dispatch copies, plus small headers.
        lease = checkNotNull(frames.reserve(size * 4 + 512)) { "Peer frame budget exhausted" }
        PeerWire.decode(connection.readExactly(size))
      }
      Frame(message, checkNotNull(lease)).also { lease = null }
    } catch (error: Throwable) {
      connection.close()
      throw error
    } finally {
      lease?.close()
    }
  }

  /** Called by the actor. Null leaves ordinary peer messages for its other protocol handlers. */
  fun accept(frame: Frame): Event? {
    check(!closed)
    val message = frame.message as? PeerMessage.Unknown ?: return null
    return when (val hash = PeerHashWire.decode(message)) {
      null -> null
      is PeerHashMessage.Request -> Event.Request(hash)
      is PeerHashMessage.Reject -> Event.Rejected(hash.selector, exchange.reject(hash))
      is PeerHashMessage.Hashes -> Event.Verified(exchange.receive(hash))
    }
  }

  fun expire(): List<PeerHashExchange.Ticket> = exchange.expire()

  fun close() {
    closed = true
    try { connection.close() } finally { exchange.close() }
  }
}
