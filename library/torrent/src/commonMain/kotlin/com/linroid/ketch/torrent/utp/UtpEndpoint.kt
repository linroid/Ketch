package com.linroid.ketch.torrent.utp

import com.linroid.ketch.api.log.KetchLogger
import com.linroid.ketch.torrent.crypto.torrentRandomBytes
import com.linroid.ketch.torrent.describeWithoutUrls
import com.linroid.ketch.torrent.net.PeerEndpoint
import com.linroid.ketch.torrent.net.TorrentConnection
import com.linroid.ketch.torrent.net.TorrentDatagram
import com.linroid.ketch.torrent.net.TorrentNetwork
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okio.IOException
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.time.TimeSource

/**
 * The uTP connections of one UDP port: dials ([dial]), answers SYNs, routes packets by peer and
 * connection ID, ticks every socket and RESETs packets of connections it does not know. One lock
 * guards every socket; packets they produce go out after each step.
 *
 * A SYN's source address can be forged, so an answered SYN stays half open, outside
 * [onIncoming], until the peer's next packet acknowledges our answer ([UtpSocket.confirmed]).
 * At most [maxHalfOpen] wait at once, each for [halfOpenTimeoutUs], and further SYNs are
 * dropped unanswered, so forged SYNs never hold the engine's connection budget and are answered
 * one packet each.
 */
