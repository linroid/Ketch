package com.linroid.ketch.torrent

import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okio.Buffer
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

  private inner class Connection : TorrentConnection {
    override val remote = PeerEndpoint("127.0.0.1", 1)
    private val input = Channel<ByteArray>(Channel.UNLIMITED)
    private val pending = Buffer()
    val requests = mutableListOf<PeerMessage.Request>()
    var closed = false
    var readers = 0
    var corruptOnce = false
    var failRequests = false
    var closeAfterResponses = Int.MAX_VALUE
    init {
      add(PeerMessage.Bitfield(byteArrayOf(192.toByte())))
      add(PeerMessage.Control(PeerMessage.Signal.UNCHOKE))
    }
    override suspend fun readExactly(size: Int): ByteArray {
      readers++
      try {
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
        if (failRequests) throw IOException("Peer request write failed")
        val source = if (message.index == 0) this@TorrentV2SessionLoopTest.bytes else last
        val payload = source.copyOfRange(message.begin, message.begin + message.length)
        if (corruptOnce) {
          payload[0] = (payload[0].toInt() xor 1).toByte()
          corruptOnce = false
        }
        add(PeerMessage.Piece(message.index, message.begin, payload))
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
    fun attach(pool: PeerV2Pool, clock: () -> Long, configure: (Connection) -> Unit = {}) {
      val connection = Connection()
      configure(connection)
      connections += connection
      val transport = PeerHashTransport(connection, PeerHashExchange(buffers, { null }), buffers,
        pieceCount = 2)
      val blocks = PeerBlockExchange(layout, transport, buffers, clock = clock)
      assertNotNull(pool.attach(transport, blocks))
    }
    fun checkReleased() {
      assertTrue(connections.all { it.closed && it.readers == 0 })
      assertEquals(0, buffers.allocated)
      assertEquals(0, state.allocated)
    }
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
