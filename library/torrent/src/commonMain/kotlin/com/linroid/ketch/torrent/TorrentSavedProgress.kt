package com.linroid.ketch.torrent

import com.linroid.ketch.torrent.metainfo.TorrentContentLayout
import com.linroid.ketch.torrent.metainfo.TorrentV2Document
import com.linroid.ketch.torrent.storage.TorrentCheckpoint
import com.linroid.ketch.torrent.storage.TorrentV2Checkpoint

/**
 * The verified bytes of each file that the checkpoint saved in [state] records, by file ID. A
 * file a selection adds back without a saved segment finds them here: v1 keeps a deselected file
 * and its verified pieces, and v2 keeps them readable. As the stores count them, a verified piece
 * counts its bytes in each file it spans. A v2 checkpoint is read against [metainfo]. Empty when
 * [state] saved no checkpoint of its torrent; throws when the checkpoint cannot be read.
 */
internal fun savedFileProgress(
  state: TorrentSourceState,
  metainfo: ByteArray?,
  config: TorrentConfig,
): Map<String, Long> {
  if (state.resumeData.isEmpty()) return emptyMap()
  val bytes = decodeBase64(state.resumeData)
  if (state.version != 3) {
    val checkpoint = TorrentCheckpoint.decode(bytes) ?: return emptyMap()
    val metadata = checkpoint.metadata
    if (metadata.infoHash.hex != state.infoHash) return emptyMap()
    var offset = 0L
    return buildMap {
      for (file in metadata.files) {
        if (!file.padding) {
          put(file.index.toString(), verifiedBytes(offset, file.size, metadata.pieceLength) {
            checkpoint.verified[it.toInt()]
          })
        }
        offset += file.size
      }
    }
  }
  val checkpoint = TorrentV2Checkpoint.decode(bytes) ?: return emptyMap()
  val document = TorrentV2Document.parse(metainfo ?: return emptyMap(),
    maxDocumentBytes = config.maxMetadataBytes, maxFiles = config.maxFilesPerTorrent)
  checkpoint.validateContent(document)
  val layout = TorrentContentLayout.from(document.info, document.hybrid)
  val hint = checkpoint.verifiedHint
  return layout.files.associate { file ->
    file.id to verifiedBytes(file.offset, file.length, layout.pieceLength) { piece ->
      hint[(piece / 8).toInt()].toInt() and (128 ushr (piece % 8).toInt()) != 0
    }
  }
}

/** The bytes of the file at [offset] of [length] that the [verified] pieces hold. */
private inline fun verifiedBytes(
  offset: Long,
  length: Long,
  pieceLength: Long,
  verified: (Long) -> Boolean,
): Long {
  if (length <= 0) return 0
  val end = offset + length
  var bytes = 0L
  for (piece in offset / pieceLength..(end - 1) / pieceLength) {
    if (verified(piece)) {
      bytes += minOf(end, (piece + 1) * pieceLength) - maxOf(offset, piece * pieceLength)
    }
  }
  return bytes
}
