package com.linroid.ketch.server

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TestTimeSource

class PairingBackoffTest {
  private val time = TestTimeSource()
  private val backoff = PairingBackoff(timeSource = time)

  @Test
  fun `address without strikes may ask`() {
    assertNull(backoff.waitFor("192.168.1.30"))
  }

  @Test
  fun `each strike doubles the wait`() {
    backoff.recordStrike("192.168.1.30")
    assertEquals(30.seconds, backoff.waitFor("192.168.1.30"))

    time += 30.seconds
    assertNull(backoff.waitFor("192.168.1.30"))

    backoff.recordStrike("192.168.1.30")
    assertEquals(1.minutes, backoff.waitFor("192.168.1.30"))
  }

  @Test
  fun `wait stops growing at its maximum`() {
    val unpaused = PairingBackoff(pauseAfter = 100, timeSource = time)
    repeat(20) { unpaused.recordStrike("192.168.1.30") }

    assertEquals(1.hours, unpaused.waitFor("192.168.1.30"))
  }

  @Test
  fun `strikes are forgotten after an hour without one`() {
    repeat(3) { backoff.recordStrike("192.168.1.30") }

    time += 1.hours

    assertNull(backoff.waitFor("192.168.1.30"))
    backoff.recordStrike("192.168.1.30")
    assertEquals(30.seconds, backoff.waitFor("192.168.1.30"))
  }

  @Test
  fun `other addresses are not held back by one address`() {
    backoff.recordStrike("192.168.1.30")

    assertNull(backoff.waitFor("192.168.1.31"))
  }

  @Test
  fun `IPv6 addresses in one network share their strikes`() {
    backoff.recordStrike("2001:db8:1:2::10")

    assertEquals(30.seconds, backoff.waitFor("2001:db8:1:2:abcd::99"))
    assertNull(backoff.waitFor("2001:db8:1:3::10"))
  }

  @Test
  fun `many strikes from many addresses pause every request`() {
    repeat(5) { backoff.recordStrike("192.168.1.${30 + it}") }

    assertEquals(10.minutes, backoff.waitFor("192.168.1.99"))
    time += 10.minutes
    assertNull(backoff.waitFor("192.168.1.99"))
  }

  @Test
  fun `strikes spread over more than the window do not pause requests`() {
    repeat(5) {
      backoff.recordStrike("192.168.1.${30 + it}")
      time += 3.minutes
    }

    assertNull(backoff.waitFor("192.168.1.99"))
  }
}
