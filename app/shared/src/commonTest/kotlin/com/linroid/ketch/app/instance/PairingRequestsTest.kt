package com.linroid.ketch.app.instance

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class PairingRequestsTest {

  @Test
  fun ask_waitsForTheAnswerOfItsOwnRequest() = runTest {
    val requests = PairingRequests()
    val pixel = async { requests.ask("Pixel 9", "4821", "Android 16", "192.168.1.30") }
    val ipad = async { requests.ask("iPad", "1907", null, "192.168.1.31") }
    runCurrent()
    val (first, second) = requests.pending.value
    assertEquals(listOf("Pixel 9", "iPad"), requests.pending.value.map { it.name })

    requests.answer(second.id, allow = false)
    requests.answer(first.id, allow = true)

    assertTrue(pixel.await())
    assertFalse(ipad.await())
    assertEquals(emptyList(), requests.pending.value)
  }

  @Test
  fun ask_cancelled_takesTheRequestBack() = runTest {
    val requests = PairingRequests()
    val asking = launch { requests.ask("Pixel 9", "4821", null, "192.168.1.30") }
    runCurrent()

    asking.cancel()
    runCurrent()

    assertEquals(emptyList(), requests.pending.value)
  }

  @Test
  fun open_takesRequestsForThreeMinutes() {
    val clock = MutableClock()
    val requests = PairingRequests(clock)
    assertFalse(requests.isOpen)

    requests.open()
    assertTrue(requests.isOpen)
    assertEquals(clock.now + 3.minutes, requests.openUntil.value)

    clock.now += 3.minutes
    assertFalse(requests.isOpen)
    requests.closeIfEnded()
    assertNull(requests.openUntil.value)
  }

  @Test
  fun close_stopsTakingRequests() {
    val requests = PairingRequests(MutableClock())
    requests.open()

    requests.close()

    assertFalse(requests.isOpen)
    assertNull(requests.openUntil.value)
  }

  @Test
  fun answer_allowed_closesTheWindow() = runTest {
    val requests = PairingRequests(MutableClock())
    requests.open()
    val asking = async { requests.ask("Pixel 9", "4821", null, "192.168.1.30") }
    runCurrent()

    requests.answer(requests.pending.value.single().id, allow = true)

    assertTrue(asking.await())
    assertFalse(requests.isOpen)
  }

  @Test
  fun answer_allowed_turnsAwayTheOtherWaitingRequests() = runTest {
    val requests = PairingRequests(MutableClock())
    requests.open()
    val pixel = async { requests.ask("Pixel 9", "4821", null, "192.168.1.30") }
    val ipad = async { requests.ask("iPad", "1907", null, "192.168.1.31") }
    runCurrent()
    val (first, second) = requests.pending.value

    requests.answer(first.id, allow = true)
    // A device asking after the first was let in gets no token, even if the owner allows it.
    requests.answer(second.id, allow = true)

    assertTrue(pixel.await())
    assertFalse(ipad.await())
    assertEquals(emptyList(), requests.pending.value)
  }

  @Test
  fun answer_denied_keepsTheWindowOpen() = runTest {
    val requests = PairingRequests(MutableClock())
    requests.open()
    val asking = async { requests.ask("Pixel 9", "4821", null, "192.168.1.30") }
    runCurrent()

    requests.answer(requests.pending.value.single().id, allow = false)

    assertFalse(asking.await())
    assertTrue(requests.isOpen)
  }

  private class MutableClock(var now: Instant = Instant.fromEpochSeconds(1_800_000_000)) : Clock {
    override fun now(): Instant = now
  }
}
