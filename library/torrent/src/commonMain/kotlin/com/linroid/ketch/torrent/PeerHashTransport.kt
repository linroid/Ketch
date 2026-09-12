package com.linroid.ketch.torrent

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import okio.Buffer

/**
 * Framed exchange on an already negotiated v2 connection. The actor serializes all writes,
 * accept, expire and close; one separate reader may call read. Close unblocks the reader; the
 * runtime must then join that reader before releasing its connection admission.
 */
internal class PeerHashTransport(
  private val connection: TorrentConnection,
  private val exchange: PeerHashExchange,
  private val frames: TorrentBufferBudget,
  private val timeoutMs: Long = 180_000,
  private val pieceCount: Int? = null,
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

  private val limits = PeerFrameLimits(pieceCount)
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

  /** Caller retains payload ownership until return; admission covers encoder copies and write. */
  suspend fun send(message: PeerMessage) {
    check(!closed)
    // Hash requests must first register their response ownership through request().
    require(message !is PeerMessage.Unknown || message.id !in 21..23) {
      "Use request or respond for hash messages"
    }
    val size = PeerWire.encodedSize(message, pieceCount)
    write(size) { PeerWire.encode(message, pieceCount = pieceCount) }
  }

  /** Hash serving owns proof generation and its buffer; this method admits serialization first. */
  suspend fun respond(message: PeerHashMessage) {
    check(!closed)
    val payloadSize = when (message) {
      is PeerHashMessage.Request -> error("Use request to register hash response ownership")
      is PeerHashMessage.Reject -> 48
      is PeerHashMessage.Hashes -> {
        require(message.hashes.size == message.selector.hashCount * 32)
        48 + message.hashes.size
      }
    }
    write(payloadSize + 5) { PeerWire.encode(PeerHashWire.encode(message)) }
  }

  private suspend fun write(size: Int, encode: () -> ByteArray) {
    val lease = checkNotNull(frames.reserve(size * 4 + 512)) {
      "Peer write frame budget exhausted"
    }
    try {
      withTimeout(timeoutMs) { connection.write(encode()) }
    } catch (error: Throwable) {
      // Partial frames cannot be retried on this byte stream. Pending hash tickets die with it.
      close()
      throw error
    } finally {
      lease.close()
    }
  }

  /** Reserve before reading a body; the consumer retains frame credit through dispatch. */
  suspend fun read(): Frame = reads.withLock {
    var lease: TorrentBufferBudget.Lease? = null
    try {
      val message = withTimeout(timeoutMs) {
        val size = Buffer().write(connection.readExactly(4)).readInt()
        limits.validateSize(size)
        val id = if (size == 0) null else connection.readExactly(1).single().toInt() and 255
        if (id != null) limits.validateType(size, id)
        // Covers raw body, generic decode, hash decode and dispatch copies, plus small headers.
        lease = checkNotNull(frames.reserve(size * 4 + 512)) { "Peer frame budget exhausted" }
        if (id == null) PeerMessage.KeepAlive else {
          val payload = Buffer().writeByte(id).write(connection.readExactly(size - 1))
            .readByteArray()
          PeerWire.decode(payload, pieceCount = pieceCount)
        }
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
