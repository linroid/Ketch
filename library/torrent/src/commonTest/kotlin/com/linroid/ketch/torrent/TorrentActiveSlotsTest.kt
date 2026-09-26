package com.linroid.ketch.torrent

import com.linroid.ketch.api.DownloadPriority
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TorrentActiveSlotsTest {
  @Test
  fun acquire_whenFull_waitsAndReleaseHandsSlotToWaitersInArrivalOrder() = runTest {
    val slots = TorrentActiveSlots(1)
    val order = mutableListOf<String>()
    assertNull(slots.acquire())
    val first = async { slots.acquire().also { order += "first" } }
    val second = async { slots.acquire().also { order += "second" } }
    runCurrent()
    assertFalse(first.isCompleted)
    assertFalse(second.isCompleted)

    slots.release()
    runCurrent()
    assertEquals(listOf("first"), order)
    assertFalse(second.isCompleted)
    slots.release()
    runCurrent()
    assertEquals(listOf("first", "second"), order)
  }

  @Test
  fun acquire_higherPriorityWaiterGoesFirst_samePriorityKeepsArrivalOrder() = runTest {
    val slots = TorrentActiveSlots(1)
    val order = mutableListOf<String>()
    slots.acquire()
    for ((name, priority) in listOf("low" to DownloadPriority.LOW,
      "normal-1" to DownloadPriority.NORMAL, "high" to DownloadPriority.HIGH,
      "normal-2" to DownloadPriority.NORMAL)) {
      async { slots.acquire(priority); order += name; slots.release() }
      runCurrent()
    }

    slots.release()
    runCurrent()
    assertEquals(listOf("high", "normal-1", "normal-2", "low"), order)
  }

  @Test
  fun acquire_cancelledWaiterLeavesQueueWithoutTakingASlot() = runTest {
    val slots = TorrentActiveSlots(1)
    slots.acquire()
    var waited = 0
    val cancelled = async { slots.acquire(onWait = { waited++ }) }
    val next = async { slots.acquire() }
    runCurrent()
    assertEquals(1, waited)

    cancelled.cancel()
    runCurrent()
    slots.release()
    runCurrent()
    assertTrue(next.isCompleted)
    // The cancelled waiter consumed nothing: releasing the only slot makes it free again.
    slots.release()
    assertNull(slots.acquire())
  }

  @Test
  fun acquire_whenFull_takesOldestSeederSlotBeforeWaiting() = runTest {
    val slots = TorrentActiveSlots(2)
    slots.acquire()
    slots.acquire()
    assertTrue(slots.lend("seed-old"))
    assertTrue(slots.lend("seed-new"))

    assertEquals("seed-old", slots.acquire())
    // A seeder whose slot was taken can no longer be reclaimed by a task removal.
    assertFalse(slots.reclaim("seed-old"))
    assertTrue(slots.reclaim("seed-new"))
    slots.release()
    assertNull(slots.acquire())
  }

  @Test
  fun lend_whileDownloadWaits_refusesSoTheSlotGoesToTheWaiter() = runTest {
    val slots = TorrentActiveSlots(1)
    slots.acquire()
    val waiter = async { slots.acquire() }
    runCurrent()

    assertFalse(slots.lend("finished"))
    slots.release()
    runCurrent()
    assertTrue(waiter.isCompleted)
    assertNull(waiter.await())
  }

  @Test
  fun close_failsWaitingAndLaterAcquisitions() = runTest {
    val slots = TorrentActiveSlots(1)
    slots.acquire()
    val waiter = async { runCatching { slots.acquire() } }
    runCurrent()

    slots.close()
    runCurrent()
    assertTrue(waiter.await().exceptionOrNull() is IllegalStateException)
    assertFailsWith<IllegalStateException> { slots.acquire() }
  }
}
