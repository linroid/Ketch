package com.linroid.ketch.torrent

import okio.ByteString
import okio.ByteString.Companion.toByteString

/**
 * Authenticated content metadata from a bounded in-memory v2 or hybrid metainfo document.
 * Caller context (trackers, credentials, display hints) is deliberately outside this value.
 * Large imports need the future streaming/spilled loader; this path never raises its byte limit.
 */
internal class TorrentV2Document private constructor(
  val info: TorrentV2Info,
  val pieceLayers: Map<ByteString, ByteString>,
  val hybrid: TorrentHybridLayout?,
) {
  val identity: TorrentIdentity get() = hybrid?.identity ?: TorrentIdentity(v2 = info.hash)

  companion object {
    fun parse(
      document: ByteArray,
      maxDocumentBytes: Int = 4 * 1024 * 1024,
      maxInfoBytes: Int = 4 * 1024 * 1024,
      maxNodes: Int = 100_000,
      maxFiles: Int = 10_000,
      maxLayerBytes: Long = 64L * 1024 * 1024,
      expectedIdentity: TorrentIdentity? = null,
    ): TorrentV2Document {
      require(maxDocumentBytes in 1..32 * 1024 * 1024)
      require(maxInfoBytes in 1..32 * 1024 * 1024)
      require(maxLayerBytes in 0..256L * 1024 * 1024)
      val envelope = Bencode.parse(document, maxDocumentBytes, maxNodes)
      requireNotNull(envelope.dictionary) { "Metainfo must be a dictionary" }
      val infoNode = requireNotNull(envelope["info"]) { "Missing info dictionary" }
      requireNotNull(infoNode.dictionary) { "Info must be a dictionary" }
      require(infoNode.end - infoNode.start <= maxInfoBytes) { "Raw info exceeds byte limit" }
      val rawInfo = document.copyOfRange(infoNode.start, infoNode.end)
      val info = TorrentV2Info.parse(rawInfo, maxInfoBytes, maxFiles, maxNodes, expectedIdentity)
      val hybrid = TorrentHybridLayout.parse(infoNode, info)
      require(expectedIdentity?.v1 == null || hybrid != null) {
        "A v1 topic cannot identify a v2-only document"
      }
      val nodes = requireNotNull(envelope["piece layers"]?.dictionary) {
        "Imported metainfo requires a piece layers dictionary"
      }
      var layerBytes = 0L
      val layers = buildMap {
        for ((root, node) in nodes) {
          require(root.size == 32) { "Piece-layer key must be a SHA-256 root" }
          val hashes = requireNotNull(node.bytes) { "Piece layer must be a byte string" }
          require(hashes.size <= maxLayerBytes - layerBytes) { "Piece layers exceed byte limit" }
          layerBytes += hashes.size
          put(root, hashes.toByteString())
        }
      }
      return TorrentV2Document(info, info.validatePieceLayers(layers, maxLayerBytes), hybrid)
    }
  }
}
