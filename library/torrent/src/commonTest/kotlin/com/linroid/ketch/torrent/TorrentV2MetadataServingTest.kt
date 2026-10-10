package com.linroid.ketch.torrent

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okio.ByteString.Companion.toByteString
import okio.FileSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A v2 owner that is still downloading gives magnet peers what they need to resolve it, the info
 * dictionary (`ut_metadata`) and its piece layers as hash proofs, but only while it uploads.
 */
class TorrentV2MetadataServingTest {
  // Pieces of 32 KiB: a and b have piece layers, of five and three hashes; c has none.
  private val fixture = TorrentV2Fixture.build(listOf("a" to 150_000, "b" to 70_000, "c" to 5))

  /**
   * Runs [body] against an engine owning [fixture] under [policy], downloading with nobody to
   * download from, then removes the owner and checks the engine gave everything back.
   */
  private fun downloading(
    policy: TorrentUploadPolicy,
    body: suspend (KotlinTorrentEngine) -> Unit,
  ) = runTest {
    withContext(Dispatchers.Default) {
      withTimeout(30_000) {
        val root = (FileSystem.SYSTEM_TEMPORARY_DIRECTORY /
          "ketch-v2-metadata-${InfoHash.fromBytes(torrentRandomBytes(20)).hex}")
          .also { torrentFileSystem.createDirectories(it) }
        val engine = KotlinTorrentEngine(TorrentConfig(dhtEnabled = false,
          uploadPolicy = policy), listenHost = "127.0.0.1")
        try {
          engine.start()
          val owner = engine.addV2Task(TorrentV2TaskSpec("serving", fixture.document,
            (root / "out").toString(), privacy = TorrentDiscoveryPrivacy.PUBLIC,
            discover = { awaitCancellation() }))
          owner.resume()
          assertEquals(TorrentSessionState.DOWNLOADING, owner.state.first {
            it == TorrentSessionState.DOWNLOADING || it == TorrentSessionState.STOPPED
          }, owner.failure.value?.stackTraceToString())
          body(engine)
          assertEquals(TorrentSessionState.DOWNLOADING, owner.state.value)
          engine.removeTorrent(fixture.document.info.hash.hex)
          assertEquals(0, engine.admittedSessionBytes)
        } finally {
          engine.stop()
          torrentFileSystem.deleteRecursively(root, mustExist = false)
        }
        assertEquals(0, engine.allocatedExchangeBytes)
      }
    }
  }

  @Test
  fun downloadingOwnerServesRawInfoToBtmhMagnetFetcher() =
    downloading(TorrentUploadPolicy.WHILE_DOWNLOADING) { engine ->
      val network = createTorrentNetwork()
      try {
        // What a btmh magnet's resolution does: the info dictionary, then each piece layer.
        val metainfo = TorrentV2MetadataExchange(network).fetch(fixture.document.identity,
          PeerEndpoint("127.0.0.1", engine.listenPort))
        val fetched = TorrentV2Document.parse(metainfo,
          expectedIdentity = fixture.document.identity)
        assertEquals(fixture.document.info.rawInfo, fetched.info.rawInfo)
        assertEquals(2, fetched.pieceLayers.size)
        assertEquals(fixture.document.pieceLayers, fetched.pieceLayers)
      } finally { network.close() }
    }

  @Test
  fun disabledUploadAdvertisesNoUtMetadata() =
    downloading(TorrentUploadPolicy.DISABLED) { engine ->
      val network = createTorrentNetwork()
      try {
        val connection = network.connect(PeerEndpoint("127.0.0.1", engine.listenPort))
        try {
          val route = PeerIdentityHandshake(fixture.document.identity, extensions = true)
            .initiate(connection, PeerIdentityHandshake.Mode.V2,
              torrentRandomBytes(20).toByteString(), TorrentBufferBudget(65_536))
          assertTrue(route.extensions)
          val wire = PeerWire(connection, pieceCount = fixture.layout.pieceCount.toInt())
          // Our extension handshake comes first: peer exchange, but no info dictionary.
          val ours = assertIs<PeerMessage.Extended>(wire.read())
          assertEquals(0, ours.id)
          val handshake = Bencode.parse(ours.payload)
          val offered = checkNotNull(handshake["m"]?.dictionary).keys.map { it.utf8() }.toSet()
          assertEquals(setOf("ut_pex"), offered)
          assertNull(handshake["metadata_size"])
          assertEquals(engine.listenPort.toLong(), handshake["p"]?.integer)
          assertEquals(MAX_QUEUED_UPLOADS.toLong(), handshake["reqq"]?.integer)
          // Asked anyway, it rejects the request.
          wire.send(PeerMessage.Extended(0, Bencode.encode(mapOf(
            "m" to mapOf("ut_metadata" to 3L)))))
          wire.send(TorrentMetadataExchange.metadataMessage(PeerExtensions.METADATA, 0, 0))
          val reply = wire.next { it is PeerMessage.Extended && it.id == 3 }
          val header = Bencode.parse((reply as PeerMessage.Extended).payload)
          assertEquals(2L, header["msg_type"]?.integer)
          assertEquals(0L, header["piece"]?.integer)
          // Piece layer proofs are refused as well.
          val layer = PeerHashSelector(checkNotNull(fixture.document.info.files[0].piecesRoot),
            1, 0, 8, 2)
          wire.send(PeerHashWire.encode(PeerHashMessage.Request(layer)))
          val answer = wire.next { it is PeerMessage.Unknown && it.id in 22..23 }
          assertEquals(PeerHashMessage.Reject(layer),
            PeerHashWire.decode(answer as PeerMessage.Unknown))
        } finally { connection.close() }
      } finally { network.close() }
    }

  private suspend fun PeerWire.next(match: (PeerMessage) -> Boolean): PeerMessage {
    while (true) {
      val message = read()
      if (match(message)) return message
    }
  }
}
