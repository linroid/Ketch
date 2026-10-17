package com.linroid.ketch.core.media

import okio.Buffer

/*
 * Merging separate video and audio tracks of fragmented MP4 (ISO/IEC 14496-12) into one file
 * without decoding them: one initialization segment holding both tracks, then each track's
 * fragments with their track ID and sequence number rewritten. Box sizes never change, so the
 * offsets inside each fragment stay valid.
 */

/** A box within a byte array: [start] of its header, [payload] after it, [end] after it all. */
internal class Mp4Box(val type: String, val start: Int, val payload: Int, val end: Int)

/** The boxes in [bytes] between [from] and [to], which they must fill exactly. */
internal fun mp4Boxes(bytes: ByteArray, from: Int = 0, to: Int = bytes.size): List<Mp4Box> {
  val boxes = mutableListOf<Mp4Box>()
  var at = from
  while (at < to) {
    require(to - at >= 8) { "Truncated MP4 box" }
    val size32 = readUInt32(bytes, at)
    val type = boxType(bytes, at + 4)
    val (size, header) = when (size32) {
      0L -> (to - at).toLong() to 8
      1L -> {
        require(to - at >= 16) { "Truncated MP4 box" }
        readInt64(bytes, at + 8) to 16
      }
      else -> size32 to 8
    }
    require(size >= header && size <= to - at) { "Invalid MP4 box size" }
    boxes += Mp4Box(type, at, at + header, at + size.toInt())
    at += size.toInt()
    require(boxes.size <= 10_000) { "Too many MP4 boxes" }
  }
  return boxes
}

/**
 * One initialization segment holding [video] as track 1 and [audio] as track 2, each of which
 * must hold one track. Durations of the audio track are rescaled to the video's movie timescale.
 *
 * @throws IllegalArgumentException when either is not a fragmented MP4 initialization segment
 *   of one unencrypted track
 */
internal fun mergeInitSegments(video: ByteArray, audio: ByteArray): ByteArray {
  val first = InitSegment.parse(video)
  val second = InitSegment.parse(audio)
  val mvhd = first.mvhd.copyOf()
  writeUInt32(mvhd, mvhd.size - 4, 3)
  val trex = listOf(first.trex.copyOf(), second.trex.copyOf())
  trex.forEachIndexed { index, box -> writeUInt32(box, 12, index + 1L) }
  val mvex = box("mvex", listOfNotNull(first.mehd) + trex)
  val moov = box("moov", listOf(
    mvhd,
    first.trak(trackId = 1, timescale = first.timescale),
    second.trak(trackId = 2, timescale = first.timescale),
    mvex,
  ))
  val out = Buffer()
  (first.ftyp ?: second.ftyp)?.let { out.write(it) }
  out.write(moov)
  return out.readByteArray()
}

private class InitSegment(
  val bytes: ByteArray,
  val ftyp: ByteArray?,
  val mvhd: ByteArray,
  val timescale: Long,
  val trakBox: Mp4Box,
  val mehd: ByteArray?,
  val trex: ByteArray,
) {
  /** The track as [trackId], its durations moved from this movie's timescale to [timescale]. */
  fun trak(trackId: Long, timescale: Long): ByteArray {
    val trak = bytes.copyOfRange(trakBox.start, trakBox.end)
    val offset = trakBox.start
    fun scale(value: Long): Long =
      if (timescale == this.timescale || value <= 0) value
      else (value.toDouble() * timescale / this.timescale).toLong()
    for (child in mp4Boxes(bytes, trakBox.payload, trakBox.end)) {
      when (child.type) {
        "tkhd" -> {
          val at = child.payload - offset
          val v1 = trak[at].toInt() == 1
          writeUInt32(trak, at + if (v1) 20 else 12, trackId)
          val durationAt = at + if (v1) 28 else 20
          if (v1) writeInt64(trak, durationAt, scale(readInt64(trak, durationAt)))
          else {
            val duration = readUInt32(trak, durationAt)
            if (duration != 0xffffffffL) writeUInt32(trak, durationAt, scale(duration))
          }
        }
        "edts" -> for (elst in mp4Boxes(bytes, child.payload, child.end)) {
          if (elst.type != "elst") continue
          val at = elst.payload - offset
          val v1 = trak[at].toInt() == 1
          val entries = readUInt32(trak, at + 4)
          val entrySize = if (v1) 20 else 12
          require(entries * entrySize <= elst.end - elst.payload - 8) { "Invalid MP4 edit list" }
          for (i in 0 until entries.toInt()) {
            val entry = at + 8 + i * entrySize
            if (v1) writeInt64(trak, entry, scale(readInt64(trak, entry)))
            else writeUInt32(trak, entry, scale(readUInt32(trak, entry)))
          }
        }
      }
    }
    return trak
  }

  companion object {
    fun parse(bytes: ByteArray): InitSegment {
      val top = mp4Boxes(bytes)
      val moov = top.singleOrNull { it.type == "moov" }
      require(moov != null) { "MP4 initialization segment has no movie box" }
      val ftyp = top.firstOrNull { it.type == "ftyp" }
      val children = mp4Boxes(bytes, moov!!.payload, moov.end)
      require(children.none { it.type == "pssh" }) { "Encrypted media is not supported" }
      val mvhd = children.singleOrNull { it.type == "mvhd" }
      val trak = children.filter { it.type == "trak" }
      val mvex = children.singleOrNull { it.type == "mvex" }
      require(mvhd != null && mvhd.end - mvhd.payload >= 100) { "Invalid MP4 movie header" }
      require(trak.size == 1) { "Each separate MP4 track must hold exactly one track" }
      require(mvex != null) { "Separate MP4 tracks must be fragmented" }
      val v1 = bytes[mvhd!!.payload].toInt() == 1
      val timescale = readUInt32(bytes, mvhd.payload + if (v1) 20 else 12)
      require(timescale > 0) { "Invalid MP4 timescale" }
      val extends = mp4Boxes(bytes, mvex!!.payload, mvex.end)
      val trex = extends.filter { it.type == "trex" }
      require(trex.size == 1 && trex.single().end - trex.single().payload >= 24) {
        "Invalid MP4 track defaults"
      }
      return InitSegment(
        bytes = bytes,
        ftyp = ftyp?.let { bytes.copyOfRange(it.start, it.end) },
        mvhd = bytes.copyOfRange(mvhd.start, mvhd.end),
        timescale = timescale,
        trakBox = trak.single(),
        mehd = extends.firstOrNull { it.type == "mehd" }
          ?.let { bytes.copyOfRange(it.start, it.end) },
        trex = bytes.copyOfRange(trex.single().start, trex.single().end),
      )
    }
  }
}