internal class UtpEndpoint(
  private val scope: CoroutineScope,
  private val send: suspend (PeerEndpoint, ByteArray) -> Unit,
  /** Takes a new incoming connection; false refuses it, which RESETs the peer. */
  private val onIncoming: (TorrentConnection) -> Boolean,
  private val options: UtpOptions = UtpOptions(),
  /** Options of dialed sockets: fewer SYN retries, as a dial already waited for TCP. */
  private val dialOptions: UtpOptions = UtpOptions(synRetries = 2),
  private val maxConnections: Int = 512,
  private val maxHalfOpen: Int = 64,
  private val halfOpenTimeoutUs: Long = 10_000_000,
) {
  private data class Key(val remote: PeerEndpoint, val receiveId: Int)

  private class Entry(
    val remote: PeerEndpoint,
    val socket: UtpSocket,
    /** When an accepted socket was created; it is offered to [onIncoming] once confirmed. */
    val acceptedAt: Long = -1,
  ) {
    /** Bumped whenever the socket may have changed; readers and writers wait on it. */
    val version = MutableStateFlow(0L)
    var released = false
    /** Dialed, or accepted and handed to [onIncoming]. */
    var offered = acceptedAt < 0

    val halfOpen: Boolean get() = !offered && !released

    fun signature() = listOf(socket.available, socket.writable, socket.currentState,
      socket.isEndOfStream)
  }

  private val log = KetchLogger("TorrentEngine")
  private val mutex = Mutex()
  private val entries = mutableMapOf<Key, Entry>()
  private val outbox = ArrayList<Pair<PeerEndpoint, UtpPacket>>()
  private val clock = TimeSource.Monotonic.markNow()
  private var resets = 0
  private var resetWindow = 0L
  private var lastReport = 0L

  private fun now(): Long = clock.elapsedNow().inWholeMicroseconds

  init {
    scope.launch {
      while (isActive) {
        delay(50)
        // One failed tick must not stop retransmitting for every connection.
        try { tickAll() } catch (e: CancellationException) {
          throw e
        } catch (e: Exception) {
          log.d { "uTP tick failed: ${e.describeWithoutUrls()}" }
        }
      }
    }
  }

  private suspend fun tickAll() {
        step {
          val now = now()
          val finished = mutableListOf<Key>()
          for ((key, entry) in entries) {
            val before = entry.signature()
            entry.socket.tick(now)
            // Waiters wake only when what they wait for may have changed.
            if (entry.signature() != before) entry.version.update { it + 1 }
            if (entry.socket.isFinished && entry.released) finished += key
            // Never confirmed: forgotten without a RESET, which could go to a forged source.
            if (entry.halfOpen && now - entry.acceptedAt >= halfOpenTimeoutUs) finished += key
          }
          finished.forEach { entries.remove(it) }
          // Every 10 s, how each connection with data to send is doing.
          if (now - lastReport >= 10_000_000) {
            lastReport = now
            for (entry in entries.values) {
              if (entry.socket.inFlight > 0 || entry.socket.writable == 0) {
                // The ID tells apart successive connections to one peer.
                val socket = entry.socket
                log.d { "uTP ${entry.remote} id=${socket.receiveId}: ${socket.describe()}" }
              }
            }
          }
        }
  }

  val connectionCount: Int get() = entries.size

  /** Runs [block] on the sockets, then sends what they produced. */
  private suspend fun <T> step(block: () -> T): T {
    val (result, packets) = mutex.withLock {
      val result = block()
      result to outbox.toList().also { outbox.clear() }
    }
    for ((remote, packet) in packets) {
      try {
        send(remote, packet.encode())
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        // A closed or full socket loses the packet; retransmission covers it.
        log.v { "uTP packet to $remote not sent: ${e.describeWithoutUrls()}" }
      }
    }
    return result
  }

  private fun sender(remote: PeerEndpoint): (UtpPacket) -> Unit = { outbox += remote to it }

  suspend fun receive(datagram: TorrentDatagram) {
    val packet = UtpPacket.decode(datagram.bytes) ?: return
    val remote = datagram.remote
    val incoming = step {
      val now = now()
      if (packet.type == UtpType.SYN) {
        val existing = entries[Key(remote, utpSeq(packet.connectionId))]
        if (existing != null) {
          existing.socket.receive(packet, now)
          return@step null
        }
        if (entries.size >= maxConnections) {
          reset(remote, packet, now)
          return@step null
        }
        // Dropped unanswered: the peer's SYN retries come back once some have expired.
        if (entries.values.count { it.halfOpen } >= maxHalfOpen) return@step null
        val socket = UtpSocket.accept(packet, sender(remote), now, options)
        entries[Key(remote, socket.receiveId)] = Entry(remote, socket, acceptedAt = now)
        return@step null
      }
      val entry = entries[Key(remote, packet.connectionId)]
        ?: if (packet.type == UtpType.RESET) {
          // A RESET may name either of a connection's IDs.
          entries[Key(remote, utpSeq(packet.connectionId))]
            ?: entries[Key(remote, utpSeq(packet.connectionId, -1))]
        } else null
      if (entry == null) {
        if (packet.type != UtpType.RESET) reset(remote, packet, now)
      } else {
        entry.socket.receive(packet, now)
        entry.version.update { it + 1 }
        if (entry.halfOpen && entry.socket.confirmed) {
          entry.offered = true
          return@step entry
        }
      }
      null
    }
    if (incoming != null) {
      val connection = Connection(incoming)
      if (!onIncoming(connection)) connection.abort()
    }
  }

  /** At most 64 RESETs a second, so a flood of stray packets cannot make us one. */
  private fun reset(remote: PeerEndpoint, packet: UtpPacket, now: Long) {
    if (now - resetWindow >= 1_000_000) {
      resetWindow = now
      resets = 0
    }
    if (++resets > 64) return
    outbox += remote to UtpPacket(UtpType.RESET, packet.connectionId, now.toInt(), 0, 0,
      utpSeq(packet.ack), packet.seq)
  }

  /** Connects to [remote] over uTP, or throws once its SYN retries go unanswered. */
  suspend fun dial(remote: PeerEndpoint): TorrentConnection {
    val entry = step {
      if (entries.size >= maxConnections) throw UtpEndpointFullException()
      var id: Int
      do {
        id = torrentRandomBytes(2).let { ((it[0].toInt() and 255) shl 8) or (it[1].toInt() and 255) }
      } while (Key(remote, id) in entries || Key(remote, utpSeq(id)) in entries)
      val socket = UtpSocket.connect(id, sender(remote), now(), dialOptions)
      Entry(remote, socket).also { entries[Key(remote, id)] = it }
    }
    val connection = Connection(entry)
    try {
      entry.version.first {
        val state = mutex.withLock { entry.socket.currentState }
        state != UtpSocket.State.SYN_SENT
      }
      val (state, failure) = mutex.withLock { entry.socket.currentState to entry.socket.failure }
      if (state != UtpSocket.State.CONNECTED) {
        throw IOException("uTP connect failed: ${failure ?: state}")
      }
      return connection
    } catch (e: Throwable) {
      connection.abort()
      throw e
    }
  }

  /** Fails every connection, as when the port under it stopped: readers and writers throw. */
  suspend fun close() {
    step {
      val now = now()
      for (entry in entries.values) {
        entry.socket.abort(now)
        entry.released = true
        entry.version.update { it + 1 }
      }
    }
  }

  /** One uTP socket as a [TorrentConnection]. One reader and one writer at a time. */
  private inner class Connection(private val entry: Entry) : TorrentConnection {
    override val remote: PeerEndpoint get() = entry.remote
    override val utp: Boolean get() = true

    override suspend fun readExactly(size: Int): ByteArray {
      require(size in 0..16 * 1024 * 1024)
      val out = ByteArray(size)
      var filled = 0
      while (filled < size) {
        val seen = entry.version.value
        val chunk = step {
          val socket = entry.socket
          when {
            socket.available > 0 -> socket.read(size - filled, now())
            socket.isEndOfStream -> throw IOException("uTP peer closed the connection")
            socket.currentState == UtpSocket.State.FAILED ->
              throw IOException(socket.failure ?: "uTP connection failed")
            socket.isFinished -> throw IOException("uTP connection closed")
            else -> null
          }
        }
        if (chunk == null) {
          entry.version.first { it != seen }
          currentCoroutineContext().ensureActive()
        } else {
          chunk.copyInto(out, filled)
          filled += chunk.size
        }
      }
      return out
    }

    override suspend fun write(bytes: ByteArray) {
      var offset = 0
      while (offset < bytes.size) {
        val seen = entry.version.value
        val accepted = step {
          val socket = entry.socket
          if (socket.isFinished) {
            throw IOException(socket.failure ?: "uTP connection closed")
          }
          socket.write(bytes, offset, bytes.size - offset, now())
        }
        offset += accepted
        if (offset < bytes.size) entry.version.first { it != seen }
      }
    }

    override fun close() {
      // Sends the FIN once queued bytes went out; the tick loop forgets the socket after.
      scope.launch {
        step {
          entry.socket.close(now())
          entry.released = true
          entry.version.update { it + 1 }
        }
      }
    }

    fun abort() {
      scope.launch {
        step {
          entry.socket.abort(now())
          entry.released = true
          entry.version.update { it + 1 }
        }
      }
    }
  }
}

