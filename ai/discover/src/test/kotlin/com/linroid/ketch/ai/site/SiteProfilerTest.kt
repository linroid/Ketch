package com.linroid.ketch.ai.site

import com.linroid.ketch.ai.fetch.RateLimiter
import com.linroid.ketch.ai.fetch.SafeFetcher
import com.linroid.ketch.ai.fetch.UrlValidator
import com.linroid.ketch.ai.fetch.fakeDns
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandler
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class SiteProfilerTest {

  private fun profiler(robots: String): SiteProfiler = profiler(
    MockEngine {
      respond(robots, HttpStatusCode.OK, headersOf(HttpHeaders.ContentLength, "${robots.length}"))
    },
  )

  private fun profiler(engine: MockEngine): SiteProfiler {
    val fetcher = SafeFetcher(
      httpClient = HttpClient(engine) { followRedirects = false },
      urlValidator = UrlValidator(
        resolve = fakeDns(
          "example.com" to "93.184.215.14",
          "www.example.com" to "93.184.215.14",
          "evil.example" to "203.0.113.5",
          "www.github.io" to "185.199.108.153",
          "attacker.github.io" to "185.199.108.153",
        ),
      ),
      rateLimiter = RateLimiter(delayMs = 0),
    )
    return SiteProfiler(fetcher)
  }

  /** Redirects `example.com/robots.txt` to [location] and serves a disallow-all file there. */
  private fun redirectingRobots(location: String): MockRequestHandler = { request ->
    if (request.url.host == "example.com") {
      respond("", HttpStatusCode.MovedPermanently, headersOf(HttpHeaders.Location, location))
    } else {
      respond("User-agent: *\nDisallow: /\n")
    }
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

  @Test
  fun fetchRobotsRules_redirectToAnotherSite_countsAsMissing() = runTest {
    val engine = MockEngine(redirectingRobots("https://evil.example/robots.txt"))

    val rules = profiler(engine).fetchRobotsRules("https://example.com")

    assertNull(rules)
    assertEquals(listOf("example.com"), engine.requestHistory.map { it.url.host })
  }

  @Test
  fun fetchRobotsRules_redirectFromWwwToAnotherSiteOnSharedSuffix_countsAsMissing() = runTest {
    // www.github.io is one site; dropping its www. would cover every GitHub Pages site.
    val engine = MockEngine { request ->
      if (request.url.host == "www.github.io") {
        val location = "https://attacker.github.io/robots.txt"
        respond("", HttpStatusCode.MovedPermanently, headersOf(HttpHeaders.Location, location))
      } else {
        respond("User-agent: *\nDisallow: /\n")
      }
    }

    val rules = profiler(engine).fetchRobotsRules("https://www.github.io")

    assertNull(rules)
    assertEquals(listOf("www.github.io"), engine.requestHistory.map { it.url.host })
  }

  @Test
  fun fetchRobotsRules_redirectWithinSite_isFollowed() = runTest {
    val engine = MockEngine(redirectingRobots("https://www.example.com/robots.txt"))
    val profiler = profiler(engine)

    val rules = assertNotNull(profiler.fetchRobotsRules("https://example.com"))

    assertFalse(profiler.isAllowed("/downloads/", rules))
  }
}
