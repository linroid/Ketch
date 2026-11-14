package com.linroid.ketch.torrent.mse

import com.linroid.ketch.api.log.KetchLogger
import com.linroid.ketch.torrent.TorrentEncryption
import com.linroid.ketch.torrent.net.DEFAULT_CONNECT_TIMEOUT_MS
import com.linroid.ketch.torrent.net.PeerEndpoint
import com.linroid.ketch.torrent.net.PrefixedConnection
import com.linroid.ketch.torrent.net.TorrentConnection
import com.linroid.ketch.torrent.net.TorrentNetwork
import com.linroid.ketch.torrent.net.connectWithin
import com.linroid.ketch.torrent.net.resolveTorrentHost
import com.linroid.ketch.torrent.net.numericHost
import com.linroid.ketch.torrent.net.numericAddress
import com.linroid.ketch.torrent.net.TorrentIpFilter
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import okio.ByteString
import okio.IOException
import kotlin.concurrent.Volatile
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
  /** Looks a peer's host name up when a filter is set, so the filter sees its address. */
  private val resolve: suspend (String) -> List<ByteArray> = ::resolveTorrentHost,
  /**
   * Off when a proxy resolves host names: a peer named by host is then dialed by that name,
   * unfiltered, rather than looked up here, which would tell the local resolver of it.
   */
  private val resolveNames: Boolean = true,
) : TorrentNetwork by delegate {
  private val log = KetchLogger("TorrentEngine")
  private val current = AtomicReference(policy)

  /** The policy dials and incoming connections follow; each reads it once, as it starts. */
  var policy: TorrentEncryption
    get() = current.load()
    set(value) = current.store(value)
  /** The addresses no peer is dialed at, read as each dial starts. */
  @Volatile var filter: () -> TorrentIpFilter = { TorrentIpFilter.NONE }

  /**
   * Where to dial [remote] under the [filter]: itself when it is an address that is not
   * blocked, and for a host name, as an HTTP tracker may list one, the first address it resolves
   * to that is not blocked, so the name cannot lead to a blocked address when it is connected.
   *
   * @throws IOException when every address of it is blocked
   */
  private suspend fun allowed(remote: PeerEndpoint): PeerEndpoint {
    val filter = filter()
    if (filter.isEmpty) return remote
    if (numericAddress(remote.host) != null) {
      if (filter.blocks(remote.host)) throw IOException("The peer's address is blocked")
      return remote
    }
    if (!resolveNames) return remote
    val address = resolve(remote.host).map(::numericHost).firstOrNull { !filter.blocks(it) }
      ?: throw IOException("The peer's address is blocked")
    return PeerEndpoint(address, remote.port)
  }

  private val mutex = Mutex()
  private val plaintextPeers = LinkedHashSet<PeerEndpoint>()

  /** Connects to [remote] for the torrent with wire tag [tag], negotiating MSE as the policy says. */
  suspend fun dial(
    remote: PeerEndpoint,
    tag: ByteArray,
    connectTimeoutMs: Long = DEFAULT_CONNECT_TIMEOUT_MS,
  ): TorrentConnection {
    val target = allowed(remote)
    // Each connect has its own deadline, so a plaintext retry still has time after MSE failed.
    suspend fun connect() = delegate.connectWithin(target, connectTimeoutMs)
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
