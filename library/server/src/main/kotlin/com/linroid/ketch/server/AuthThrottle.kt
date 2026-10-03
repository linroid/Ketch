package com.linroid.ketch.server

import com.linroid.ketch.api.log.KetchLogger
import com.linroid.ketch.endpoints.model.ErrorResponse
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationPlugin
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.plugins.origin
import io.ktor.server.response.header
import io.ktor.server.response.respond
import java.security.MessageDigest
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.TimeMark
import kotlin.time.TimeSource

private val log = KetchLogger("KetchServer")

/**
 * Counts wrong tokens per client address and turns an address away once it has sent
 * [maxFailures] of them within [window], until that window ends. Guessing a token then takes a
 * [window] for every [maxFailures] guesses.
 *
 * Clients behind one address, such as a reverse proxy's, share its count, so a client that keeps
 * sending a wrong token also holds back the others until the window ends.
 */
internal class AuthThrottle(
  private val maxFailures: Int = 10,
  private val window: Duration = 1.minutes,
  private val timeSource: TimeSource = TimeSource.Monotonic,
) {
  private class Failures(val since: TimeMark, var count: Int)

  // Insertion ordered, so the oldest address goes first when too many are tracked.
  private val failures = LinkedHashMap<String, Failures>()

  /** How long [address] must wait before its token is checked again; `null` when it need not. */
  @Synchronized
  fun retryAfter(address: String): Duration? {
    val entry = failures[address] ?: return null
    val elapsed = entry.since.elapsedNow()
    if (elapsed >= window) {
      failures.remove(address)
      return null
    }
    return if (entry.count >= maxFailures) window - elapsed else null
  }

  /** Records that [address] sent a wrong token. */
  @Synchronized
  fun recordFailure(address: String) {
    val current = failures[address]
    if (current != null && current.since.elapsedNow() < window) {
      current.count++
      return
    }
    failures.remove(address)
    if (failures.size >= MAX_TRACKED_ADDRESSES) failures.remove(failures.keys.first())
    failures[address] = Failures(timeSource.markNow(), 1)
  }

  private companion object {
    const val MAX_TRACKED_ADDRESSES = 1024
  }
}

/**
 * Answers `429 Too Many Requests` to requests that carry a token from an address [throttle] turns
 * away, before authentication checks it. Requests without a token, such as the web UI's files
 * and pairing requests, pass.
 */
internal fun authThrottling(throttle: AuthThrottle): ApplicationPlugin<Unit> =
  createApplicationPlugin("AuthThrottling") {
    onCall { call ->
      if (call.request.headers[HttpHeaders.Authorization] == null) return@onCall
      val address = call.request.origin.remoteAddress
      val wait = throttle.retryAfter(address) ?: return@onCall
      log.w { "Refused a token from $address: too many wrong tokens" }
      call.response.header(HttpHeaders.RetryAfter, wait.inWholeSeconds.coerceAtLeast(1))
      call.respond(
        HttpStatusCode.TooManyRequests,
        ErrorResponse(
          "too_many_attempts",
          "Too many wrong access tokens; try again in ${wait.inWholeSeconds.coerceAtLeast(1)}s",
        ),
      )
    }
  }

/**
 * Checks bearer tokens against [expected] in time that does not depend on where they differ,
 * nor on their length, as both sides are hashed before they are compared.
 */
internal class TokenCheck(expected: String) {
  private val expectedDigest = sha256(expected)

  fun matches(token: String): Boolean = MessageDigest.isEqual(sha256(token), expectedDigest)

  private fun sha256(text: String): ByteArray =
    MessageDigest.getInstance("SHA-256").digest(text.encodeToByteArray())
}
