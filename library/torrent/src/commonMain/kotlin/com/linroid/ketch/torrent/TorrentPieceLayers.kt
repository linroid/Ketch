package com.linroid.ketch.torrent

import okio.ByteString

/** Validates an imported document's external layers; BEP 9 info-only responses cannot call this. */
internal fun TorrentV2Info.validatePieceLayers(
  layers: Map<ByteString, ByteString>,
  maxBytes: Long = 64L * 1024 * 1024,
): Map<ByteString, ByteString> {
  require(maxBytes in 0..256L * 1024 * 1024)
  var total = 0L
  for (bytes in layers.values) {
    require(bytes.size <= maxBytes - total) { "Piece layers exceed byte limit" }
    total += bytes.size
  }
  val filesWithLayers = files.filter { it.length > pieceLength }
  val required = filesWithLayers.map { checkNotNull(it.piecesRoot) }.toSet()
  require(layers.keys == required) { "Missing or unreferenced piece layers" }
  val checked = mutableSetOf<Pair<ByteString, Long>>()
  for (file in filesWithLayers) {
    val root = checkNotNull(file.piecesRoot)
    val count = (file.length - 1) / pieceLength + 1
    val bytes = layers.getValue(root)
    require(bytes.size.toLong() == count * 32) { "Wrong piece-layer hash count" }
    if (!checked.add(root to count)) continue
    val verifier = TorrentPieceLayerVerifier(file.length, pieceLength, root.toByteArray())
    for (offset in 0 until bytes.size step 32) {
      verifier.append(bytes.substring(offset, offset + 32).toByteArray())
    }
    require(verifier.verify()) { "Piece layer does not match file root" }
  }
  return layers.toMap()
}
