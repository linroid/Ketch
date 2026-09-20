package com.linroid.ketch.torrent

import okio.Buffer
import okio.ByteString

/** BEP 9 bounded metadata exchange, with four outstanding blocks and full hash verification. */
internal class TorrentV2MetadataExchange(
  private val network: TorrentNetwork,
  private val maxBytes: Int = 4 * 1024 * 1024,
  private val timeoutMs: Long = 30_000,
  private val budget: TorrentBufferBudget = TorrentBufferBudget(32 * 1024 * 1024),
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
  ): ByteArray = TorrentMetadataExchange(network, maxBytes, timeoutMs, budget)
    .fetchContent(identity, endpoint, trackerTiers, privacy) { connection, raw, workspace ->
      val info = TorrentV2Info.parse(raw, expectedIdentity = identity)
      if (info.privateTorrent && privacy != TorrentDiscoveryPrivacy.TRACKER_ONLY) {
        throw PrivateTorrentMagnetException()
      }
      val layers = fetchLayers(connection, info, workspace)
      val envelope = Bencode.encode(mapOf("announce-list" to trackerTiers))
      val suffix = Bencode.encode(mapOf("piece layers" to layers))
      val metainfo = Buffer().write(envelope, 0, envelope.size - 1)
        .writeUtf8("4:info").write(raw).write(suffix, 1, suffix.size - 1).readByteArray()
      require(metainfo.size <= maxBytes) { "Resolved metainfo exceeds limit" }
      TorrentV2Document.parse(metainfo, maxDocumentBytes = maxBytes, expectedIdentity = identity)
      metainfo
    }

  private suspend fun fetchLayers(
    connection: TorrentConnection,
    info: TorrentV2Info,
    workspace: TorrentBufferBudget,
  ): Map<ByteString, ByteString> {
    val lengths = info.files.filter { it.length > info.pieceLength }
      .associate { requireNotNull(it.piecesRoot) to it.length }
    val exchange = PeerHashExchange(workspace, fileLength = { lengths[it] })
    val transport = PeerHashTransport(connection, exchange, workspace,
      pieceCount = TorrentContentLayout.from(info).pieceCount.toInt())
    try {
      var retained = info.rawInfo.size.toLong()
      return buildMap {
        for ((root, length) in lengths) {
          val count = (length - 1) / info.pieceLength + 1
          require(count * 32 <= maxBytes - retained) { "Piece layers exceed metainfo limit" }
          retained += count * 32
          val layer = Buffer()
          val base = (info.pieceLength / 16_384).countTrailingZeroBits()
          val blocks = (length - 1) / 16_384 + 1
          var width = 1L
          var height = 0
          while (width < blocks) { width *= 2; height++ }
          val group = minOf(512L, width shr base).toInt()
          var index = 0L
          while (index < count) {
            val selector = PeerHashSelector(root, base, index, group, height - base - 1)
            checkNotNull(transport.request(selector)) { "Hash proof budget exhausted" }
            while (true) {
              val frame = transport.read()
              var complete = false
              try {
                require(frame.message !is PeerMessage.Piece) { "Unrequested payload" }
                when (val event = transport.accept(frame)) {
                  is PeerHashTransport.Event.Verified -> try {
                    require(event.result.selector == selector)
                    val take = minOf(group.toLong(), count - index).toInt() * 32
                    layer.write(event.result.hashes.substring(0, take))
                    complete = true
                  } finally { event.result.close() }
                  is PeerHashTransport.Event.Rejected -> error("Peer rejected piece layers")
                  is PeerHashTransport.Event.Request ->
                    transport.respond(PeerHashMessage.Reject(event.request.selector))
                  null -> Unit
                }
              } finally { frame.close() }
              if (complete) break
            }
            index += group
          }
          put(root, layer.readByteString())
        }
      }
    } finally { exchange.close() }
  }

}
