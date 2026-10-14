package com.linroid.ketch.app.instance

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

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
}
