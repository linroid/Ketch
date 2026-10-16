package com.linroid.ketch.app.feedback

import com.linroid.ketch.app.instance.PairingRequests
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class PairingNoticesTest {
  private val requests = PairingRequests()
  private val inFront = MutableStateFlow(false)
  private val posted = mutableListOf<String>()
  private val withdrawn = mutableListOf<Long>()

  private fun TestScope.follow() {
    backgroundScope.launch {
      PairingNotices.follow(requests, inFront, { posted += it.name }, { withdrawn += it })
    }
  }

  private fun TestScope.ask(name: String) {
    backgroundScope.launch { requests.ask(name, "4821", null, "192.168.1.30") }
  }

  @Test
  fun requestWhileAway_isPostedOnce() = runTest {
    follow()
    ask("Pixel 9")
    runCurrent()
    inFront.value = true
    runCurrent()
    inFront.value = false
    runCurrent()

    assertEquals(listOf("Pixel 9"), posted)
  }

  @Test
  fun requestWhileInFront_isPostedOnceTheAppLeaves() = runTest {
    inFront.value = true
    follow()
    ask("Pixel 9")
    runCurrent()
    assertEquals(emptyList(), posted)

    inFront.value = false
    runCurrent()

    assertEquals(listOf("Pixel 9"), posted)
  }

  @Test
  fun answeredRequest_isWithdrawn() = runTest {
    follow()
    ask("Pixel 9")
    runCurrent()
    val ask = requests.pending.value.single()

    requests.answer(ask.id, allow = false)
    runCurrent()

    assertEquals(listOf(ask.id), withdrawn)
  }

  @Test
  fun requestAnsweredInFront_isNeverPosted() = runTest {
    inFront.value = true
    follow()
    ask("Pixel 9")
    runCurrent()

    requests.answer(requests.pending.value.single().id, allow = true)
    inFront.value = false
    runCurrent()

    assertEquals(emptyList(), posted)
    assertEquals(emptyList(), withdrawn)
  }
}