/**
 * Rewrites one media segment of a separate track as the bytes arrive: keeps its movie fragments
 * (`moof`), their media data (`mdat`) and any box between the two, as sample offsets relative to
 * the fragment count those, drops every other box (`styp`, `sidx`, `emsg`, ...), and gives each
 * fragment [trackId] and the next sequence number from [nextSequence]. A fragment
 * that names an absolute base data offset gets one in the output file.
 *
 * @param sourceStart where the segment's first byte lies in the file it was fetched from
 * @param outputStart where its first output byte lands in the output file
 */
internal class FragmentRewriter(
  private val trackId: Long,
  private val nextSequence: () -> Long,
  private val sourceStart: Long,
  private val outputStart: Long,
) {
  private val header = ByteArray(16)
  private var headerSize = 0
  private var mode = Mode.Header
  private var remaining = 0L
  private var consumed = 0L
  private var emitted = 0L
  private var boxStart = 0L
  private var moof: Buffer = Buffer()
  private var fragments = 0
  // Between a fragment and its media data, where every box is kept.
  private var awaitingData = false

  private enum class Mode { Header, Fragment, Data, Skip }

  /** The output [data] completes, possibly empty. */
  fun update(data: ByteArray): ByteArray {
    val out = Buffer()
    var at = 0
    while (at < data.size) {
      when (mode) {
        Mode.Header -> {
          if (headerSize == 0) boxStart = consumed
          val need = if (headerSize >= 8 && readUInt32(header, 0) == 1L) 16 else 8
          val take = minOf(need - headerSize, data.size - at)
          data.copyInto(header, headerSize, at, at + take)
          headerSize += take
          at += take
          consumed += take
          if (headerSize == 8 && readUInt32(header, 0) == 1L) continue
          if (headerSize == need) startBox(out)
        }
        Mode.Fragment, Mode.Data, Mode.Skip -> {
          val take = minOf(remaining, (data.size - at).toLong()).toInt()
          when (mode) {
            Mode.Fragment -> moof.write(data, at, take)
            Mode.Data -> {
              out.write(data, at, take)
              emitted += take
            }
            else -> Unit
          }
          at += take
          consumed += take
          remaining -= take
          if (remaining == 0L) {
            if (mode == Mode.Fragment) emitFragment(out)
            mode = Mode.Header
          }
        }
      }
    }
    return out.readByteArray()
  }

  /**
   * Checks that the segment ended with a whole box and held media.
   *
   * @throws IllegalArgumentException otherwise
   */
  fun finish() {
    val open = mode == Mode.Data && remaining == Long.MAX_VALUE
    require((mode == Mode.Header && headerSize == 0) || open) { "Truncated MP4 fragment" }
    require(fragments > 0) { "MP4 segment holds no movie fragment" }
  }

  private fun startBox(out: Buffer) {
    val size32 = readUInt32(header, 0)
    val type = boxType(header, 4)
    val headerLength = headerSize
    headerSize = 0
    val size = when (size32) {
      0L -> -1L
      1L -> readInt64(header, 8)
      else -> size32
    }
    require(size == -1L || size >= headerLength) { "Invalid MP4 box size" }
    when (type) {
      "moof" -> {
        require(size > 0 && size <= MAX_FRAGMENT_HEADER) { "MP4 fragment header is too large" }
        moof = Buffer().apply { write(header, 0, headerLength) }
        remaining = size - headerLength
        mode = Mode.Fragment
        if (remaining == 0L) emitFragment(out).also { mode = Mode.Header }
      }
      "mdat" -> {
        awaitingData = false
        out.write(header, 0, headerLength)
        emitted += headerLength
        remaining = if (size == -1L) Long.MAX_VALUE else size - headerLength
        mode = if (remaining == 0L) Mode.Header else Mode.Data
      }
      else -> {
        require(size != -1L) { "Invalid MP4 box size" }
        remaining = size - headerLength
        if (awaitingData) {
          out.write(header, 0, headerLength)
          emitted += headerLength
        }
        mode = when {
          remaining == 0L -> Mode.Header
          awaitingData -> Mode.Data
          else -> Mode.Skip
        }
      }
    }
  }

  private fun emitFragment(out: Buffer) {
    val bytes = moof.readByteArray()
    val children = mp4Boxes(bytes, if (readUInt32(bytes, 0) == 1L) 16 else 8)
    val mfhd = children.singleOrNull { it.type == "mfhd" }
    require(mfhd != null && mfhd.end - mfhd.payload >= 8) { "Invalid MP4 fragment header" }
    writeUInt32(bytes, mfhd!!.payload + 4, nextSequence())
    val trafs = children.filter { it.type == "traf" }
    require(trafs.size == 1) { "Each separate MP4 fragment must hold exactly one track" }
    val tfhd = mp4Boxes(bytes, trafs.single().payload, trafs.single().end)
      .singleOrNull { it.type == "tfhd" }
    require(tfhd != null && tfhd.end - tfhd.payload >= 8) { "Invalid MP4 track fragment" }
    writeUInt32(bytes, tfhd!!.payload + 4, trackId)
    val flags = readUInt32(bytes, tfhd.payload) and 0xffffff
    if (flags and BASE_DATA_OFFSET != 0L) {
      require(tfhd.end - tfhd.payload >= 16) { "Invalid MP4 track fragment" }
      val base = readInt64(bytes, tfhd.payload + 8)
      val moved = base - (sourceStart + boxStart) + (outputStart + emitted)
      require(moved >= 0) { "Invalid MP4 base data offset" }
      writeInt64(bytes, tfhd.payload + 8, moved)
    }
    out.write(bytes)
    emitted += bytes.size
    fragments++
    awaitingData = true
  }

  private companion object {
    const val BASE_DATA_OFFSET = 0x1L
    const val MAX_FRAGMENT_HEADER = 16L * 1024 * 1024
  }
}

