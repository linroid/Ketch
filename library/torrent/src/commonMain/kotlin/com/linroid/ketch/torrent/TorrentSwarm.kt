package com.linroid.ketch.torrent

import com.linroid.ketch.api.log.KetchLogger
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.onTimeout
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okio.IOException
import kotlin.time.TimeSource

/** Bounded peer workers around verified storage. Each worker owns its wire and request state. */
internal class TorrentSwarm(
  private val store: TorrentPieceStore,
  private val network: TorrentNetwork,
  private val budget: TorrentBufferBudget,
  private val peerId: ByteArray = torrentRandomBytes(20),
  private val connections: () -> Int = { 20 },
  private val uploadPolicy: TorrentUploadPolicy = TorrentUploadPolicy.DISABLED,
  private val downloadPayload: suspend (Int) -> Unit = {},
  private val uploadPayload: suspend (Int) -> Unit = {},
  private val onProgress: suspend (Long) -> Unit = {},
  private val onCompleted: suspend () -> Unit = {},
  private val allowLocalDiscovery: Boolean = false,
  trackerOnly: Boolean = false,
  private val logLabel: String = logHash(store.metadata.infoHash.hex),
) {
  private val log = KetchLogger("TorrentSwarm")
  private val trackerRestricted = store.metadata.isPrivate || trackerOnly
  private val uploadSlots = Semaphore(4)
  private val connectedMutex = Mutex()
  private val connected = mutableMapOf<Int, PeerEndpoint>()

  /** A closed peer stream fails once every candidate has exhausted its bounded retries. */
  @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class,
    kotlinx.coroutines.DelicateCoroutinesApi::class)
  suspend fun run(
    peers: ReceiveChannel<PeerEndpoint>,
    incoming: ReceiveChannel<TorrentConnection>? = null,
    resets: ReceiveChannel<CompletableDeferred<Unit>>? = null,
  ) = supervisorScope {
    store.initialize()
    if (store.completed() && uploadPolicy != TorrentUploadPolicy.SEED_AFTER_COMPLETION) {
      store.finish()
      onProgress(store.progress().sum())
      onCompleted()
      return@supervisorScope
    }
    val scheduler = TorrentPieceScheduler(BooleanArray(store.pieceCount) { store.needed(it) },
      store.verifiedPieces(), store::pieceSize, budget)
    val largestPiece = (0 until store.pieceCount).filter { store.needed(it) }
      .maxOfOrNull { store.pieceSize(it) } ?: 0
    val peerOverhead = PEER_WIRE_BYTES + store.pieceCount * 4
    require(budget.capacity >= largestPiece + peerOverhead) {
      "Torrent buffer limit cannot hold a piece and peer protocol state"
    }
    // Leave room for one claim so connected peers can never starve piece buffers entirely.
    fun reservePeer(): TorrentBufferBudget.Lease? {
      if (budget.capacity - budget.allocated - peerOverhead < largestPiece) return null
      return budget.reserve(peerOverhead)
    }
    val active = mutableMapOf<PeerEndpoint, Job>()
    val attempts = linkedMapOf<PeerEndpoint, Int>()
    val attemptedAt = mutableMapOf<PeerEndpoint, Long>()
    val pending = ArrayDeque<PeerEndpoint>()
    val results = Channel<Pair<PeerEndpoint, Throwable?>>(512)
    val progressEvents = Channel<Unit>(Channel.CONFLATED)
    val pexEvents = Channel<Pair<PeerEndpoint, PexUpdate>>(64)
    val pexDirectory = TorrentPeerDirectory(trackerRestricted)
    val retryAt = mutableMapOf<PeerEndpoint, Long>()
    val now = monotonicClock()
    var discoveryClosed = false
    var complete = false
    var nextId = 0
    // Counters since the last periodic summary, which explains a stalled swarm.
    var discovered = 0
    var connectFailures = 0
    var corruptPieces = 0
    var lastSummary = now()
    fun acceptPex(source: PeerEndpoint, update: PexUpdate) {
      pexDirectory.update(PeerOrigin.PEX, "${source.host}:${source.port}", update.added,
        update.dropped)
      for (endpoint in pexDirectory.candidates()) {
        if (endpoint !in attempts && endpoint !in pending &&
          attempts.size + pending.size < 4096) pending.addLast(endpoint)
      }
    }
    suspend fun logSummary() {
      // The inline lambda only runs, and only reads the store, when logging is enabled.
      log.d {
        val verified = store.verifiedPieces().count { it }
        val handshaken = connectedMutex.withLock { connected.size }
        "Swarm $logLabel: pieces $verified/${store.pieceCount}, peers connected=$handshaken " +
          "active=${active.size}/${connections()}, queued=${pending.size}, " +
          "known=${attempts.size}, discovered=$discovered, failed=$connectFailures, " +
          "corrupt=$corruptPieces, discovery=${if (discoveryClosed) "closed" else "open"}"
      }
      discovered = 0
      connectFailures = 0
      corruptPieces = 0
    }
    fun launchPeer(
      endpoint: PeerEndpoint,
      overhead: TorrentBufferBudget.Lease,
      connection: TorrentConnection? = null,
    ) {
      val id = nextId++
      attempts[endpoint] = (attempts[endpoint] ?: 0) + 1
      attemptedAt[endpoint] = now()
      active[endpoint] = launch(Dispatchers.Default, start = CoroutineStart.ATOMIC) {
        var failure: Throwable? = null
        try {
          peer(id, endpoint, scheduler, progressEvents, pexEvents, connection)
        } catch (e: CancellationException) {
          throw e
        } catch (e: Exception) {
          failure = e
        } finally {
          withContext(NonCancellable) {
            scheduler.remove(id)
            connectedMutex.withLock { connected.remove(id) }
          }
          connection?.close()
          overhead.close()
          results.trySend(endpoint to failure)
        }
      }
    }
    try {
      while (currentCoroutineContext().isActive) {
        // A terminating introducer may have queued its last PEX before its result was selected.
        repeat(64) {
          val event = pexEvents.tryReceive().getOrNull() ?: return@repeat
          acceptPex(event.first, event.second)
        }
        if (store.completed() && !complete) {
          store.finish()
          onProgress(store.progress().sum())
          onCompleted()
          complete = true
        }
        if (now() - lastSummary >= SWARM_SUMMARY_INTERVAL_MS) {
          lastSummary = now()
          logSummary()
        }
        if (complete && uploadPolicy != TorrentUploadPolicy.SEED_AFTER_COMPLETION) break
        val expired = attemptedAt.filter { (endpoint, time) ->
          now() - time >= 300_000 && endpoint !in active && endpoint !in pending
        }.keys
        expired.forEach { attempts.remove(it); attemptedAt.remove(it); retryAt.remove(it) }
        val limit = connections().coerceIn(1, 512)
        active.entries.drop(limit).forEach { it.value.cancel() }
        var candidates = pending.size
        while (active.size < limit && pending.isNotEmpty() && candidates-- > 0) {
          val endpoint = pending.removeFirst()
          if (endpoint in active) continue
          if ((retryAt[endpoint] ?: 0) > now()) {
            pending.addLast(endpoint)
            continue
          }
          val overhead = reservePeer() ?: run {
            pending.addFirst(endpoint)
            break
          }
          launchPeer(endpoint, overhead)
        }
        if (discoveryClosed && pending.isEmpty() && active.isEmpty() && incoming == null) {
          if (!complete) logSummary()
          check(complete) { "No peer could complete the torrent" }
          break
        }
        select<Unit> {
          incoming?.onReceive { connection ->
            val overhead = if (active.size < limit && connection.remote !in active) {
              reservePeer()
            } else null
            if (overhead == null) connection.close()
            else launchPeer(connection.remote, overhead, connection)
          }
          resets?.onReceive { done ->
            log.d { "Swarm $logLabel: disconnecting ${active.size} peer(s) for a tracker change" }
            active.values.forEach { it.cancel() }
            active.values.forEach { it.join() }
            active.clear()
            while (results.tryReceive().isSuccess) { }
            while (peers.tryReceive().isSuccess) { }
            while (true) (incoming?.tryReceive()?.getOrNull() ?: break).close()
            pending.clear()
            attempts.clear()
            attemptedAt.clear()
            retryAt.clear()
            done.complete(Unit)
          }
          if (!discoveryClosed) peers.onReceiveCatching { result ->
            val endpoint = result.getOrNull()
            if (endpoint == null) discoveryClosed = true
            else if (endpoint !in attempts && attempts.size + pending.size < 4096 &&
              endpoint !in pending) {
              pending.addLast(endpoint)
              discovered++
            }
          }
          results.onReceive { (endpoint, failure) ->
            active.remove(endpoint)
            if (failure is TorrentStorageException) throw failure
            if (failure != null) {
              connectFailures++
              if (failure is CorruptPieceException) corruptPieces++
              log.v { "Peer $endpoint for $logLabel closed: ${failure.describeWithoutUrls()}" }
            }
            if (failure !is IllegalArgumentException && (attempts[endpoint] ?: 0) < 3 &&
              !complete) {
              retryAt[endpoint] = now() + 1000L * (attempts[endpoint] ?: 1)
              pending.addLast(endpoint)
            }
          }
          pexEvents.onReceive { (source, update) -> acceptPex(source, update) }
          progressEvents.onReceive { onProgress(store.progress().sum()) }
          onTimeout(100) {}
        }
      }
    } finally {
      active.values.forEach { it.cancel() }
      active.values.forEach { it.join() }
      results.close()
      progressEvents.close()
      pexEvents.close()
    }
  }

  private suspend fun peer(
    id: Int,
    endpoint: PeerEndpoint,
    scheduler: TorrentPieceScheduler,
    progressEvents: SendChannel<Unit>,
    pexEvents: SendChannel<Pair<PeerEndpoint, PexUpdate>>,
    accepted: TorrentConnection? = null,
  ) {
    val connection = accepted ?: network.connect(endpoint)
    try {
      PeerWorker(id, endpoint, connection, scheduler, progressEvents, pexEvents).run()
    } finally {
      connection.close()
    }
  }

  /**
   * One peer connection. Its state lives in fields and its loop is split into small functions:
   * as one coroutine body, every suspension point saved every local, and the body grew past the
   * size ART compiles, leaving the hot peer loop interpreted on Android.
   */
  private inner class PeerWorker(
    private val id: Int,
    private val endpoint: PeerEndpoint,
    private val connection: TorrentConnection,
    private val scheduler: TorrentPieceScheduler,
    private val progressEvents: SendChannel<Unit>,
    private val pexEvents: SendChannel<Pair<PeerEndpoint, PexUpdate>>,
  ) {
    private val uploadCache = TorrentUploadCache(store, budget)
    private val wire = PeerWire(connection, store.metadata)
    private val exchange = PeerExchange()
    private val extensions = PeerExtensions()
    private val state = PeerProtocolState(store.pieceCount, maxPending = 16)
    private var extensionsNegotiated = false
    private var uploadSlot = false
    private var metadataServed = 0
    private var metadataWindow = TimeSource.Monotonic.markNow()
    private var advertised = BooleanArray(0)
    private var version = -1L
    private var claim: TorrentPieceScheduler.Claim? = null
    private var received = BooleanArray(0)
    private var requested = BooleanArray(0)
    private var receivedBytes = 0
    private var lastUsefulPayload = TimeSource.Monotonic.markNow()
    private var lastBlock = TimeSource.Monotonic.markNow()
    private var lastWrite = TimeSource.Monotonic.markNow()

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    suspend fun run() = supervisorScope {
      try {
        open()
        val messages = Channel<PeerMessage>(1)
        val consumed = Channel<Unit>(1)
        val reader = launch {
          try {
            while (isActive) {
              val message = wire.read()
              messages.send(message)
              // Do not start an idle read while the actor intentionally waits on a rate limiter.
              consumed.receive()
            }
          } catch (e: Throwable) {
            messages.close(e)
          }
        }
        lastUsefulPayload = TimeSource.Monotonic.markNow()
        lastBlock = TimeSource.Monotonic.markNow()
        lastWrite = TimeSource.Monotonic.markNow()
        try {
          while (isActive) {
            val message = select<PeerMessage?> {
              messages.onReceive { it }
              onTimeout(100) { null }
            }
            if (message != null) {
              receive(message)
              consumed.trySend(Unit)
            }
            maintain()
          }
        } finally {
          withContext(NonCancellable) {
            reader.cancelAndJoin()
            runCatching {
              withTimeoutOrNull(200) {
                for (request in state.requests) {
                  wire.send(PeerMessage.Cancel(request.index, request.begin, request.length))
                }
              }
            }
            messages.cancel()
            consumed.cancel()
          }
        }
      } finally {
        uploadCache.close()
        if (uploadSlot) uploadSlots.release()
      }
    }

    private suspend fun open() {
      val handshake = wire.handshake(PeerHandshake(store.metadata.infoHash, peerId, true, false))
      require(!handshake.peerId.contentEquals(peerId)) { "Connected to ourselves" }
      connectedMutex.withLock { connected[id] = connection.remote }
      extensionsNegotiated = handshake.extensions
      metadataWindow = TimeSource.Monotonic.markNow()
      if (extensionsNegotiated) {
        wire.send(PeerExtensions.handshake(store.metadata, pex = !trackerRestricted))
      }
      advertised = store.verifiedPieces()
      wire.send(PeerMessage.Bitfield(pieceBitfield(advertised)))
      wire.send(PeerMessage.Control(PeerMessage.Signal.INTERESTED))
    }

    private suspend fun receive(message: PeerMessage) {
      if (message is PeerMessage.Piece) {
        val throttling = TimeSource.Monotonic.markNow()
        downloadPayload(message.bytes.size)
        val paused = throttling.elapsedNow()
        lastBlock += paused
        lastUsefulPayload += paused
      }
      val accepted = state.received(message)
      when (message) {
        // Only the one-time bitfield rebuilds availability; HAVE stays incremental so a
        // peer cannot force a full snapshot and rarity scan per announcement.
        is PeerMessage.Bitfield -> scheduler.availability(id, state.availabilitySnapshot())
        is PeerMessage.Have -> scheduler.announce(id, message.index)
        is PeerMessage.Control -> {
          if (message.signal == PeerMessage.Signal.CHOKE) {
            scheduler.release(id)
            claim = null
          }
          if (message.signal == PeerMessage.Signal.NOT_INTERESTED && uploadSlot) {
            uploadSlots.release()
            uploadSlot = false
            uploadCache.close()
            wire.send(PeerMessage.Control(PeerMessage.Signal.CHOKE))
          }
        }
        is PeerMessage.Piece -> if (accepted) receiveBlock(message)
        is PeerMessage.Request -> if (uploadSlot && state.interested &&
          scheduler.isVerified(message.index)) {
          upload(message)
        }
        is PeerMessage.Extended -> receiveExtension(message)
        else -> Unit
      }
    }

    private suspend fun receiveBlock(message: PeerMessage.Piece) {
      val current = checkNotNull(claim)
      check(message.index == current.index && message.begin % PeerWire.BLOCK_SIZE == 0)
      val block = message.begin / PeerWire.BLOCK_SIZE
      check(!received[block])
      message.bytes.copyInto(current.bytes, message.begin)
      received[block] = true
      receivedBytes += message.bytes.size
      lastBlock = TimeSource.Monotonic.markNow()
      lastUsefulPayload = TimeSource.Monotonic.markNow()
      if (receivedBytes == current.bytes.size) {
        val valid = storage { store.commit(current.index, current.bytes) }
        if (!valid) {
          log.w { "Piece ${current.index} of $logLabel from $endpoint failed its hash check" }
          throw CorruptPieceException()
        }
        scheduler.verified(current.index)
        scheduler.release(id)
        claim = null
        progressEvents.trySend(Unit)
      }
    }

    private suspend fun upload(request: PeerMessage.Request) {
      val bytes = storage { uploadCache.read(request.index) }
      if (bytes != null) {
        uploadPayload(request.length)
        wire.send(PeerMessage.Piece(request.index, request.begin,
          bytes.copyOfRange(request.begin, request.begin + request.length)))
      } else {
        wire.send(PeerMessage.Control(PeerMessage.Signal.CHOKE))
        uploadSlots.release()
        uploadSlot = false
      }
    }

    private suspend fun receiveExtension(message: PeerMessage.Extended) {
      require(extensionsNegotiated) { "Unnegotiated peer extension" }
      if (message.id == 0) extensions.receive(message.payload, 4 * 1024 * 1024)
      else if (message.id == PeerExtensions.METADATA) {
        val header = Bencode.parse(message.payload, PeerWire.MAX_FRAME_SIZE)
        if (header["msg_type"]?.integer == 0L) {
          val index = requireNotNull(header["piece"]?.integer)
          require(index in 0..Int.MAX_VALUE.toLong())
          val remoteId = extensions.id("ut_metadata")
          if (remoteId != 0) {
            if (metadataWindow.elapsedNow().inWholeSeconds >= 60) {
              metadataServed = 0
              metadataWindow = TimeSource.Monotonic.markNow()
            }
            val limit = (store.metadata.infoBytes.size / 16_384 + 1) * 3
            val response = if (metadataServed < limit) {
              metadataServed++
              TorrentMetadataExchange.response(remoteId, index.toInt(), store.metadata)
            } else TorrentMetadataExchange.metadataMessage(remoteId, 2, index.toInt())
            wire.send(response)
          }
        }
      } else if (message.id == PeerExtensions.PEX) {
        require(!trackerRestricted) {
          "Tracker-restricted peer exchange is forbidden"
        }
        val update = exchange.receive(message.payload)
        val added = update.added.filter { peer ->
          allowLocalDiscovery ||
            numericAddress(peer.host)?.let(::publicTorrentAddress) == true
        }
        pexEvents.send(connection.remote to update.copy(added = added))
      }
    }

    /** Runs after every message and idle tick: upload slot, PEX, HAVEs, deadlines, requests. */
    private suspend fun maintain() {
      uploadCache.expire()
      if (state.interested && !uploadSlot && uploadPolicy != TorrentUploadPolicy.DISABLED &&
        uploadSlots.tryAcquire()) {
        uploadSlot = true
        wire.send(PeerMessage.Control(PeerMessage.Signal.UNCHOKE))
      }
      if (!trackerRestricted && extensions.id("ut_pex") != 0 && exchange.due()) {
        val contacts = connectedMutex.withLock { connected.values.toSet() }
          .filter { it != connection.remote && (allowLocalDiscovery ||
            numericAddress(it.host)?.let(::publicTorrentAddress) == true) }.toSet()
        exchange.message(extensions.id("ut_pex"), contacts)?.let { wire.send(it) }
      }
      scheduler.snapshot(version)?.let { (nextVersion, pieces) ->
        version = nextVersion
        for (index in pieces.indices) {
          if (pieces[index] && !advertised[index]) {
            wire.send(PeerMessage.Have(index))
            advertised[index] = true
          }
        }
      }
      val current = claim
      if (current != null && scheduler.isVerified(current.index)) {
        for (request in state.requests) {
          state.cancel(request)
          wire.send(PeerMessage.Cancel(request.index, request.begin, request.length))
        }
        scheduler.release(id)
        claim = null
      }
      if (!store.completed() && lastUsefulPayload.elapsedNow().inWholeSeconds >= 90) {
        throw IOException("Peer made no useful download progress")
      }
      if (state.requests.isNotEmpty() && lastBlock.elapsedNow().inWholeSeconds >= 20) {
        error("Peer block deadline exceeded")
      }
      if (!state.choking && claim == null) {
        claim = scheduler.claim(id)
        claim?.let {
          received = BooleanArray((it.bytes.size + PeerWire.BLOCK_SIZE - 1) /
            PeerWire.BLOCK_SIZE)
          requested = BooleanArray(received.size)
          receivedBytes = 0
          lastBlock = TimeSource.Monotonic.markNow()
        }
      }
      claim?.let { assigned ->
        if (!state.choking) {
          for (block in requested.indices) {
            if (state.requests.size >= 16) break
            if (requested[block]) continue
            val begin = block * PeerWire.BLOCK_SIZE
            val request = PeerMessage.Request(assigned.index, begin,
              minOf(PeerWire.BLOCK_SIZE, assigned.bytes.size - begin))
            state.requested(request)
            wire.send(request)
            requested[block] = true
            lastWrite = TimeSource.Monotonic.markNow()
          }
        }
      }
      if (lastWrite.elapsedNow().inWholeSeconds >= 30) {
        wire.send(PeerMessage.KeepAlive)
        lastWrite = TimeSource.Monotonic.markNow()
      }
    }
  }
}

/** One maximal frame plus its decode copy; piece buffers are charged separately per claim. */
private const val PEER_WIRE_BYTES = 2 * PeerWire.MAX_FRAME_SIZE

internal class TorrentStorageException(cause: IOException) :
  Exception("Torrent storage failed", cause)

/** An [IllegalArgumentException], so the swarm never retries the peer that sent the piece. */
internal class CorruptPieceException : IllegalArgumentException("Peer sent a corrupt piece")

/** How often a swarm logs its peer and piece counts at debug level. */
internal const val SWARM_SUMMARY_INTERVAL_MS = 30_000L

private suspend fun <T> storage(block: suspend () -> T): T = try {
  block()
} catch (e: IOException) {
  throw TorrentStorageException(e)
}
