package com.linroid.ketch.dash

/** A fragment a segment index lists: its bytes in the file and when it starts, in seconds. */
internal class IndexedFragment(val range: LongRange, val startSeconds: Double)

/**
 * The fragments the first `sidx` box (ISO/IEC 14496-12, 8.16.3) of [bytes] lists, [bytes] being
 * what a file holds from byte [start]: their offsets count from the first byte after the box.
 * An index that refers to further indexes, rather than to fragments, is not supported.
 */
internal fun parseSegmentIndex(bytes: ByteArray, start: Long): List<IndexedFragment> {
  var at = 0
  while (true) {
    mediaRequire(at.toLong() + 8 <= bytes.size, "DASH segment index has no sidx box")
    var size = u32(bytes, at)
    var header = 8
    if (size == 1L) {
      mediaRequire(at.toLong() + 16 <= bytes.size, "Invalid DASH segment index")
      size = u64(bytes, at + 8)
      header = 16
    } else if (size == 0L) {
      size = (bytes.size - at).toLong()
    }
    mediaRequire(size >= header && size <= bytes.size - at, "Invalid DASH segment index")
    val type = bytes.copyOfRange(at + 4, at + 8).decodeToString()
    if (type == "sidx") {
      return sidxFragments(bytes, at + header, at + size.toInt(), start + at + size)
    }
    at += size.toInt()
  }
}

/** The fragments of the `sidx` box body at [from] until [end], offset from [anchor]. */
private fun sidxFragments(
  bytes: ByteArray,
  from: Int,
  end: Int,
  anchor: Long,
): List<IndexedFragment> {
  fun need(at: Int, count: Int) = mediaRequire(at + count <= end, "Invalid DASH segment index")
  need(from, 12)
  val version = bytes[from].toInt() and 0xff
  // Version and flags, then the reference ID.
  val timescale = u32(bytes, from + 8)
  mediaRequire(timescale > 0, "Invalid DASH segment index timescale")
  var at = from + 12
  val earliest: Long
  val firstOffset: Long
  if (version == 0) {
    need(at, 8)
    earliest = u32(bytes, at)
    firstOffset = u32(bytes, at + 4)
    at += 8
  } else {
    need(at, 16)
    earliest = u64(bytes, at)
    firstOffset = u64(bytes, at + 8)
    at += 16
  }
  need(at, 4)
  val count = ((bytes[at + 2].toInt() and 0xff) shl 8) or (bytes[at + 3].toInt() and 0xff)
  at += 4
  mediaRequire(count in 1..10_000, "DASH segment index lists no fragments")
  need(at, count * 12)
  mediaRequire(firstOffset >= 0 && earliest >= 0, "Invalid DASH segment index")
  var offset = anchor + firstOffset
  var time = earliest
  return List(count) {
    val reference = u32(bytes, at)
    val duration = u32(bytes, at + 4)
    at += 12
    mediaRequire(reference ushr 31 == 0L, "Nested DASH segment indexes are not supported")
    val size = reference and 0x7fffffff
    mediaRequire(size > 0 && offset <= Long.MAX_VALUE - size && time <= Long.MAX_VALUE - duration,
      "Invalid DASH segment index")
    IndexedFragment(offset until offset + size, time.toDouble() / timescale).also {
      offset += size
      time += duration
    }
  }
}

private fun u32(bytes: ByteArray, at: Int): Long =
  ((bytes[at].toLong() and 0xff) shl 24) or ((bytes[at + 1].toLong() and 0xff) shl 16) or
    ((bytes[at + 2].toLong() and 0xff) shl 8) or (bytes[at + 3].toLong() and 0xff)

private fun u64(bytes: ByteArray, at: Int): Long {
  val value = (u32(bytes, at) shl 32) or u32(bytes, at + 4)
  mediaRequire(value >= 0, "Invalid DASH segment index")
  return value
}
