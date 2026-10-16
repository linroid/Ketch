package com.linroid.ketch.torrent.mse

import com.linroid.ketch.api.log.KetchLogger
import com.linroid.ketch.torrent.TorrentEncryption
import com.linroid.ketch.torrent.net.DEFAULT_CONNECT_TIMEOUT_MS
import com.linroid.ketch.torrent.net.PeerEndpoint
import com.linroid.ketch.torrent.net.PrefixedConnection
import com.linroid.ketch.torrent.net.TorrentConnection
import com.linroid.ketch.torrent.net.TorrentNetwork
import com.linroid.ketch.torrent.net.connectWithin
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import okio.ByteString
import okio.IOException
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/**
 * Applies the engine's [TorrentEncryption] policy to peer connections: dials negotiate MSE/PE
 * before the BitTorrent handshake, and incoming connections are answered either way. Under
 * [TorrentEncryption.PREFERRED] a peer that will not encrypt is dialed again in plaintext, and
 * dialed in plaintext first while it is among the last 1024 such peers. A handshake that failed
 * after agreeing on a secret older libtorrent hashes differently is tried once more with new
 * keys first ([MseHandshakeException.leadingZeroSecret]).
 */
@OptIn(ExperimentalAtomicApi::class)
internal class TorrentPeerEncryption(
  private val delegate: TorrentNetwork,
  policy: TorrentEncryption,
  private val handshakeTimeoutMs: Long = 10_000,
  private val keyPairs: () -> MseKeyPair = ::MseKeyPair,
) : TorrentNetwork by delegate {
  private val log = KetchLogger("TorrentEngine")
  private val current = AtomicReference(policy)

  /** The policy dials and incoming connections follow; each reads it once, as it starts. */
  var policy: TorrentEncryption
    get() = current.load()
    set(value) = current.store(value)
  private val mutex = Mutex()
  private val plaintextPeers = LinkedHashSet<PeerEndpoint>()

  /** Connects to [remote] for the torrent with wire tag [tag], negotiating MSE as the policy says. */
  suspend fun dial(
    remote: PeerEndpoint,
    tag: ByteArray,
    connectTimeoutMs: Long = DEFAULT_CONNECT_TIMEOUT_MS,
  ): TorrentConnection {
    // Each connect has its own deadline, so a plaintext retry still has time after MSE failed.
    suspend fun connect() = delegate.connectWithin(remote, connectTimeoutMs)
    val policy = policy
    if (policy == TorrentEncryption.DISABLED) return connect()
    if (policy == TorrentEncryption.PREFERRED && mutex.withLock { remote in plaintextPeers }) {
      return connect()
    }
    val provide = if (policy == TorrentEncryption.REQUIRED) MseHandshake.CRYPTO_RC4
      else MseHandshake.CRYPTO_RC4 or MseHandshake.CRYPTO_PLAINTEXT
    // A new connection negotiated; throws MseHandshakeException when the peer would not.
    suspend fun negotiate(): TorrentConnection {
      val connection = connect()
      try {
        withTimeoutOrNull(handshakeTimeoutMs) {
          MseHandshake.initiate(connection, tag, provide, keyPairs())
        }?.let { return it }
      } catch (e: Throwable) {
        connection.close()
        throw e
      }
      connection.close()
      throw MseHandshakeException("No MSE answer within ${handshakeTimeoutMs / 1000}s")
    }
    val failure = try {
      return negotiate()
    } catch (e: MseHandshakeException) {
      if (!e.leadingZeroSecret) {
        e
      } else {
        log.v { "Peer $remote refused a secret with a leading zero byte, trying new keys" }
        try {
          return negotiate()
        } catch (retry: MseHandshakeException) {
          retry
        }
      }
    }
    if (policy == TorrentEncryption.REQUIRED) throw failure
    log.v { "Peer $remote did not encrypt, dialing in plaintext: ${failure.message}" }
    mutex.withLock {
      plaintextPeers.remove(remote)
      plaintextPeers += remote
      if (plaintextPeers.size > 1024) plaintextPeers.remove(plaintextPeers.first())
    }
    return connect()
  }

  /**
   * Answers an incoming [connection] whose first bytes are unread: a plaintext BitTorrent
   * handshake passes through, an MSE opening for one of [tags] is negotiated. Returns the stream
   * with the BitTorrent handshake next; throws when the policy refuses it or MSE fails.
   */
  suspend fun accept(connection: TorrentConnection, tags: Collection<ByteString>): TorrentConnection {
    val policy = policy
    val opening = connection.readExactly(MseHandshake.plaintextHeader.size)
    if (opening.contentEquals(MseHandshake.plaintextHeader)) {
      if (policy == TorrentEncryption.REQUIRED) throw IOException("Plaintext peer refused")
      return PrefixedConnection(connection, opening)
    }
    if (policy == TorrentEncryption.DISABLED) throw IOException("Encrypted peer refused")
    val allowed = if (policy == TorrentEncryption.REQUIRED) MseHandshake.CRYPTO_RC4
      else MseHandshake.CRYPTO_RC4 or MseHandshake.CRYPTO_PLAINTEXT
    return MseHandshake.respond(connection, opening, tags.map { it.toByteArray() }, allowed).first
  }
}
