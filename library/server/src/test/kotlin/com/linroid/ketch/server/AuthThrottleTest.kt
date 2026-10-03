package com.linroid.ketch.server

import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TestTimeSource

class AuthThrottleTest {
  private val time = TestTimeSource()
  private val throttle = AuthThrottle(maxFailures = 3, window = 1.minutes, timeSource = time)

  @Test
  fun retryAfter_belowTheLimit_null() {
    repeat(2) { throttle.recordFailure(ADDRESS) }

    assertNull(throttle.retryAfter(ADDRESS))
  }

  @Test
  fun retryAfter_atTheLimit_waitsForTheRestOfTheWindow() {
    repeat(3) { throttle.recordFailure(ADDRESS) }
    time += 20.seconds

    assertEquals(40.seconds, throttle.retryAfter(ADDRESS))
    assertNull(throttle.retryAfter("192.0.2.2"))
  }

  @Test
  fun retryAfter_windowOver_countsAgainFromZero() {
    repeat(3) { throttle.recordFailure(ADDRESS) }
    time += 1.minutes

    assertNull(throttle.retryAfter(ADDRESS))
    throttle.recordFailure(ADDRESS)
    assertNull(throttle.retryAfter(ADDRESS))
  }

  @Test
  fun tokenCheck_onlyTheSameToken_matches() {
    val check = TokenCheck("secret-token")

    assertTrue(check.matches("secret-token"))
    assertFalse(check.matches("secret"))
    assertFalse(check.matches("secret-token2"))
    assertFalse(check.matches(""))
  }

  @Test
  fun `ten wrong tokens turn the address away even with the right token`() = testApplication {
    application {
      val server = KetchServer(createTestKetch(), apiToken = "secret-token")
      with(server) { configureServer() }
    }

    repeat(10) {
      val wrong = client.get("/api/status") { bearerAuth("guess-$it") }
      assertEquals(HttpStatusCode.Unauthorized, wrong.status)
    }
    val right = client.get("/api/status") { bearerAuth("secret-token") }

    assertEquals(HttpStatusCode.TooManyRequests, right.status)
    assertNotNull(right.headers[HttpHeaders.RetryAfter])
    // Requests without a token are not throttled, so they get the usual challenge.
    assertEquals(HttpStatusCode.Unauthorized, client.get("/api/status").status)
  }

  private companion object {
    const val ADDRESS = "192.0.2.1"
  }
}
