package com.linroid.ketch.dash

import com.linroid.ketch.api.KetchError
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SegmentIndexTest {
  @Test
  fun version0_listsFragmentsAfterTheBox() {
    // 10 bytes of initialization before the index, which is 56 bytes long.
    val index = sidx(version = 0, timescale = 1000, earliest = 500, firstOffset = 0,
      references = listOf(100L to 2000L, 200L to 3000L))
    val fragments = parseSegmentIndex(index, start = 10)

    assertEquals(listOf(66L..165L, 166L..365L), fragments.map { it.range })
    assertEquals(listOf(0.5, 2.5), fragments.map { it.startSeconds })
  }

  @Test
  fun version1_readsSixtyFourBitTimesAndTheFirstOffset() {
    val index = sidx(version = 1, timescale = 90_000, earliest = 0, firstOffset = 8,
      references = listOf(1000L to 180_000L))
    val fragments = parseSegmentIndex(index, start = 0)

    assertEquals(listOf((index.size + 8L) until index.size + 1008L), fragments.map { it.range })
    assertEquals(listOf(0.0), fragments.map { it.startSeconds })
  }

  @Test
  fun boxesBeforeTheIndex_areSkipped() {
    val free = box("free", ByteArray(4))
    val index = sidx(version = 0, timescale = 1, earliest = 0, firstOffset = 0,
      references = listOf(5L to 1L))
    val fragments = parseSegmentIndex(free + index, start = 100)

    assertEquals(listOf((100L + free.size + index.size).let { it until it + 5 }),
      fragments.map { it.range })
  }

  @Test
  fun nestedOrTruncatedIndexes_areRefused() {
    val nested = sidx(version = 0, timescale = 1, earliest = 0, firstOffset = 0,
      references = listOf((1L shl 31) or 5L to 1L))
    assertFailsWith<KetchError.SourceError> { parseSegmentIndex(nested, start = 0) }
    val whole = sidx(version = 0, timescale = 1, earliest = 0, firstOffset = 0,
      references = listOf(5L to 1L))
    assertFailsWith<KetchError.SourceError> {
      parseSegmentIndex(whole.copyOf(whole.size - 4), start = 0)
    }
    assertFailsWith<KetchError.SourceError> { parseSegmentIndex(box("free", ByteArray(0)), 0) }
  }

  private fun sidx(
    version: Int,
    timescale: Long,
    earliest: Long,
    firstOffset: Long,
    references: List<Pair<Long, Long>>,
  ): ByteArray {
    val body = mutableListOf<Byte>()
    fun u32(value: Long) = (3 downTo 0).forEach { body += (value ushr (it * 8)).toByte() }
    fun u64(value: Long) = (7 downTo 0).forEach { body += (value ushr (it * 8)).toByte() }
    u32(version.toLong() shl 24)
    u32(1)
    u32(timescale)
    if (version == 0) {
      u32(earliest)
      u32(firstOffset)
    } else {
      u64(earliest)
      u64(firstOffset)
    }
    u32(references.size.toLong())
    for ((reference, duration) in references) {
      u32(reference)
      u32(duration)
      u32(0x90000000)
    }
    return box("sidx", body.toByteArray())
  }

  private fun box(type: String, body: ByteArray): ByteArray {
    val size = body.size + 8
    return byteArrayOf(
      (size ushr 24).toByte(), (size ushr 16).toByte(), (size ushr 8).toByte(), size.toByte(),
    ) + type.encodeToByteArray() + body
  }
}
