package com.linroid.ketch.torrent

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TorrentV2RarityPickerTest {
  @Test
  fun choosesRarestEligiblePiecesAndRotatesEqualRarityAcrossSelections() {
    val budget = TorrentBufferBudget(4096)
    val picker = assertNotNull(TorrentV2RarityPicker.create<String>(3, budget, maxPeers = 3))
    try {
      assertTrue(picker.update("a", byteArrayOf(224.toByte())))
      assertTrue(picker.update("b", byteArrayOf(192.toByte())))
      assertEquals(2, picker.pick("a") { true })
      assertEquals(0, picker.pick("a") { it != 2 })
      assertEquals(1, picker.pick("a") { it != 2 })
      assertNull(picker.pick("a") { false })
      assertNull(picker.pick("unknown") { true })
      assertTrue(picker.have("b", 2))
      assertTrue(picker.have("b", 2))
      assertEquals(2, picker.pick("a") { true })
      assertEquals(0, picker.pick("a") { true })
    } finally { picker.close() }
    assertEquals(0, budget.allocated)
  }

  @Test
  fun replacementAndRemovalUpdateRarityWithoutAliasingInputsOrCountingDuplicateHaves() {
    val budget = TorrentBufferBudget(4096)
    val picker = assertNotNull(TorrentV2RarityPicker.create<String>(2, budget, maxPeers = 3))
    try {
      val bits = byteArrayOf(192.toByte())
      assertTrue(picker.update("a", bits))
      bits[0] = 0
      assertTrue(picker.have("b", 0))
      assertTrue(picker.have("b", 0))
      assertEquals(1, picker.pick("a") { true })
      assertTrue(picker.update("b", byteArrayOf(64)))
      assertEquals(0, picker.pick("a") { true })
      picker.remove("b")
      assertEquals(1, picker.pick("a") { true })
      picker.remove("b")
      assertEquals(0, picker.pick("a") { true })
    } finally { picker.close() }
    assertEquals(0, budget.allocated)
  }

  @Test
  fun indexAndPeerSnapshotsMustBeAdmittedAndMalformedSnapshotsDoNotChangeState() {
    assertNull(TorrentV2RarityPicker.create<Int>(1_000_000, TorrentBufferBudget(4096)))
    val budget = TorrentBufferBudget(2048)
    val picker = assertNotNull(TorrentV2RarityPicker.create<Int>(9, budget, maxPeers = 1))
    try {
      val reserved = assertNotNull(budget.reserve(budget.capacity - budget.allocated))
      assertFalse(picker.update(1, byteArrayOf(128.toByte(), 0)))
      assertNull(picker.pick(1) { true })
      reserved.close()
      assertTrue(picker.update(1, byteArrayOf(128.toByte(), 0)))
      val retained = budget.allocated
      assertFalse(picker.have(2, 8))
      assertFailsWith<IllegalArgumentException> { picker.update(1, byteArrayOf(0, 1)) }
      assertEquals(retained, budget.allocated)
      assertEquals(0, picker.pick(1) { true })
      picker.remove(1)
      assertTrue(picker.have(2, 8))
      assertEquals(8, picker.pick(2) { true })
    } finally { picker.close() }
    assertEquals(0, budget.allocated)
  }

  @Test
  fun handlesEmptyAndMillionPieceIndexesWithoutCandidateLists() {
    val emptyBudget = TorrentBufferBudget(1024)
    val empty = assertNotNull(TorrentV2RarityPicker.create<Int>(0, emptyBudget, maxPeers = 1))
    assertTrue(empty.update(1, ByteArray(0)))
    assertNull(empty.pick(1) { true })
    empty.close()
    assertEquals(0, emptyBudget.allocated)
    val budget = TorrentBufferBudget(5_000_000)
    val picker = assertNotNull(TorrentV2RarityPicker.create<Int>(1_000_000, budget, maxPeers = 1))
    try {
      assertTrue(picker.have(1, 999_999))
      assertEquals(999_999, picker.pick(1) { true })
      assertNull(picker.pick(1) { it != 999_999 })
    } finally { picker.close() }
    assertEquals(0, budget.allocated)
  }
}