/**
 * Dials TCP first and, when the peer cannot be reached that way, uTP through the [utp] endpoint
 * of its address family: peers behind NAT or with TCP blocked often still answer uTP. Peers
 * known to speak uTP, from peer exchange ([preferUtp]) or a uTP dial that worked, are dialed
 * over uTP first, saving the TCP connect timeout; the last [capacity] are remembered.
 */
@OptIn(ExperimentalAtomicApi::class)
internal class TorrentUtpFallback(
  private val delegate: TorrentNetwork,
  private val capacity: Int = 4096,
  /** The running endpoint for a peer's address family, rebinding a port that stopped. */
  private val utp: suspend (PeerEndpoint) -> UtpEndpoint?,
) : TorrentNetwork by delegate {
  private val utpPeers = AtomicReference<Set<PeerEndpoint>>(emptySet())

  /** Remembers [endpoints] as reachable over uTP, newest last. */
  fun preferUtp(endpoints: Collection<PeerEndpoint>) {
    if (endpoints.isEmpty()) return
    while (true) {
      val current = utpPeers.load()
      val next = LinkedHashSet(current).apply {
        removeAll(endpoints.toSet())
        addAll(endpoints)
        while (size > capacity) remove(first())
      }
      if (utpPeers.compareAndSet(current, next)) return
    }
  }

  private fun forget(endpoint: PeerEndpoint) {
    while (true) {
      val current = utpPeers.load()
      if (endpoint !in current) return
      if (utpPeers.compareAndSet(current, current - endpoint)) return
    }
  }

  override suspend fun connect(remote: PeerEndpoint): TorrentConnection {
    val first = if (remote in utpPeers.load()) utp(remote) else null
    if (first != null) {
      try {
        return first.dial(remote)
      } catch (e: TimeoutCancellationException) {
        forget(remote)
      } catch (e: CancellationException) {
        throw e
      } catch (_: UtpEndpointFullException) {
        // Our side is full: nothing was learned about the peer, so it stays preferred.
      } catch (e: Exception) {
        // It no longer answers uTP; TCP may still work.
        forget(remote)
      }
    }
    val failure: Throwable = try {
      return delegate.connect(remote)
    } catch (e: TimeoutCancellationException) {
      e
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      // Socket errors differ by platform; any failure to connect is worth a uTP try.
      e
    }
    currentCoroutineContext().ensureActive()
    // uTP was tried first already.
    if (first != null) throw failure
    val endpoint = utp(remote) ?: throw failure
    return try {
      endpoint.dial(remote).also { preferUtp(listOf(remote)) }
    } catch (e: TimeoutCancellationException) {
      failure.addSuppressed(e)
      throw failure
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      failure.addSuppressed(e)
      throw failure
    }
  }
}

/** The endpoint already runs as many uTP connections as it may; the peer was not contacted. */
internal class UtpEndpointFullException : IOException("Too many uTP connections")
