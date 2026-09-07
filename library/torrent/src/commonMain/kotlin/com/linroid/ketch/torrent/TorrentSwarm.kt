package com.linroid.ketch.torrent

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
) {
  private val uploadSlots = Semaphore(4)

  /** A closed peer stream fails once every candidate has exhausted its bounded retries. */
  @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
  suspend fun run(peers: ReceiveChannel<PeerEndpoint>) = supervisorScope {
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
    require(budget.capacity >= largestPiece + 256 * 1024 + store.pieceCount * 4) {
      "Torrent buffer limit cannot hold a piece and peer protocol state"
    }
    val active = mutableMapOf<PeerEndpoint, Job>()
    val attempts = linkedMapOf<PeerEndpoint, Int>()
    val pending = ArrayDeque<PeerEndpoint>()
    val results = Channel<Pair<PeerEndpoint, Throwable?>>(512)
    val progressEvents = Channel<Unit>(Channel.CONFLATED)
    val retryAt = mutableMapOf<PeerEndpoint, Long>()
    val now = monotonicClock()
    var discoveryClosed = false
    var complete = false
    var nextId = 0
    try {
      while (currentCoroutineContext().isActive) {
        if (store.completed() && !complete) {
          store.finish()
          onProgress(store.progress().sum())
          onCompleted()
          complete = true
        }
        if (complete && uploadPolicy != TorrentUploadPolicy.SEED_AFTER_COMPLETION) break
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
          val overhead = budget.reserve(256 * 1024 + store.pieceCount * 4) ?: run {
            pending.addFirst(endpoint)
            break
          }
          val id = nextId++
          attempts[endpoint] = (attempts[endpoint] ?: 0) + 1
          active[endpoint] = launch(Dispatchers.Default) {
            var failure: Throwable? = null
            try {
              peer(id, endpoint, scheduler, progressEvents)
            } catch (e: CancellationException) {
              throw e
            } catch (e: Exception) {
              failure = e
            } finally {
              withContext(NonCancellable) { scheduler.remove(id) }
              overhead.close()
              results.trySend(endpoint to failure)
            }
          }
        }
        if (discoveryClosed && pending.isEmpty() && active.isEmpty()) {
          check(complete) { "No peer could complete the torrent" }
          break
        }
        select<Unit> {
          if (!discoveryClosed) peers.onReceiveCatching { result ->
            val endpoint = result.getOrNull()
            if (endpoint == null) discoveryClosed = true
            else if (endpoint !in attempts && attempts.size + pending.size < 4096 &&
              endpoint !in pending) pending.addLast(endpoint)
          }
          results.onReceive { (endpoint, failure) ->
            active.remove(endpoint)
            if (failure is TorrentStorageException) throw failure
            if (failure !is IllegalArgumentException && (attempts[endpoint] ?: 0) < 3 &&
              !complete) {
              retryAt[endpoint] = now() + 1000L * (attempts[endpoint] ?: 1)
              pending.addLast(endpoint)
            }
          }
          progressEvents.onReceive { onProgress(store.progress().sum()) }
          onTimeout(100) {}
        }
      }
    } finally {
      active.values.forEach { it.cancel() }
      active.values.forEach { it.join() }
      results.close()
      progressEvents.close()
    }
  }

  @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
  private suspend fun peer(
    id: Int,
    endpoint: PeerEndpoint,
    scheduler: TorrentPieceScheduler,
    progressEvents: SendChannel<Unit>,
  ) =
    supervisorScope {
      val connection = network.connect(endpoint)
      var uploadSlot = false
      val uploadCache = TorrentUploadCache(store, budget)
      try {
        val wire = PeerWire(connection, store.metadata)
        val handshake = wire.handshake(PeerHandshake(store.metadata.infoHash, peerId, true, false))
        require(!handshake.peerId.contentEquals(peerId)) { "Connected to ourselves" }
        val extensions = PeerExtensions()
        var metadataServed = 0
        var metadataWindow = TimeSource.Monotonic.markNow()
        if (handshake.extensions) wire.send(PeerExtensions.handshake(store.metadata))
        val state = PeerProtocolState(store.pieceCount, maxPending = 16)
        val advertised = store.verifiedPieces()
        var version = -1L
        wire.send(PeerMessage.Bitfield(pieceBitfield(advertised)))
        wire.send(PeerMessage.Control(PeerMessage.Signal.INTERESTED))
        val messages = Channel<PeerMessage>(1)
        val reader = launch {
          try {
            while (isActive) {
              val message = wire.read()
              if (message is PeerMessage.Piece) downloadPayload(message.bytes.size)
              messages.send(message)
            }
          } catch (e: Throwable) {
            messages.close(e)
          }
        }
        var claim: TorrentPieceScheduler.Claim? = null
        var received = BooleanArray(0)
        var requested = BooleanArray(0)
        var receivedBytes = 0
        var lastBlock = TimeSource.Monotonic.markNow()
        var lastWrite = TimeSource.Monotonic.markNow()
        try {
          while (isActive) {
            val message = select<PeerMessage?> {
              messages.onReceive { it }
              onTimeout(100) { null }
            }
            if (message != null) {
              val accepted = state.received(message)
              when (message) {
                is PeerMessage.Bitfield, is PeerMessage.Have ->
                  scheduler.availability(id, state.available)
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
                is PeerMessage.Piece -> if (accepted) {
                  val current = checkNotNull(claim)
                  check(message.index == current.index && message.begin % PeerWire.BLOCK_SIZE == 0)
                  val block = message.begin / PeerWire.BLOCK_SIZE
                  check(!received[block])
                  message.bytes.copyInto(current.bytes, message.begin)
                  received[block] = true
                  receivedBytes += message.bytes.size
                  lastBlock = TimeSource.Monotonic.markNow()
                  if (receivedBytes == current.bytes.size) {
                    val valid = storage { store.commit(current.index, current.bytes) }
                    require(valid) { "Peer sent a corrupt piece" }
                    scheduler.verified(current.index)
                    scheduler.release(id)
                    claim = null
                    progressEvents.trySend(Unit)
                  }
                }
                is PeerMessage.Request -> if (uploadSlot && state.interested &&
                  scheduler.isVerified(message.index)) {
                  val bytes = storage { uploadCache.read(message.index) }
                  if (bytes != null) {
                    uploadPayload(message.length)
                    wire.send(PeerMessage.Piece(message.index, message.begin,
                      bytes.copyOfRange(message.begin, message.begin + message.length)))
                  } else {
                    wire.send(PeerMessage.Control(PeerMessage.Signal.CHOKE))
                    uploadSlots.release()
                    uploadSlot = false
                  }
                }
                is PeerMessage.Extended -> {
                  require(handshake.extensions) { "Unnegotiated peer extension" }
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
                  }
                }
                else -> Unit
              }
            }
            uploadCache.expire()
            if (state.interested && !uploadSlot && uploadPolicy != TorrentUploadPolicy.DISABLED &&
              uploadSlots.tryAcquire()) {
              uploadSlot = true
              wire.send(PeerMessage.Control(PeerMessage.Signal.UNCHOKE))
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
          }
        }
      } finally {
        uploadCache.close()
        if (uploadSlot) uploadSlots.release()
        connection.close()
      }
    }
}

internal class TorrentStorageException(cause: IOException) :
  Exception("Torrent storage failed", cause)

private suspend fun <T> storage(block: suspend () -> T): T = try {
  block()
} catch (e: IOException) {
  throw TorrentStorageException(e)
}
