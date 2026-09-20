package com.linroid.ketch.torrent

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TrackerConfigurationTest {
  @Test
  fun admissionSnapshotsInputAndReturnsCreditOnlyFromTheCurrentOwner() {
    val budget = TorrentBufferBudget(100_000)
    val tier = mutableListOf("https://tracker/announce?key=secret")
    val input = mutableListOf<List<String>>(tier)
    val original = assertNotNull(TrackerConfiguration.admit(input, budget))
    val reserved = budget.allocated
    assertTrue(reserved > 0)
    tier.clear()
    input.clear()
    val moved = original.transfer()
    original.close()
    assertEquals(reserved, budget.allocated)
    assertEquals(listOf(listOf("https://tracker/announce?key=secret")), moved.configuration.tiers)
    assertFailsWith<IllegalStateException> { original.configuration }
    assertFailsWith<IllegalStateException> { original.transfer() }
    moved.close()
    moved.close()
    assertEquals(0, budget.allocated)
    assertFailsWith<IllegalStateException> { moved.configuration }
  }

  @Test
  fun insufficientBudgetRejectsBeforeParsingAndDoesNotConsumeOtherReservations() {
    val budget = TorrentBufferBudget(5000)
    val held = assertNotNull(budget.reserve(1000))
    assertNull(TrackerConfiguration.admit(listOf(listOf("not-a-url")), budget))
    assertEquals(1000, budget.allocated)
    held.close()
    assertEquals(0, budget.allocated)
  }

  @Test
  fun validationFailureReleasesTheReservationAndAllowsTheNextConfiguration() {
    val budget = TorrentBufferBudget(100_000)
    assertFailsWith<IllegalArgumentException> {
      TrackerConfiguration.admit(listOf(listOf("file:///secret")), budget)
    }
    assertEquals(0, budget.allocated)
    val valid = assertNotNull(TrackerConfiguration.admit(
      listOf(listOf("https://t/announce")), budget))
    valid.close()
    assertEquals(0, budget.allocated)
  }

  @Test
  fun replacementCanBeAdmittedBeforeTheOldOwnerIsReleased() {
    val budget = TorrentBufferBudget(20_000)
    val old = assertNotNull(TrackerConfiguration.admit(
      listOf(listOf("https://old/announce")), budget))
    val oldBytes = budget.allocated
    val replacement = assertNotNull(TrackerConfiguration.admit(
      listOf(listOf("https://new/announce")), budget))
    assertTrue(budget.allocated > oldBytes)
    replacement.close() // A failed install keeps the old owner intact.
    assertEquals(oldBytes, budget.allocated)
    assertEquals("https://old/announce", old.configuration.tiers.single().single())
    old.close()
    assertEquals(0, budget.allocated)
  }

  @Test
  fun boundedWorstCaseAccountingDoesNotOverflowOrTruncate() {
    val budget = TorrentBufferBudget(8 * 1024 * 1024)
    val url = "https://tracker/" + "a".repeat(8192 - "https://tracker/".length)
    val owned = assertNotNull(TrackerConfiguration.admit(List(256) { listOf(url) }, budget))
    assertTrue(budget.allocated > 4 * 1024 * 1024)
    owned.close()
    assertEquals(0, budget.allocated)
    assertFailsWith<IllegalArgumentException> {
      TrackerConfiguration.admit(List(257) { listOf(url) }, budget)
    }
    assertEquals(0, budget.allocated)
  }
}
