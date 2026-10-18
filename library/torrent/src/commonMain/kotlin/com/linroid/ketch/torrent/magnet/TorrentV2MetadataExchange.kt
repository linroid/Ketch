package com.linroid.ketch.torrent.magnet

import com.linroid.ketch.torrent.TorrentClient
import com.linroid.ketch.torrent.TorrentDiscoveryPrivacy
import com.linroid.ketch.torrent.engine.TorrentBufferBudget
import com.linroid.ketch.torrent.metainfo.Bencode
import com.linroid.ketch.torrent.metainfo.TorrentContentLayout
import com.linroid.ketch.torrent.metainfo.TorrentIdentity
import com.linroid.ketch.torrent.metainfo.TorrentV2Document
import com.linroid.ketch.torrent.metainfo.TorrentV2Info
import com.linroid.ketch.torrent.metainfo.requireSupportedPieceLength
import com.linroid.ketch.torrent.net.PeerEndpoint
import com.linroid.ketch.torrent.net.TorrentConnection
import com.linroid.ketch.torrent.net.TorrentNetwork
import com.linroid.ketch.torrent.peer.PeerMessage
import com.linroid.ketch.torrent.v2.peer.PeerHashExchange
import com.linroid.ketch.torrent.v2.peer.PeerHashMessage
import com.linroid.ketch.torrent.v2.peer.PeerHashSelector
import com.linroid.ketch.torrent.v2.peer.PeerHashTransport
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okio.Buffer
import okio.ByteString
import okio.ByteString.Companion.toByteString

/**
 * What the peers of one v2 lookup sent of the piece layers so far, each group of hashes verified
 * against its file's root as it arrived. A peer that downloads only some of the files has only
 * their layers (qBittorrent asks for a file's layer as it reaches the file), so each peer adds
 * what it has and the next is asked only for the rest. At most the size of the layers, which
 * [TorrentV2MetadataExchange] bounds by its metainfo limit.
 */
internal class TorrentV2LayerParts {
  private val mutex = Mutex()
  private val groups = HashMap<PeerHashSelector, ByteString>()

  suspend fun get(selector: PeerHashSelector): ByteString? = mutex.withLock { groups[selector] }

  suspend fun put(selector: PeerHashSelector, hashes: ByteString) {
    mutex.withLock { groups[selector] = hashes }
  }
}

