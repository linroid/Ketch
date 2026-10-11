package com.linroid.ketch.torrent.net

import com.linroid.ketch.torrent.mse.TorrentPeerEncryption
import kotlinx.coroutines.withTimeout
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi

internal expect fun createTorrentNetwork(): TorrentNetwork

internal data class PeerEndpoint(val host: String, val port: Int) {
  init {
    require(host.isNotBlank() && host.length <= 253 && port in 0..65535)
  }
}

internal interface TorrentConnection {
  val remote: PeerEndpoint
  /** The bytes travel RC4-encrypted (MSE/PE). */
  val encrypted: Boolean get() = false
  /** The connection runs over uTP rather than TCP. */
  val utp: Boolean get() = false
  suspend fun readExactly(size: Int): ByteArray
  suspend fun write(bytes: ByteArray)
  fun close()
}

internal interface TorrentListener {
  val local: PeerEndpoint
  suspend fun accept(): TorrentConnection
  fun close()
}

internal data class TorrentDatagram(val remote: PeerEndpoint, val bytes: ByteArray)

internal interface TorrentDatagramSocket {
  val local: PeerEndpoint
  suspend fun send(remote: PeerEndpoint, bytes: ByteArray)
  suspend fun receive(): TorrentDatagram
  fun close()
}

internal interface TorrentNetwork {
  suspend fun connect(remote: PeerEndpoint): TorrentConnection
  suspend fun listen(local: PeerEndpoint): TorrentListener
  suspend fun bindUdp(local: PeerEndpoint): TorrentDatagramSocket
  fun close()
}

/**
 * Opens a peer connection for the torrent with 20-byte wire tag [tag], ready for the BitTorrent
 * handshake: [TorrentPeerEncryption] negotiates MSE/PE first, other networks just connect. Not a
 * member, so networks that wrap another by delegation keep routing dials through their own
 * [TorrentNetwork.connect].
 */
internal suspend fun TorrentNetwork.connectPeer(
  remote: PeerEndpoint,
  tag: ByteArray,
  /** Bounds each TCP connect; encryption and a plaintext retry have deadlines of their own. */
  connectTimeoutMs: Long = DEFAULT_CONNECT_TIMEOUT_MS,
): TorrentConnection = if (this is TorrentPeerEncryption) {
  dial(remote, tag, connectTimeoutMs)
} else {
  connectWithin(remote, connectTimeoutMs)
}

/**
 * How long one connect may take: a TCP connect (10 s on its own) and, when TCP fails, the uTP
 * dial after it (7 s). Encryption and a plaintext retry run outside it.
 */
internal const val DEFAULT_CONNECT_TIMEOUT_MS = 20_000L

/**
 * [TorrentNetwork.connect] within [timeoutMs]. The socket is captured inside the timeout, so a
 * cancellation at its return boundary closes it rather than losing it.
 */
internal suspend fun TorrentNetwork.connectWithin(
  remote: PeerEndpoint,
  timeoutMs: Long,
): TorrentConnection {
  var connection: TorrentConnection? = null
  try {
    withTimeout(timeoutMs) { connection = connect(remote) }
    return checkNotNull(connection)
  } catch (error: Throwable) {
    connection?.close()
    throw error
  }
}

/** Owns live sockets only; closing a connection removes its runtime reference immediately. */
@OptIn(ExperimentalAtomicApi::class)
internal class TorrentResources {
  private val resources = AtomicReference<List<Lease>?>(emptyList())

  inner class Lease(private val action: () -> Unit) {
    private val closed = AtomicBoolean(false)
    fun close() {
      if (!closed.compareAndSet(false, true)) return
      try {
        action()
      } finally {
        while (true) {
          val current = resources.load() ?: break
          if (resources.compareAndSet(current, current.filterNot { it === this })) break
        }
      }
    }
  }

  fun register(action: () -> Unit): Lease {
    val lease = Lease(action)
    while (true) {
      val current = resources.load()
      if (current == null) {
        lease.close()
        error("Torrent runtime is closed")
      }
      if (resources.compareAndSet(current, current + lease)) return lease
    }
  }

  val activeCount: Int get() = resources.load()?.size ?: 0

  fun close() {
    resources.exchange(null)?.forEach { lease -> runCatching { lease.close() } }
  }
}
