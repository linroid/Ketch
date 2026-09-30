package com.linroid.ketch.ai.site

import com.linroid.ketch.ai.fetch.RateLimiter
import com.linroid.ketch.ai.fetch.SafeFetcher
import com.linroid.ketch.ai.fetch.UrlValidator
import com.linroid.ketch.ai.fetch.fakeDns
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull

class SiteProfilerTest {

  private fun profiler(robots: String): SiteProfiler {
    val engine = MockEngine {
      respond(robots, HttpStatusCode.OK, headersOf(HttpHeaders.ContentLength, "${robots.length}"))
    }
    val fetcher = SafeFetcher(
      httpClient = HttpClient(engine) { followRedirects = false },
      urlValidator = UrlValidator(resolve = fakeDns("example.com" to "93.184.215.14")),
      rateLimiter = RateLimiter(delayMs = 0),
    )
    return SiteProfiler(fetcher)
  }

  @Test
  fun fetchRobotsRules_fileOverLimit_parsesTheFirstPartWithoutTheCutLine() = runTest {
    val head = "User-agent: *\nDisallow: /private/\n"
    val cutLine = "Allow: /private/public-docs\n"
    // Pad with one comment line so the limit falls right after "Allow: /private/p".
    val kept = "Allow: /private/p".length
    val padding = "#" + "x".repeat((SiteProfiler.MAX_ROBOTS_BYTES - head.length - kept).toInt() - 2)
    val robots = head + padding + "\n" + cutLine + "Disallow: /late/\n"
    val profiler = profiler(robots)

    val rules = assertNotNull(profiler.fetchRobotsRules("https://example.com"))

    assertEquals(listOf(RobotRule("/private/", allowed = false)), rules.rules)
    // Kept, the half-read "Allow: /private/p" would outrank the disallow for this path.
    assertFalse(profiler.isAllowed("/private/passwords", rules))
  }
}
