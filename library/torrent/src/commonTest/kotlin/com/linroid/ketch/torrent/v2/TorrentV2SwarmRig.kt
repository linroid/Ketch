package com.linroid.ketch.torrent.v2

import com.linroid.ketch.torrent.TorrentUploadPolicy
import com.linroid.ketch.torrent.TorrentV2Fixture
import com.linroid.ketch.torrent.crypto.torrentRandomBytes
import com.linroid.ketch.torrent.engine.TorrentBufferBudget
import com.linroid.ketch.torrent.engine.TorrentRateLimiter
import com.linroid.ketch.torrent.metainfo.InfoHash
import com.linroid.ketch.torrent.monotonicClock
import com.linroid.ketch.torrent.net.PeerEndpoint
import com.linroid.ketch.torrent.net.TorrentConnection
import com.linroid.ketch.torrent.net.TorrentDatagramSocket
import com.linroid.ketch.torrent.net.TorrentListener
import com.linroid.ketch.torrent.net.TorrentNetwork
import com.linroid.ketch.torrent.peer.FAST_REJECT_GRACE_MS
import com.linroid.ketch.torrent.peer.PeerIdentityHandshake
import com.linroid.ketch.torrent.peer.PeerMessage
import com.linroid.ketch.torrent.peer.PeerWire
import com.linroid.ketch.torrent.storage.TorrentV2PieceStore
import com.linroid.ketch.torrent.storage.torrentFileSystem
import com.linroid.ketch.torrent.v2.peer.PeerBlockExchange
import com.linroid.ketch.torrent.v2.peer.PeerHashExchange
import com.linroid.ketch.torrent.v2.peer.PeerHashTransport
import com.linroid.ketch.torrent.v2.peer.PeerInfo
import com.linroid.ketch.torrent.v2.peer.PeerV2Connector
import com.linroid.ketch.torrent.v2.peer.PeerV2Dialer
import com.linroid.ketch.torrent.v2.peer.PeerV2Origin
import com.linroid.ketch.torrent.v2.peer.PeerV2Pool
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import okio.Buffer
import okio.ByteString.Companion.toByteString
import okio.FileHandle
import okio.FileSystem
import okio.ForwardingFileSystem
import okio.Path
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

private val interested = PeerMessage.Control(PeerMessage.Signal.INTERESTED)
private val unchoke = PeerMessage.Control(PeerMessage.Signal.UNCHOKE)

/** The far end of one connection: the test speaks for the peer and reads what we sent it. */
internal class TorrentV2RemotePeer(
  private val pieceCount: Int,
  override val remote: PeerEndpoint,
) : TorrentConnection {
  private val input = Channel<ByteArray>(Channel.UNLIMITED)
  private val pending = Buffer()
  val received = Channel<PeerMessage>(Channel.UNLIMITED)
  val closed = CompletableDeferred<Unit>()

  override suspend fun readExactly(size: Int): ByteArray {
    while (pending.size < size) pending.write(input.receive())
    return pending.readByteArray(size.toLong())
  }

  override suspend fun write(bytes: ByteArray) {
    check(!closed.isCompleted)
    val buffer = Buffer().write(bytes)
    val size = buffer.readInt()
    received.trySend(PeerWire.decode(buffer.readByteArray(size.toLong()),
      pieceCount = pieceCount))
  }

  fun send(message: PeerMessage) {
    input.trySend(PeerWire.encode(message, pieceCount = pieceCount))
  }

  /** The next message we sent this peer that [match]es; others are skipped. */
  suspend fun next(match: (PeerMessage) -> Boolean = { it != PeerMessage.KeepAlive }):
    PeerMessage {
    while (true) {
      val message = received.receive()
      if (match(message)) return message
    }
  }

  suspend fun nextPiece(): PeerMessage.Piece =
    assertIs<PeerMessage.Piece>(next { it is PeerMessage.Piece })

  override fun close() {
    closed.complete(Unit)
    input.cancel()
  }
}

