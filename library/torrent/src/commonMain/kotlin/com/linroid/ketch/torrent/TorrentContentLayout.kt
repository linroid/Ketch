package com.linroid.ketch.torrent

/** File-aligned v2/hybrid addressing without allocating arrays proportional to piece count. */
internal class TorrentContentLayout private constructor(
  val pieceLength: Long,
  val protocolBytes: Long,
  val payloadBytes: Long,
  val files: List<File>,
  private val spans: List<Span>,
) {
  data class File(val id: String, val v2Index: Int, val offset: Long, val length: Long)
  /** A null file id denotes virtual zeros; those bytes are never an output file. */
  data class Extent(val fileId: String?, val fileOffset: Long, val length: Long)
  private data class Span(val start: Long, val end: Long, val fileId: String?)
  val pieceCount: Long = protocolBytes / pieceLength +
    if (protocolBytes % pieceLength == 0L) 0 else 1

  /** Maps a bounded range in O(log(files) + returned extents), including virtual padding. */
  fun map(offset: Long, length: Long): List<Extent> {
    require(offset >= 0 && length >= 0 && length <= protocolBytes &&
      offset <= protocolBytes - length)
    if (length == 0L) return emptyList()
    val end = offset + length
    var low = 0
    var high = spans.size
    while (low < high) {
      val middle = low + (high - low) / 2
      if (spans[middle].end <= offset) low = middle + 1 else high = middle
    }
    return buildList {
      var index = low
      while (index < spans.size && spans[index].start < end) {
        val span = spans[index++]
        val start = maxOf(offset, span.start)
        add(Extent(span.fileId, if (span.fileId == null) 0 else start - span.start,
          minOf(end, span.end) - start))
      }
    }
  }

  /** V1 hybrid requests include zeros up to the next file or the recorded torrent end. */
  fun pieceExtents(index: Long): List<Extent> {
    require(index >= 0 && index < pieceCount)
    val start = index * pieceLength
    return map(start, minOf(pieceLength, protocolBytes - start))
  }

  /** V2 never requests alignment gaps; a short file tail is a short protocol piece. */
  fun v2Piece(index: Long): Extent = pieceExtents(index).single { it.fileId != null }

  companion object {
    fun from(info: TorrentV2Info, hybrid: TorrentHybridLayout? = null): TorrentContentLayout {
      require(hybrid == null || hybrid.identity.v2 == info.hash) { "Hybrid identity mismatch" }
      val files = mutableListOf<File>()
      val spans = mutableListOf<Span>()
      var cursor = 0L
      for ((index, file) in info.files.withIndex()) {
        val legacy = hybrid?.files?.get(index)
        val remainder = cursor % info.pieceLength
        val gap = if (file.length == 0L || remainder == 0L) 0 else info.pieceLength - remainder
        require(cursor <= Long.MAX_VALUE - gap) { "Aligned file offset overflows" }
        val offset = legacy?.offset ?: (cursor + gap)
        val id = (legacy?.v1Index ?: index).toString()
        files += File(id, index, offset, file.length)
        if (file.length == 0L) continue
        require(offset >= cursor && file.length <= Long.MAX_VALUE - offset) { "Layout overflow" }
        if (offset > cursor) spans += Span(cursor, offset, null)
        spans += Span(offset, offset + file.length, id)
        cursor = offset + file.length
      }
      val total = hybrid?.totalV1Bytes ?: cursor
      require(total >= cursor)
      if (total > cursor) spans += Span(cursor, total, null)
      return TorrentContentLayout(info.pieceLength, total, info.totalBytes, files.toList(),
        spans.toList())
    }
  }
}