private fun box(type: String, children: List<ByteArray>): ByteArray {
  val size = 8L + children.sumOf { it.size.toLong() }
  require(size <= Int.MAX_VALUE) { "MP4 box is too large" }
  val out = Buffer()
  out.writeInt(size.toInt())
  out.writeUtf8(type)
  children.forEach { out.write(it) }
  return out.readByteArray()
}

private fun boxType(bytes: ByteArray, at: Int): String =
  CharArray(4) { (bytes[at + it].toInt() and 0xff).toChar() }.concatToString()

private fun readUInt32(bytes: ByteArray, at: Int): Long {
  require(at >= 0 && at + 4 <= bytes.size) { "Truncated MP4 box" }
  return ((bytes[at].toLong() and 0xff) shl 24) or ((bytes[at + 1].toLong() and 0xff) shl 16) or
    ((bytes[at + 2].toLong() and 0xff) shl 8) or (bytes[at + 3].toLong() and 0xff)
}

private fun readInt64(bytes: ByteArray, at: Int): Long =
  (readUInt32(bytes, at) shl 32) or readUInt32(bytes, at + 4)

private fun writeUInt32(bytes: ByteArray, at: Int, value: Long) {
  require(at >= 0 && at + 4 <= bytes.size && value in 0..0xffffffffL) { "Invalid MP4 value" }
  for (i in 0 until 4) bytes[at + i] = (value ushr (24 - 8 * i)).toByte()
}

private fun writeInt64(bytes: ByteArray, at: Int, value: Long) {
  require(at >= 0 && at + 8 <= bytes.size) { "Invalid MP4 value" }
  for (i in 0 until 8) bytes[at + i] = (value ushr (56 - 8 * i)).toByte()
}
