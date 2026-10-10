package com.linroid.ketch.torrent

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TorrentChokerTest {
  private var now = 0L
  private val choker = TorrentChoker<String>({ now })

  private fun interested(vararg peers: String) = peers.forEach {
    choker.add(it)
    choker.interested(it, true)
  }

  private fun unchoked(vararg peers: String) = peers.filter(choker::isUnchoked).toSet()

  @Test
  fun disabledChokesEveryone() {
    interested("a", "b")
    choker.update(allowed = true, seeding = false)
    assertEquals(setOf("a", "b"), unchoked("a", "b"))
    val changes = choker.update(allowed = false, seeding = false)
    assertEquals(setOf("a", "b"), changes.choke.toSet())
    assertTrue(unchoked("a", "b").isEmpty())
    // Interest while uploading is off unchokes nobody, and no timer is armed.
    interested("c")
    choker.update(allowed = false, seeding = false)
    assertTrue(unchoked("a", "b", "c").isEmpty())
    assertNull(choker.nextDueMs())
  }

  @Test
  fun freeSlotsFillAtOnce() {
    interested("a", "b", "c", "d", "e")
    val first = choker.update(allowed = true, seeding = false)
    // Four slots: the first four to wait, longest first; the fifth waits.
    assertEquals(listOf("a", "b", "c", "d"), first.unchoke)
    assertFalse(choker.isUnchoked("e"))
    // A slot freed between rechokes goes to the waiting peer without any timer.
    choker.remove("b")
    choker.update(allowed = true, seeding = false)
    assertTrue(choker.isUnchoked("e"))
    assertEquals(0L, now)
    // So does a slot that was free when a peer becomes interested.
    choker.remove("a")
    choker.add("f")
    choker.update(allowed = true, seeding = false)
    assertFalse(choker.isUnchoked("f"))
    choker.interested("f", true)
    choker.update(allowed = true, seeding = false)
    assertTrue(choker.isUnchoked("f"))
  }

  @Test
  fun regularSlotsGoToFastestPeersEveryTenSeconds() {
    interested("a", "b", "c", "d", "e", "f")
    choker.update(allowed = true, seeding = false)
    choker.received("c", 300)
    choker.received("e", 500)
    choker.received("f", 400)
    choker.received("a", 100)
    now = 9_999
    choker.update(allowed = true, seeding = false)
    assertEquals(setOf("a", "b", "c", "d"), unchoked("a", "b", "c", "d", "e", "f"))
    assertEquals(1L, choker.nextDueMs())
    now = 10_000
    choker.update(allowed = true, seeding = false)
    val after = unchoked("a", "b", "c", "d", "e", "f")
    // The three that sent the most hold regular slots; the fourth is optimistic.
    assertTrue(after.containsAll(setOf("e", "f", "c")), "$after")
    assertEquals(4, after.size)
    // Counters reset: the next round ranks only what arrives after this one.
    choker.received("b", 1_000)
    choker.received("d", 900)
    choker.received("a", 800)
    now = 20_000
    choker.update(allowed = true, seeding = false)
    assertTrue(unchoked("a", "b", "c", "d", "e", "f").containsAll(setOf("b", "d", "a")))
  }

  @Test
  fun optimisticSlotRotatesEveryThirtySeconds() {
    interested("a", "b", "c", "d", "e", "f")
    choker.update(allowed = true, seeding = false)
    // a, b and c keep the regular slots throughout; d took the last slot as optimistic.
    fun feed() = listOf("a", "b", "c").forEach { choker.received(it, 1_000) }
    val optimistic = mutableListOf<String>()
    for (round in 1..9) {
      feed()
      now = round * 10_000L
      choker.update(allowed = true, seeding = false)
      val current = unchoked("a", "b", "c", "d", "e", "f")
      assertTrue(current.containsAll(setOf("a", "b", "c")), "$current at $now")
      assertEquals(4, current.size)
      optimistic += (current - setOf("a", "b", "c")).single()
    }
    // Rotations at 30, 60 and 90 s move the slot to the peer choked longest.
    assertEquals(listOf("d", "d", "e", "e", "e", "f", "f", "f", "d"), optimistic)
  }

  @Test
  fun seedingRanksByUploadedBytes() {
    interested("a", "b", "c", "d", "e", "f")
    choker.update(allowed = true, seeding = true)
    choker.received("a", 10_000)
    choker.received("b", 10_000)
    choker.uploaded("d", 300)
    choker.uploaded("e", 500)
    choker.uploaded("f", 400)
    now = 10_000
    choker.update(allowed = true, seeding = true)
    val seeded = unchoked("a", "b", "c", "d", "e", "f")
    assertTrue(seeded.containsAll(setOf("d", "e", "f")), "$seeded")
    // While downloading, the same round would rank what peers sent us instead.
    choker.received("a", 10_000)
    choker.received("b", 10_000)
    choker.received("c", 10_000)
    now = 20_000
    choker.update(allowed = true, seeding = false)
    assertTrue(unchoked("a", "b", "c", "d", "e", "f").containsAll(setOf("a", "b", "c")))
  }

  @Test
  fun uninterestedPeerLosesItsSlotAtOnce() {
    interested("a", "b", "c", "d", "e")
    choker.update(allowed = true, seeding = false)
    assertTrue(choker.isUnchoked("a"))
    choker.interested("a", false)
    val changes = choker.update(allowed = true, seeding = false)
    assertEquals(listOf("a"), changes.choke)
    assertEquals(listOf("e"), changes.unchoke)
    // A peer the session could not serve gives its slot up too, until the next rechoke.
    choker.refused("b")
    choker.update(allowed = true, seeding = false)
    assertFalse(choker.isUnchoked("b"))
    assertEquals(3, choker.unchokedCount)
    now = 10_000
    choker.update(allowed = true, seeding = false)
    assertTrue(choker.isUnchoked("b"))
    choker.remove("c")
    choker.remove("d")
    choker.remove("e")
    choker.remove("b")
    choker.update(allowed = true, seeding = false)
    // Nobody is interested any more: no timer is armed.
    assertNull(choker.nextDueMs())
  }
}
