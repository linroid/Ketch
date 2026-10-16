package com.linroid.ketch.torrent.utp

import okio.Buffer

/** uTP packet types (BEP 29). */
internal enum class UtpType(val code: Int) {
  DATA(0),
  FIN(1),
  STATE(2),
  RESET(3),
  SYN(4),
  ;

  companion object {
    fun of(code: Int): UtpType? = entries.firstOrNull { it.code == code }
  }
}

/**
 * One uTP packet. Numbers are unsigned on the wire: [connectionId], [seq] and [ack] are 16-bit,
 * [timestamp], [timestampDiff] and [window] 32-bit, kept in the low bits of an Int or Long.
 * [selectiveAck] is the BEP 29 bitmask after [ack]: bit i of byte j acknowledges ack + 2 + 8j + i.
 */
internal class UtpPacket(
  val type: UtpType,
  val connectionId: Int,
  val timestamp: Int,
  val timestampDiff: Int,
  val window: Long,
  val seq: Int,
  val ack: Int,
  val selectiveAck: ByteArray? = null,
  val payload: ByteArray = EMPTY,
) {
  init {
    require(connectionId in 0..0xffff && seq in 0..0xffff && ack in 0..0xffff)
    require(window in 0..0xffffffffL)
    require(selectiveAck == null || selectiveAck.size in 4..252 && selectiveAck.size % 4 == 0)
  }

  fun encode(): ByteArray {
    val out = Buffer()
      .writeByte((type.code shl 4) or VERSION)
      .writeByte(if (selectiveAck != null) EXTENSION_SACK else 0)
      .writeShort(connectionId)
      .writeInt(timestamp)
      .writeInt(timestampDiff)
      .writeInt(window.toInt())
      .writeShort(seq)
      .writeShort(ack)
    if (selectiveAck != null) out.writeByte(0).writeByte(selectiveAck.size).write(selectiveAck)
    return out.write(payload).readByteArray()
  }

  override fun toString(): String =
    "UtpPacket($type id=$connectionId seq=$seq ack=$ack window=$window " +
      "payload=${payload.size}${if (selectiveAck != null) " sack" else ""})"

  companion object {
    const val VERSION = 1
    const val HEADER_SIZE = 20
    private const val EXTENSION_SACK = 1
    private val EMPTY = ByteArray(0)

    /** True when [bytes] start like a uTP packet, the way a shared UDP socket tells them apart. */
    fun looksLikeUtp(bytes: ByteArray): Boolean = bytes.size >= HEADER_SIZE &&
      bytes[0].toInt() and 0x0f == VERSION && UtpType.of((bytes[0].toInt() ushr 4) and 0x0f) != null

    /** Parses one packet; null for anything that is not a valid uTP version 1 packet. */
    fun decode(bytes: ByteArray): UtpPacket? {
      if (!looksLikeUtp(bytes)) return null
      val type = UtpType.of((bytes[0].toInt() ushr 4) and 0x0f) ?: return null
      val input = Buffer().write(bytes)
      input.skip(1)
      var extension = input.readByte().toInt() and 0xff
      val connectionId = input.readShort().toInt() and 0xffff
      val timestamp = input.readInt()
      val timestampDiff = input.readInt()
      val window = input.readInt().toLong() and 0xffffffffL
      val seq = input.readShort().toInt() and 0xffff
      val ack = input.readShort().toInt() and 0xffff
      var selectiveAck: ByteArray? = null
      // Unknown extensions are skipped by their length, as BEP 29 asks.
      while (extension != 0) {
        if (input.size < 2) return null
        val next = input.readByte().toInt() and 0xff
        val length = input.readByte().toInt() and 0xff
        if (input.size < length) return null
        val data = input.readByteArray(length.toLong())
        if (extension == EXTENSION_SACK && length >= 4 && length % 4 == 0) selectiveAck = data
        extension = next
      }
      return UtpPacket(type, connectionId, timestamp, timestampDiff, window, seq, ack,
        selectiveAck, input.readByteArray())
    }
  }
}

/** 16-bit sequence arithmetic: whether [a] comes after [b] within half the space. */
internal fun utpAfter(a: Int, b: Int): Boolean = ((a - b) and 0xffff).let { it != 0 && it < 0x8000 }

/** [a] + [delta] in 16-bit sequence space. */
internal fun utpSeq(a: Int, delta: Int = 1): Int = (a + delta) and 0xffff
