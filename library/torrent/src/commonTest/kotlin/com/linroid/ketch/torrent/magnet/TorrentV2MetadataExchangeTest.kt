package com.linroid.ketch.torrent.magnet

import com.linroid.ketch.torrent.TorrentV2Fixture
import com.linroid.ketch.torrent.crypto.torrentRandomBytes
import com.linroid.ketch.torrent.engine.TorrentBufferBudget
import com.linroid.ketch.torrent.metainfo.Bencode
import com.linroid.ketch.torrent.metainfo.TorrentV2Document
import com.linroid.ketch.torrent.net.PeerEndpoint
import com.linroid.ketch.torrent.net.TorrentListener
import com.linroid.ketch.torrent.net.createTorrentNetwork
import com.linroid.ketch.torrent.peer.PeerExtensions
import com.linroid.ketch.torrent.peer.PeerIdentityHandshake
import com.linroid.ketch.torrent.peer.PeerMessage
import com.linroid.ketch.torrent.peer.PeerWire
import com.linroid.ketch.torrent.v2.peer.PeerHashMessage
import com.linroid.ketch.torrent.v2.peer.PeerHashWire
import com.linroid.ketch.torrent.v2.peer.TorrentV2HashServer
import com.linroid.ketch.torrent.v2.peer.peerHashServeBounds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okio.ByteString
import okio.ByteString.Companion.toByteString
import okio.IOException
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.incrementAndFetch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** A btmh magnet's metadata from peers that each hold only some of the piece layers. */
@OptIn(ExperimentalAtomicApi::class)
class TorrentV2MetadataExchangeTest {
  // Twenty files of three 32 KiB pieces each: twenty piece layers.
  private val fixture = TorrentV2Fixture.build((10..29).map { "f$it" to 70_000 })
  private val document = fixture.document
  private val roots = document.info.files.map { checkNotNull(it.piecesRoot) }
  private val local = PeerEndpoint("127.0.0.1", 0)

  @Test
  fun layers_addUpAcrossPeersThatEachHaveSome() = runTest {
    withContext(Dispatchers.Default) {
      withTimeout(15_000) {
        coroutineScope {
          val network = createTorrentNetwork()
          // As qBittorrent peers do, each has the layers of the files it has reached.
          val first = PartialPeer(network.listen(local), roots.take(8).toSet())
          val second = PartialPeer(network.listen(local), roots.drop(8).toSet())
          val jobs = listOf(first.serve(this), second.serve(this))
          try {
            val exchange = TorrentV2MetadataExchange(network)
            val parts = TorrentV2LayerParts()
            val error = assertFailsWith<IllegalStateException> {
              exchange.fetch(document.identity, first.listener.local, parts = parts)
            }
            assertEquals("Peer lacks the piece layers of 12 of 20 files", error.message)
            val metainfo = exchange.fetch(document.identity, second.listener.local,
              parts = parts)
            val fetched = TorrentV2Document.parse(metainfo, expectedIdentity = document.identity)
            assertEquals(document.pieceLayers, fetched.pieceLayers)
            // The second peer was asked only for what the first lacked.
            assertEquals(12, second.requests.load())
          } finally {
            jobs.forEach { it.cancelAndJoin() }
            network.close()
          }
        }
      }
    }
  }

  /** Serves the info dictionary, and the piece layers of [serves] only, rejecting the rest. */
  private inner class PartialPeer(
    val listener: TorrentListener,
    private val serves: Set<ByteString>,
  ) {
    val requests = AtomicInt(0)
    private val hashes = TorrentV2HashServer(document, fixture.layout,
      TorrentBufferBudget(1_000_000))

    fun serve(scope: CoroutineScope): Job = scope.launch {
      while (true) {
        val connection = listener.accept()
        launch {
          try {
            PeerIdentityHandshake(document.identity, extensions = true).respond(connection,
              torrentRandomBytes(20).toByteString(), TorrentBufferBudget(65_536))
            val wire = PeerWire(connection)
            val raw = document.info.rawInfo
            wire.send(PeerMessage.Extended(0, Bencode.encode(mapOf(
              "m" to mapOf("ut_metadata" to PeerExtensions.METADATA.toLong()),
              "metadata_size" to raw.size.toLong()))))
            while (true) {
              val message = wire.read()
              if (message is PeerMessage.Extended && message.id == PeerExtensions.METADATA) {
                val piece = Bencode.parse(message.payload)["piece"]!!.integer!!.toInt()
                wire.send(TorrentMetadataExchange.response(PeerExtensions.METADATA, piece, raw))
              }
              val request = (message as? PeerMessage.Unknown)
                ?.let { PeerHashWire.decode(it) as? PeerHashMessage.Request } ?: continue
              val selector = request.selector
              val answer = if (selector.root in serves) {
                requests.incrementAndFetch()
                val bounds = checkNotNull(peerHashServeBounds(document, fixture.layout, selector))
                PeerHashMessage.Hashes(selector,
                  checkNotNull(hashes.respond(selector, bounds) { error("No piece reads") }))
              } else PeerHashMessage.Reject(selector)
              wire.send(PeerHashWire.encode(answer))
            }
          } catch (_: IOException) {
            // The fetcher closes the connection once it has what it wanted.
          } finally {
            connection.close()
          }
        }
      }
    }
  }
}
