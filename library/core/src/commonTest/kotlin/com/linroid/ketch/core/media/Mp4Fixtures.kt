package com.linroid.ketch.core.media

import okio.Buffer

/** Minimal fragmented MP4 boxes, enough for the merger to read and rewrite. */
internal object Mp4Fixtures {
  fun box(type: String, vararg children: ByteArray): ByteArray {
    val out = Buffer()
    out.writeInt(8 + children.sumOf { it.size })
    out.writeUtf8(type)
    children.forEach { out.write(it) }
    return out.readByteArray()
  }

  fun full(type: String, flags: Int, payload: Buffer.() -> Unit): ByteArray =
    box(type, Buffer().apply { writeInt(flags) }.apply(payload).readByteArray())

  fun init(trackId: Int, timescale: Int, duration: Int, editDuration: Int): ByteArray {
    val mvhd = full("mvhd", 0) {
      writeInt(0); writeInt(0); writeInt(timescale); writeInt(duration)
      write(ByteArray(76))
      writeInt(trackId + 1)
    }
    val tkhd = full("tkhd", 7) {
      writeInt(0); writeInt(0); writeInt(trackId); writeInt(0); writeInt(duration)
      write(ByteArray(60))
    }
    val elst = full("elst", 0) {
      writeInt(1); writeInt(editDuration); writeInt(0); writeInt(0x10000)
    }
    val mdia = box("mdia", "media-$trackId".encodeToByteArray())
    val trak = box("trak", tkhd, box("edts", elst), mdia)
    val trex = full("trex", 0) {
      writeInt(trackId); writeInt(1); writeInt(0); writeInt(0); writeInt(0)
    }
    return box("ftyp", "iso6mp41".encodeToByteArray()) +
      box("moov", mvhd, trak, box("mvex", trex))
  }

  /**
   * A media segment: `styp`, `sidx`, then one fragment whose `tfhd` names [trackId] and, when
   * given, the absolute [baseDataOffset], then [beforeData] and its `mdat` of [payload].
   */
  fun segment(
    trackId: Int,
    sequence: Int,
    payload: String,
    baseDataOffset: Long? = null,
    beforeData: ByteArray = ByteArray(0),
  ): ByteArray {
    val tfhd = full("tfhd", if (baseDataOffset != null) 0x1 else 0x020000) {
      writeInt(trackId)
      baseDataOffset?.let { writeLong(it) }
    }
    val moof = box("moof", full("mfhd", 0) { writeInt(sequence) },
      box("traf", tfhd, full("trun", 0) { writeInt(0) }))
    return box("styp", "msdh".encodeToByteArray()) + box("sidx", ByteArray(12)) + moof +
      beforeData + box("mdat", payload.encodeToByteArray())
  }
}