/** BEP 9 bounded metadata exchange, with four outstanding blocks and full hash verification. */
internal class TorrentV2MetadataExchange(
  private val network: TorrentNetwork,
  private val maxBytes: Int = 4 * 1024 * 1024,
  private val timeoutMs: Long = METADATA_IDLE_MS,
  private val budget: TorrentBufferBudget = TorrentBufferBudget(32 * 1024 * 1024),
  /** Ours in the handshake: the engine's, as for every other peer. */
  private val peerId: ByteString = TorrentClient.peerId().toByteString(),
) {
  init {
    require(maxBytes in 1..4 * 1024 * 1024 && timeoutMs > 0)
    require(budget.capacity >= maxBytes * 4 + 256 * 1024)
  }

  suspend fun fetch(
    identity: TorrentIdentity,
    endpoint: PeerEndpoint,
    trackerTiers: List<List<String>> = emptyList(),
    privacy: TorrentDiscoveryPrivacy = TorrentDiscoveryPrivacy.PUBLIC,
    /** A magnet's web seeds (`ws`), kept in the metainfo as its `url-list`. */
    webSeeds: List<String> = emptyList(),
    /** Told of each info block and piece layer response received. */
    onProgress: () -> Unit = {},
    /** The piece layers other peers of the lookup sent, which this one adds to. */
    parts: TorrentV2LayerParts = TorrentV2LayerParts(),
  ): ByteArray = TorrentMetadataExchange(network, maxBytes, timeoutMs, budget, peerId)
    .fetchContent(identity, endpoint, trackerTiers, privacy, onProgress) {
        connection, raw, workspace, progress ->
      // The info matched its hash, so whatever fails to parse in it fails for every peer.
      val info = unusableUnless {
        TorrentV2Info.parse(raw, expectedIdentity = identity).also { info ->
          requireSupportedPieceLength(info.pieceLength)
          val hashes = info.files.filter { it.length > info.pieceLength }
            .sumOf { (it.length - 1) / info.pieceLength + 1 }
          require(hashes * 32 <= maxBytes - raw.size) { "Piece layers exceed metainfo limit" }
        }
      }
      if (info.privateTorrent && privacy != TorrentDiscoveryPrivacy.TRACKER_ONLY) {
        throw PrivateTorrentMagnetException()
      }
      // Fetched from this peer, so its failures are the peer's.
      val layers = fetchLayers(connection, info, workspace, progress, parts)
      unusableUnless {
        val envelope = Bencode.encode(mapOf("announce-list" to trackerTiers))
        val suffix = Bencode.encode(buildMap {
          put("piece layers", layers)
          if (webSeeds.isNotEmpty()) put("url-list", webSeeds)
        })
        val metainfo = Buffer().write(envelope, 0, envelope.size - 1)
          .writeUtf8("4:info").write(raw).write(suffix, 1, suffix.size - 1).readByteArray()
        require(metainfo.size <= maxBytes) { "Resolved metainfo exceeds limit" }
        // Layers were checked against their roots as they arrived.
        TorrentV2Document.parse(metainfo, maxDocumentBytes = maxBytes,
          expectedIdentity = identity)
        metainfo
      }
    }

  /**
   * Asks for the piece layers [parts] lacks, [LAYER_REQUESTS] hash requests at a time: one after
   * another, a torrent of many files took a round trip each, longer than a peer is given. Fails,
   * keeping what it received in [parts], when the peer lacks some.
   */
  private suspend fun fetchLayers(
    connection: TorrentConnection,
    info: TorrentV2Info,
    frames: TorrentBufferBudget,
    progress: MetadataProgress,
    parts: TorrentV2LayerParts,
  ): Map<ByteString, ByteString> {
    val lengths = info.files.filter { it.length > info.pieceLength }
      .associate { requireNotNull(it.piecesRoot) to it.length }
    // Pending requests hold their hashes within the lease's room for the metainfo, not built yet.
    val exchange = PeerHashExchange(TorrentBufferBudget(LAYER_REQUESTS * REQUEST_BYTES),
      fileLength = { lengths[it] }, maxPending = LAYER_REQUESTS)
    val transport = PeerHashTransport(connection, exchange, frames,
      pieceCount = TorrentContentLayout.from(info).pieceCount.toInt())
    try {
      // Each file's layer, filled in as its groups of at most 512 hashes arrive in any order.
      val layers = LinkedHashMap<ByteString, ByteArray>()
      // Every group, and how many of its hashes the layer keeps.
      val requests = LinkedHashMap<PeerHashSelector, Int>()
      var retained = info.rawInfo.size.toLong()
      for ((root, length) in lengths) {
        val count = (length - 1) / info.pieceLength + 1
        require(count * 32 <= maxBytes - retained) { "Piece layers exceed metainfo limit" }
        retained += count * 32
        layers[root] = ByteArray((count * 32).toInt())
        val base = (info.pieceLength / 16_384).countTrailingZeroBits()
        val blocks = (length - 1) / 16_384 + 1
        var width = 1L
        var height = 0
        while (width < blocks) { width *= 2; height++ }
        val group = minOf(512L, width shr base).toInt()
        var index = 0L
        while (index < count) {
          requests[PeerHashSelector(root, base, index, group, height - base - 1)] =
            minOf(group.toLong(), count - index).toInt()
          index += group
        }
      }
      fun fill(selector: PeerHashSelector, hashes: ByteString) {
        hashes.copyInto(0, layers.getValue(selector.root), (selector.index * 32).toInt(),
          requests.getValue(selector) * 32)
      }
      val wanted = requests.keys.filter { selector ->
        val known = parts.get(selector)
        known?.let { fill(selector, it) }
        known == null
      }
      val unsent = wanted.iterator()
      var next: PeerHashSelector? = null
      var received = 0
      val lacking = HashSet<ByteString>()
      var rejected = 0
      while (received + rejected < wanted.size) {
        while (true) {
          val selector = next ?: if (unsent.hasNext()) unsent.next() else break
          next = selector
          if (transport.request(selector) == null) break
          next = null
        }
        check(exchange.pendingCount > 0) { "Hash proof budget exhausted" }
        val frame = transport.read()
        try {
          require(frame.message !is PeerMessage.Piece) { "Unrequested payload" }
          when (val event = transport.accept(frame)) {
            is PeerHashTransport.Event.Verified -> try {
              val selector = event.result.selector
              val hashes = event.result.hashes.substring(0, requests.getValue(selector) * 32)
              fill(selector, hashes)
              parts.put(selector, hashes)
              received++
              progress.advance()
            } finally { event.result.close() }
            is PeerHashTransport.Event.Rejected -> if (event.matched) {
              rejected++
              lacking += event.selector.root
            }
            is PeerHashTransport.Event.Request ->
              transport.respond(PeerHashMessage.Reject(event.request.selector))
            null -> Unit
          }
        } finally { frame.close() }
      }
      check(rejected == 0) {
        "Peer lacks the piece layers of ${lacking.size} of ${layers.size} files"
      }
      return layers.mapValues { it.value.toByteString() }
    } finally { exchange.close() }
  }

  private companion object {
    /** Hash requests a peer has at once while it sends the piece layers. */
    const val LAYER_REQUESTS = 8

    /** What [PeerHashExchange] holds for a request of 512 hashes and its longest proof. */
    const val REQUEST_BYTES = (512 + 64) * 32 * 3 + 8192
  }
}