/**
 * One swarm loop over [fixture]'s storage and the peers the test connects. Upload reads are
 * charged to a partition of [uploadBytes] when given. We listen on [listenPort] as peers see
 * it, unknown when zero. The loop's clock runs with real time, plus what the test [skip]s.
 */
@OptIn(ExperimentalAtomicApi::class)
internal class TorrentV2SwarmRig(
  val fixture: TorrentV2Fixture,
  private val onCompleted: suspend () -> Boolean,
  uploadBytes: Int? = null,
  private val listenPort: Int = 0,
) {
  val layout = fixture.layout
  val pieceCount = layout.pieceCount.toInt()
  val buffers = TorrentBufferBudget(4 * 1024 * 1024)
  val uploads = uploadBytes?.let { TorrentBufferBudget(it, buffers) } ?: buffers
  val state = TorrentBufferBudget(16 * 1024 * 1024)
  private val clock = monotonicClock()
  private val skipped = AtomicLong(0)
  val output = FileSystem.SYSTEM_TEMPORARY_DIRECTORY /
    "ketch-v2-upload-${InfoHash.fromBytes(torrentRandomBytes(20)).hex}"
  /** Pieces read back from disk: the store opens a file read-only only to read one. */
  val pieceReads = AtomicInt(0)
  private val files = object : ForwardingFileSystem(torrentFileSystem) {
    override fun openReadOnly(file: Path): FileHandle {
      pieceReads.addAndFetch(1)
      return super.openReadOnly(file)
    }
  }
  val store = TorrentV2PieceStore(fixture.document, output, emptySet(), "upload", buffers,
    Semaphore(2), files)
  val policy = AtomicReference(TorrentUploadPolicy.SEED_AFTER_COMPLETION)
  val global = TorrentRateLimiter()
  val session = TorrentRateLimiter()
  private val connections = Channel<PeerV2Connector.Connected>(Channel.UNLIMITED,
    onUndeliveredElement = { it.close() })
  val discovered = Channel<TorrentV2Discovered>()
  val dial = Channel<TorrentV2DialTarget>(4)
  val failures = Channel<PeerV2Dialer.Failure<TorrentV2DialTarget>>(4)
  private val controls = Channel<TorrentV2SessionLoop.Control>(8)
  /** Runs when a selection change leaves the complete loop with pieces to download. */
  var onIncomplete: suspend () -> Unit = {}
  /** Our DHT port as peers see it, none when zero; DHT nodes peers named arrive in [dhtNodes]. */
  var dhtPort = 0
  val dhtNodes = Channel<PeerEndpoint>(8)
  private val remotes = mutableListOf<TorrentV2RemotePeer>()
  private var port = 40_000
  private val network = object : TorrentNetwork {
    override suspend fun connect(remote: PeerEndpoint): TorrentConnection = error("Unused")
    override suspend fun listen(local: PeerEndpoint): TorrentListener = error("Unused")
    override suspend fun bindUdp(local: PeerEndpoint): TorrentDatagramSocket = error("Unused")
    override fun close() = Unit
  }

  /**
   * A peer that claims [peerId], handed to the loop as the responders would: one that dialed
   * us from [host], or the one the loop [dialed]. With [extensions], both sides set BEP 10;
   * with [fast], the Fast extension (BEP 6), whose choked requests are released after
   * [fastRejectGraceMs]. Its frames, both ways, are charged to [frames].
   */
  fun connect(
    peerId: Int,
    mode: PeerIdentityHandshake.Mode = PeerIdentityHandshake.Mode.V2,
    host: String = "192.0.2.$peerId",
    extensions: Boolean = false,
    dialed: TorrentV2DialTarget? = null,
    frames: TorrentBufferBudget = buffers,
    dht: Boolean = false,
    fast: Boolean = false,
    fastRejectGraceMs: Long = FAST_REJECT_GRACE_MS,
  ): TorrentV2RemotePeer {
    val remote = TorrentV2RemotePeer(pieceCount, dialed?.endpoint ?: PeerEndpoint(host, port++))
    remotes += remote
    val v2 = mode == PeerIdentityHandshake.Mode.V2
    val transport = PeerHashTransport(remote, PeerHashExchange(buffers, { null }), frames,
      pieceCount = pieceCount, hashMessages = v2)
    val blocks = PeerBlockExchange(layout, transport, buffers, mode = mode, fast = fast,
      fastRejectGraceMs = fastRejectGraceMs)
    val id = ByteArray(20) { peerId.toByte() }.toByteString()
    val route = PeerIdentityHandshake.Result(fixture.document.identity, mode, id,
      extensions = extensions, dht = false, fast = fast)
    val origin = if (dialed != null) PeerV2Origin.Outgoing(remote.remote)
      else PeerV2Origin.Incoming(remote.remote)
    val info = PeerInfo(id, mode, origin, extensions = extensions, link = remote, dht = dht,
      fast = fast)
    val admission = checkNotNull(state.reserve(1024))
    check(connections.trySend(PeerV2Connector.Connected(route, info, transport, blocks,
      admission, generation = dialed?.generation ?: 0)).isSuccess)
    return remote
  }

  /** A connected peer we unchoked: it saw our bitfield (we have something) and asked. */
  suspend fun unchoked(
    peerId: Int,
    mode: PeerIdentityHandshake.Mode = PeerIdentityHandshake.Mode.V2,
    host: String = "192.0.2.$peerId",
  ): TorrentV2RemotePeer {
    val remote = connect(peerId, mode, host)
    assertIs<PeerMessage.Bitfield>(remote.next())
    remote.send(interested)
    assertEquals(unchoke, remote.next())
    return remote
  }

  fun policyChanged() {
    check(controls.trySend(TorrentV2SessionLoop.Control.PolicyChanged).isSuccess)
  }

  /** Tells the loop the store's selection changed; returns once the loop follows it. */
  suspend fun select() {
    val done = CompletableDeferred<Unit>()
    controls.send(TorrentV2SessionLoop.Control.Select(done))
    done.await()
  }

  /** Moves the loop's clock [ms] on and wakes it to look again. */
  fun skip(ms: Long) {
    skipped.addAndFetch(ms)
    policyChanged()
  }

  /** Runs the loop while [body] runs, then stops it; a failure of the loop is rethrown. */
  suspend fun run(body: suspend (Deferred<Unit>) -> Unit) = coroutineScope {
    val swarm = { serve: TorrentV2ServeWorker ->
      TorrentV2Swarm(fixture.document,
        TorrentV2Runtime(network, ByteArray(20) { 9 }.toByteString(), buffers, state,
          uploadRate = global, uploadPolicy = { policy.load() },
          listenPortFor = { listenPort }, uploadBuffers = uploads,
          dhtPortFor = { dhtPort }, onDhtNode = { dhtNodes.trySend(it) }),
        restricted = false, waitForPeers = true, discovered = discovered, dial = dial,
        dialFailures = failures, controls = controls, connectionLimit = { 8 },
        generation = { 0 }, serve = serve, sessionUploadRate = session,
        onCompleted = onCompleted, onIncomplete = { onIncomplete() })
    }
    val loop = async {
      PeerV2Pool.run(state, maxPeers = 8) { pool ->
        TorrentV2CommitWorker.run(store) { worker ->
          TorrentV2ServeWorker.run(store, uploads = uploads) { serve ->
            TorrentV2SessionLoop.download(layout, store.selectedIds(), store, pool, worker,
              buffers,
              state, maxPeers = 8, connections = connections,
              nowMs = { clock() + skipped.load() }, swarm = swarm(serve))
          }
        }
      }
    }
    try { body(loop) } finally {
      loop.cancelAndJoin()
      connections.cancel()
    }
  }

  fun checkReleased() {
    assertTrue(remotes.all { it.closed.isCompleted })
    assertEquals(0, buffers.allocated)
    assertEquals(0, state.allocated)
  }

  suspend fun close() {
    connections.cancel()
    discovered.cancel()
    dial.cancel()
    failures.cancel()
    controls.cancel()
    store.cleanup()
  }
}
