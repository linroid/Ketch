package com.linroid.ketch.torrent

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okio.Buffer
import okio.ByteString.Companion.toByteString
import okio.FileHandle
import okio.FileSystem
import okio.ForwardingFileSystem
import okio.IOException
import okio.Path
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class TorrentV2SessionLoopTest {
  private val bytes = ByteArray(16_387) { it.toByte() }
  private val last = byteArrayOf(4, 5, 6)
  private val root = sha256Digest(sha256Digest(bytes.copyOfRange(0, 16_384)) +
    sha256Digest(bytes.copyOfRange(16_384, bytes.size)))
  private val document = TorrentV2Document.parse(Bencode.encode(mapOf("info" to mapOf(
    "meta version" to 2L, "piece length" to 32_768L, "file tree" to mapOf(
      "a" to mapOf("" to mapOf("length" to bytes.size.toLong(), "pieces root" to root)),
      "b" to mapOf("" to mapOf("length" to last.size.toLong(),
        "pieces root" to sha256Digest(last))))
  ), "piece layers" to emptyMap<String, Any>())))
  private val layout = TorrentContentLayout.from(document.info)

  private inner class Connection(bitfield: Int) : TorrentConnection {
    override val remote = PeerEndpoint("127.0.0.1", 1)
    private val input = Channel<ByteArray>(Channel.UNLIMITED)
    private val pending = Buffer()
    val requests = mutableListOf<PeerMessage.Request>()
    var closed = false
    var readers = 0
    var corruptOnce = false
    var failRequests = false
    var failAfterResponses = Int.MAX_VALUE
    var announceAAfterB = false
    var startGate: CompletableDeferred<Unit>? = null
    var responseSignal: CompletableDeferred<Unit>? = null
    var closeAfterResponses = Int.MAX_VALUE
    /** Keeps answers back until [flush], so requests stay in flight. */
    var deferResponses = false
    private val deferred = mutableListOf<PeerMessage>()
    fun flush() {
      deferResponses = false
      deferred.forEach(::add)
      deferred.clear()
    }
    init {
      add(PeerMessage.Bitfield(byteArrayOf(bitfield.toByte())))
      add(PeerMessage.Control(PeerMessage.Signal.UNCHOKE))
    }
    override suspend fun readExactly(size: Int): ByteArray {
      readers++
      try {
        startGate?.await()
        while (pending.size < size) pending.write(input.receive())
        return pending.readByteArray(size.toLong())
      } finally { readers-- }
    }
    override suspend fun write(bytes: ByteArray) {
      check(!closed)
      val buffer = Buffer().write(bytes)
      val size = buffer.readInt()
      val message = PeerWire.decode(buffer.readByteArray(size.toLong()))
      if (message is PeerMessage.Request) {
        requests += message
        if (failRequests || requests.size > failAfterResponses) {
          throw IOException("Peer request write failed")
        }
        val source = if (message.index == 0) this@TorrentV2SessionLoopTest.bytes else last
        val payload = source.copyOfRange(message.begin, message.begin + message.length)
        if (corruptOnce) {
          payload[0] = (payload[0].toInt() xor 1).toByte()
          corruptOnce = false
        }
        val piece = PeerMessage.Piece(message.index, message.begin, payload)
        if (deferResponses) deferred += piece else add(piece)
        responseSignal?.complete(Unit)
        if (announceAAfterB && message.index == 1) add(PeerMessage.Have(0))
        if (requests.size == closeAfterResponses) input.close()
      }
    }
    private fun add(message: PeerMessage) {
      check(input.trySend(PeerWire.encode(message, pieceCount = 2)).isSuccess)
    }
    override fun close() {
      closed = true
      input.cancel()
    }
  }

  private inner class Fixture(
    selected: Set<String>,
    fileSystem: FileSystem = torrentFileSystem,
  ) {
    val buffers = TorrentBufferBudget(1_000_000)
    val state = TorrentBufferBudget(4_000_000)
    val output = FileSystem.SYSTEM_TEMPORARY_DIRECTORY /
      "ketch-session-${InfoHash.fromBytes(torrentRandomBytes(20)).hex}"
    val slots = Semaphore(1)
    val store = TorrentV2PieceStore(document, output, selected, "test", buffers, slots,
      fileSystem)
    val connections = mutableListOf<Connection>()
    fun attach(
      pool: PeerV2Pool,
      clock: () -> Long,
      bitfield: Int = 192,
      configure: (Connection) -> Unit = {},
    ) {
      val connection = Connection(bitfield)
      configure(connection)
      connections += connection
      val transport = PeerHashTransport(connection, PeerHashExchange(buffers, { null }), buffers,
        pieceCount = 2)
      val blocks = PeerBlockExchange(layout, transport, buffers, clock = clock)
      assertNotNull(pool.attach(transport, blocks))
    }
    /** One connection handed to a swarm loop, as a dialer or responder would deliver it. */
    fun handle(
      clock: () -> Long,
      peerId: Int,
      generation: Long = 0,
      configure: (Connection) -> Unit = {},
    ): Pair<Connection, PeerV2Connector.Connected> {
      val connection = Connection(192)
      configure(connection)
      connections += connection
      val transport = PeerHashTransport(connection, PeerHashExchange(buffers, { null }), buffers,
        pieceCount = 2)
      val blocks = PeerBlockExchange(layout, transport, buffers, clock = clock)
      val id = ByteArray(20) { peerId.toByte() }.toByteString()
      val route = PeerIdentityHandshake.Result(document.identity, PeerIdentityHandshake.Mode.V2,
        id, extensions = false, dht = false)
      val info = PeerInfo(id, PeerIdentityHandshake.Mode.V2,
        PeerV2Origin.Incoming(connection.remote), extensions = false, link = connection)
      val admission = assertNotNull(state.reserve(1024))
      return connection to PeerV2Connector.Connected(route, info, transport, blocks, admission,
        generation)
    }

    fun checkReleased() {
      assertTrue(connections.all { it.closed && it.readers == 0 })
      assertEquals(0, buffers.allocated)
      assertEquals(0, state.allocated)
    }
  }

  /** The channels a session hands its loop; discovery stays open and finds nobody. */
  private inner class Swarm(
    f: Fixture,
    limit: Int,
    generation: Long = 0,
    dialCapacity: Int = 4,
    val failures: Channel<PeerV2Dialer.Failure<TorrentV2DialTarget>> = Channel(4),
  ) {
    val connections = Channel<PeerV2Connector.Connected>(Channel.UNLIMITED,
      onUndeliveredElement = { it.close() })
    val discovered = Channel<TorrentV2Discovered>()
    val dial = Channel<TorrentV2DialTarget>(dialCapacity)
    val controls = Channel<TorrentV2SessionLoop.Control>(8)
    private val network = object : TorrentNetwork {
      override suspend fun connect(remote: PeerEndpoint): TorrentConnection = error("Unused")
      override suspend fun listen(local: PeerEndpoint): TorrentListener = error("Unused")
      override suspend fun bindUdp(local: PeerEndpoint): TorrentDatagramSocket = error("Unused")
      override fun close() = Unit
    }
    val swarm = TorrentV2Swarm(document, TorrentV2Runtime(network,
      ByteArray(20) { 9 }.toByteString(), f.buffers, f.state), restricted = false,
      waitForPeers = false, discovered = discovered, dial = dial, dialFailures = failures,
      controls = controls, connectionLimit = { limit }, generation = { generation })

    fun send(connected: PeerV2Connector.Connected) {
      check(connections.trySend(connected).isSuccess)
    }

    fun close() {
      connections.cancel()
      discovered.cancel()
      dial.cancel()
      failures.cancel()
      controls.cancel()
    }
  }

  private suspend fun kotlinx.coroutines.test.TestScope.swarmDownload(
    f: Fixture,
    swarm: Swarm,
    maxPeers: Int,
  ) = async {
    PeerV2Pool.run(f.state, maxPeers = maxPeers) { pool ->
      TorrentV2CommitWorker.run(f.store, dispatcher = StandardTestDispatcher(testScheduler)) {
        TorrentV2SessionLoop.download(layout, setOf("0"), f.store, pool, it, f.buffers, f.state,
          maxPeers = maxPeers, connections = swarm.connections,
          nowMs = { testScheduler.currentTime }, swarm = swarm.swarm)
      }
    }
  }

  @Test
  fun connectionLimitDecreaseStopsOnlyExcessPeers() = runTest {
    val f = Fixture(setOf("0"))
    val clock = { testScheduler.currentTime }
    val s = Swarm(f, limit = 3)
    try {
      f.store.initialize()
      val gate = CompletableDeferred<Unit>()
      val (first, oldest) = f.handle(clock, peerId = 1) { it.startGate = gate }
      val (busy, working) = f.handle(clock, peerId = 2) { it.deferResponses = true }
      val (newest, latest) = f.handle(clock, peerId = 3) { it.startGate = gate }
      listOf(oldest, working, latest).forEach(s::send)
      val download = swarmDownload(f, s, maxPeers = 3)
      try {
        runCurrent()
        // Only the busy peer has unchoked us and holds requests; the others gave nothing yet.
        assertEquals(2, busy.requests.size)
        s.controls.send(TorrentV2SessionLoop.Control.SetLimit(2))
        runCurrent()
        // Of the idle peers, the newest goes first.
        assertTrue(newest.closed)
        assertFalse(first.closed)
        assertFalse(busy.closed)
        s.controls.send(TorrentV2SessionLoop.Control.SetLimit(1))
        runCurrent()
        assertTrue(first.closed)
        assertFalse(busy.closed)
        // The pool, its workers and the remaining peer carry on without a restart.
        busy.flush()
        download.await()
      } finally { download.cancelAndJoin() }
      assertTrue(f.store.completed())
      assertEquals(2, busy.requests.size)
      assertTrue(first.requests.isEmpty() && newest.requests.isEmpty())
      f.checkReleased()
    } finally {
      s.close()
      f.store.cleanup()
    }
  }

  @Test
  fun dialFailureQueueMakesWorkersWaitRatherThanDrop() {
    val maxPeers = 2
    val queue = TorrentV2DownloadSession.dialFailures(maxPeers)
    val capacity = maxPeers + TorrentV2DownloadSession.DIAL_QUEUE +
      TorrentV2DownloadSession.DIAL_WORKERS
    val failures = List(capacity + 1) {
      PeerV2Dialer.Failure(TorrentV2DialTarget(PeerEndpoint("10.0.0.${it + 1}", 6881),
        PeerIdentityHandshake.Mode.V2, upgrade = false, generation = 0), IOException("refused"))
    }
    // Room for every target the loop may have out, and the dial queue and workers besides.
    for (failure in failures.take(capacity)) assertTrue(queue.trySend(failure).isSuccess)
    // Full, it refuses the next rather than evicting one: a worker waits for room.
    assertTrue(queue.trySend(failures.last()).isFailure)
    assertEquals(failures.first(), queue.tryReceive().getOrNull())
    queue.cancel()
  }

  @Test
  fun everyDialOfALargeFailedBurstIsCountedAndRetried() = runTest {
    val f = Fixture(setOf("0"))
    val count = 100
    // More dials out at once than the 64 failures the session once kept, reported into the
    // session's own queue as they arrive while the loop is busy: none may be dropped or refused.
    val s = Swarm(f, limit = count, dialCapacity = Channel.UNLIMITED,
      failures = TorrentV2DownloadSession.dialFailures(maxPeers = count))
    try {
      f.store.initialize()
      val download = swarmDownload(f, s, maxPeers = count)
      try {
        val endpoints = List(count) { PeerEndpoint("10.0.${it / 200}.${it % 200 + 1}", 6881) }
        for (endpoint in endpoints) {
          s.discovered.send(TorrentV2Discovered(endpoint, PeerTopic.V2, PeerOrigin.TRACKER))
        }
        runCurrent()
        val first = List(count) { assertNotNull(s.dial.tryReceive().getOrNull()) }
        assertEquals(endpoints.toSet(), first.map { it.endpoint }.toSet())
        // Every connect fails at once, as when the device loses its route.
        for (target in first) {
          assertTrue(s.failures.trySend(PeerV2Dialer.Failure(target,
            IOException("Network is unreachable"))).isSuccess)
        }
        runCurrent()
        assertTrue(s.dial.tryReceive().isFailure)
        // Each backs off five seconds, then every one is dialed again: none is counted as out.
        advanceTimeBy(5_000)
        runCurrent()
        val second = generateSequence { s.dial.tryReceive().getOrNull() }.toList()
        assertEquals(endpoints.toSet(), second.map { it.endpoint }.toSet())
        assertFalse(download.isCompleted)
      } finally { download.cancelAndJoin() }
      f.checkReleased()
    } finally {
      s.close()
      f.store.cleanup()
    }
  }

  @Test
  fun staleGenerationConnectionIsClosed() = runTest {
    val f = Fixture(setOf("0"))
    val clock = { testScheduler.currentTime }
    val s = Swarm(f, limit = 2, generation = 1)
    try {
      f.store.initialize()
      val (stale, before) = f.handle(clock, peerId = 1, generation = 0)
      val (current, after) = f.handle(clock, peerId = 2, generation = 1)
      s.send(before)
      s.send(after)
      val download = swarmDownload(f, s, maxPeers = 2)
      try { download.await() } finally { download.cancelAndJoin() }
      assertTrue(f.store.completed())
      // A connection from before a reset is closed unused; the session carries on.
      assertTrue(stale.closed)
      assertTrue(stale.requests.isEmpty())
      assertEquals(2, current.requests.size)
      f.checkReleased()
    } finally {
      s.close()
      f.store.cleanup()
    }
  }

  @Test
  fun failedAttachClosesHandleAndContinues() = runTest {
    val f = Fixture(setOf("0"))
    val clock = { testScheduler.currentTime }
    val s = Swarm(f, limit = 2)
    try {
      f.store.initialize()
      val (refused, unattached) = f.handle(clock, peerId = 1)
      val (served, attached) = f.handle(clock, peerId = 2)
      val download = swarmDownload(f, s, maxPeers = 2)
      try {
        runCurrent()
        // With no session state left, the pool cannot take the peer's event slot.
        val hold = assertNotNull(f.state.reserve(f.state.capacity - f.state.allocated))
        try {
          s.send(unattached)
          runCurrent()
          assertTrue(refused.closed)
          assertFalse(download.isCompleted)
        } finally { hold.close() }
        s.send(attached)
        download.await()
      } finally { download.cancelAndJoin() }
      assertTrue(f.store.completed())
      assertTrue(refused.requests.isEmpty())
      assertEquals(2, served.requests.size)
      f.checkReleased()
    } finally {
      s.close()
      f.store.cleanup()
    }
  }

  @Test
  fun rateLimitedRequestsKeepReceivingAndHonorLiveGlobalAndTaskChanges() = runTest {
    val f = Fixture(setOf("0"))
    val clock = { testScheduler.currentTime }
    val global = TorrentRateLimiter(1, clock)
    val task = TorrentRateLimiter(1, clock)
    try {
      f.store.initialize()
      val download = async {
        PeerV2Pool.run(f.state, maxPeers = 1) { pool ->
          f.attach(pool, clock)
          TorrentV2CommitWorker.run(f.store, dispatcher = StandardTestDispatcher(testScheduler)) {
            TorrentV2SessionLoop.download(layout, setOf("0"), f.store, pool, it,
              f.buffers, f.state, maxPeers = 1,
              requestDelay = { bytes, admit -> global.requestDelay(bytes, task, admit) },
              nowMs = clock)
          }
        }
      }
      try {
        runCurrent()
        assertEquals(1, f.connections.single().requests.size)
        advanceTimeBy(1000)
        runCurrent()
        assertFalse(download.isCompleted)
        assertEquals(1, f.connections.single().requests.size)
        global.set(0)
        advanceTimeBy(50)
        runCurrent()
        assertEquals(1, f.connections.single().requests.size)
        task.set(0)
        advanceTimeBy(50)
        runCurrent()
        download.await()
      } finally { download.cancelAndJoin() }
      assertTrue(f.store.completed())
      assertEquals(2, f.connections.single().requests.size)
      f.checkReleased()
    } finally { f.store.cleanup() }
  }

  @Test
  fun automaticallyRetriesCorruptPiecesAndWritesOnlySelectedFiles() = runTest {
    val selected = setOf("0")
    val f = Fixture(selected)
    try {
      f.store.initialize()
      PeerV2Pool.run(f.state, maxPeers = 2) { pool ->
        f.attach(pool, { testScheduler.currentTime }) { it.corruptOnce = true }
        TorrentV2CommitWorker.run(
          store = f.store,
          dispatcher = StandardTestDispatcher(testScheduler),
        ) { worker ->
          TorrentV2SessionLoop.download(layout, selected, f.store, pool, worker,
            f.buffers, f.state, maxPeers = 2, pipeline = 1)
        }
      }
      assertTrue(f.store.completed())
      assertContentEquals(bytes, torrentFileSystem.read(f.output / "a") { readByteArray() })
      assertFalse(torrentFileSystem.exists(f.output / "b"))
      val requests = f.connections.single().requests
      assertEquals(4, requests.size)
      assertTrue(requests.all { it.index == 0 })
      assertEquals(2, requests.count { it.begin == 0 })
      f.checkReleased()
    } finally { f.store.cleanup() }
  }

  @Test
  fun disconnectReassignsBlocksToTheRemainingPeerAndCompletesBothFiles() = runTest {
    val f = Fixture(emptySet())
    try {
      f.store.initialize()
      PeerV2Pool.run(f.state, maxPeers = 2) { pool ->
        f.attach(pool, { testScheduler.currentTime }) { it.failRequests = true }
        f.attach(pool, { testScheduler.currentTime })
        TorrentV2CommitWorker.run(
          store = f.store,
          dispatcher = StandardTestDispatcher(testScheduler),
        ) { worker ->
          TorrentV2SessionLoop.download(layout, emptySet(), f.store, pool, worker,
            f.buffers, f.state, maxPeers = 2)
        }
      }
      assertTrue(f.store.completed())
      assertTrue(f.connections.first().requests.isNotEmpty())
      assertTrue(f.connections.last().requests.any { it.index == 0 })
      assertContentEquals(bytes, torrentFileSystem.read(f.output / "a") { readByteArray() })
      assertContentEquals(last, torrentFileSystem.read(f.output / "b") { readByteArray() })
      f.checkReleased()
    } finally { f.store.cleanup() }
  }

  @Test
  fun unavailablePartialPieceReleasesTheActiveSlotForPiecesOtherPeersCanSupply() = runTest {
    // This sequential-piece case crosses real file I/O between requests. Keep network timers on
    // the real dispatcher so virtual-time skipping cannot expire a healthy peer during disk I/O.
    withContext(Dispatchers.Default) {
      withTimeout(10_000) {
        val f = Fixture(emptySet())
        try {
          f.store.initialize()
          PeerV2Pool.run(f.state, maxPeers = 2) { pool ->
            val partial = CompletableDeferred<Unit>()
            f.attach(pool, monotonicClock(), bitfield = 128) {
              it.failAfterResponses = 1
              it.responseSignal = partial
            }
            f.attach(pool, monotonicClock(), bitfield = 64) {
              it.startGate = partial
              it.announceAAfterB = true
            }
            TorrentV2CommitWorker.run(f.store) { worker ->
              TorrentV2SessionLoop.download(layout, emptySet(), f.store, pool, worker,
                f.buffers, f.state, maxPeers = 2, maxActive = 1)
            }
          }
          assertTrue(f.store.completed())
          assertEquals(2, f.connections.first().requests.size)
          assertEquals(1, f.connections.last().requests.first().index)
          assertContentEquals(bytes, torrentFileSystem.read(f.output / "a") { readByteArray() })
          assertContentEquals(last, torrentFileSystem.read(f.output / "b") { readByteArray() })
          f.checkReleased()
        } finally { f.store.cleanup() }
      }
    }
  }

  @Test
  fun diskFailureStopsTheSessionWithoutPublishingUnverifiedProgress() = runTest {
    val failure = IOException("Injected disk failure")
    var fail = false
    val fs = object : ForwardingFileSystem(torrentFileSystem) {
      override fun openReadWrite(file: Path, mustCreate: Boolean, mustExist: Boolean): FileHandle {
        if (fail) throw failure
        return super.openReadWrite(file, mustCreate, mustExist)
      }
    }
    val f = Fixture(setOf("0"), fs)
    try {
      f.store.initialize()
      fail = true
      val caught = assertFailsWith<IOException> {
        PeerV2Pool.run(f.state, maxPeers = 1) { pool ->
          f.attach(pool, { testScheduler.currentTime })
          TorrentV2CommitWorker.run(
            store = f.store,
            dispatcher = StandardTestDispatcher(testScheduler),
          ) { worker ->
            TorrentV2SessionLoop.download(layout, setOf("0"), f.store, pool, worker,
              f.buffers, f.state, maxPeers = 1)
          }
        }
      }
      assertEquals(failure.message, caught.message)
      assertFalse(f.store.completed())
      assertEquals(0L, f.store.progress().getValue("0"))
      f.checkReleased()
    } finally {
      fail = false
      f.store.cleanup()
    }
  }

  @Test
  fun exhaustingAllPeersFailsAndReleasesUnfinishedAssemblies() = runTest {
    val f = Fixture(emptySet())
    try {
      f.store.initialize()
      val failure = assertFailsWith<IllegalStateException> {
        PeerV2Pool.run(f.state, maxPeers = 1) { pool ->
          f.attach(pool, { testScheduler.currentTime }) { it.failRequests = true }
          TorrentV2CommitWorker.run(f.store) { worker ->
            TorrentV2SessionLoop.download(layout, emptySet(), f.store, pool, worker,
              f.buffers, f.state, maxPeers = 1)
          }
        }
      }
      assertTrue(failure.message.orEmpty().contains("disconnected"))
      assertFalse(f.store.completed())
      f.checkReleased()
    } finally { f.store.cleanup() }
  }

  @Test
  fun lastPeerDepartureStillWaitsForAnAlreadyQueuedCommit() = runTest {
    val f = Fixture(setOf("0"))
    try {
      f.store.initialize()
      f.slots.acquire()
      val download = async {
        PeerV2Pool.run(f.state, maxPeers = 1) { pool ->
          f.attach(pool, { testScheduler.currentTime }) { it.closeAfterResponses = 2 }
          TorrentV2CommitWorker.run(
            store = f.store,
            dispatcher = StandardTestDispatcher(testScheduler),
          ) { worker ->
            TorrentV2SessionLoop.download(layout, setOf("0"), f.store, pool, worker,
              f.buffers, f.state, maxPeers = 1)
          }
        }
      }
      try {
        try {
          runCurrent()
          assertTrue(f.connections.single().closed)
          assertEquals(0, f.connections.single().readers)
          assertFalse(download.isCompleted)
        } finally { f.slots.release() }
        download.await()
      } finally { download.cancelAndJoin() }
      assertTrue(f.store.completed())
      f.checkReleased()
    } finally { f.store.cleanup() }
  }

  @Test
  fun alreadyVerifiedSelectionCompletesWithoutAnyPeer() = runTest {
    val f = Fixture(setOf("1"))
    try {
      f.store.initialize()
      assertTrue(f.store.commit(1, last))
      PeerV2Pool.run(f.state, maxPeers = 1) { pool ->
        TorrentV2CommitWorker.run(
          store = f.store,
          dispatcher = StandardTestDispatcher(testScheduler),
        ) { worker ->
          TorrentV2SessionLoop.download(layout, setOf("1"), f.store, pool, worker,
            f.buffers, f.state, maxPeers = 1)
        }
      }
      assertTrue(f.store.completed())
      f.checkReleased()
    } finally { f.store.cleanup() }
  }
}
