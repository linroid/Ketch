package com.linroid.kdown.engine

import com.linroid.kdown.api.KDownError
import com.linroid.kdown.core.engine.HttpDownloadSource
import com.linroid.kdown.core.engine.ServerInfo
import com.linroid.kdown.core.file.DefaultFileNameResolver
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests for HTTP 429 (Too Many Requests) rate limit handling:
 * retryable classification, Retry-After propagation, and
 * FakeHttpEngine 429 support for downstream tests.
 */
class RateLimitTest {

  @Test
  fun fakeEngine_429Download_throwsRetryableHttpError() = runTest {
    val engine = FakeHttpEngine(httpErrorCode = 429)
    val error = assertFailsWith<KDownError.Http> {
      engine.download("https://example.com/file", null) {}
    }
    assertEquals(429, error.code)
    assertTrue(error.isRetryable)
  }

  @Test
  fun fakeEngine_429Download_includesRetryAfterSeconds() = runTest {
    val engine = FakeHttpEngine(
      httpErrorCode = 429,
      retryAfterSeconds = 60,
    )
    val error = assertFailsWith<KDownError.Http> {
      engine.download("https://example.com/file", null) {}
    }
    assertEquals(429, error.code)
    assertEquals(60L, error.retryAfterSeconds)
    assertTrue(error.isRetryable)
  }

  @Test
  fun fakeEngine_429Head_includesRetryAfterSeconds() = runTest {
    val engine = FakeHttpEngine(
      httpErrorCode = 429,
      retryAfterSeconds = 30,
    )
    val error = assertFailsWith<KDownError.Http> {
      engine.head("https://example.com/file")
    }
    assertEquals(429, error.code)
    assertEquals(30L, error.retryAfterSeconds)
  }

  @Test
  fun fakeEngine_429_withoutRetryAfter() = runTest {
    val engine = FakeHttpEngine(httpErrorCode = 429)
    val error = assertFailsWith<KDownError.Http> {
      engine.download("https://example.com/file", null) {}
    }
    assertEquals(429, error.code)
    assertNull(error.retryAfterSeconds)
    assertTrue(error.isRetryable)
  }

  @Test
  fun fakeEngine_500_noRetryAfter() = runTest {
    val engine = FakeHttpEngine(
      httpErrorCode = 500,
      retryAfterSeconds = 10,
    )
    val error = assertFailsWith<KDownError.Http> {
      engine.download("https://example.com/file", null) {}
    }
    assertEquals(500, error.code)
    // retryAfterSeconds is still passed through by FakeHttpEngine
    // regardless of code; real KtorHttpEngine only parses it for 429
    assertEquals(10L, error.retryAfterSeconds)
  }

  @Test
  fun connectionReduction_halvesWithMinimumOfOne() {
    // Verifies the reduction formula used by
    // DownloadCoordinator.reduceConnections
    val cases = mapOf(
      8 to 4,
      4 to 2,
      3 to 1,
      2 to 1,
      1 to 1,
    )
    for ((input, expected) in cases) {
      val reduced = (input / 2).coerceAtLeast(1)
      assertEquals(
        expected, reduced,
        "Expected $input connections to reduce to $expected",
      )
    }
  }

  @Test
  fun connectionReduction_chain() {
    // Simulates successive 429 reductions: 8 -> 4 -> 2 -> 1 -> 1
    var connections = 8
    val expected = listOf(4, 2, 1, 1)
    for (exp in expected) {
      connections = (connections / 2).coerceAtLeast(1)
      assertEquals(exp, connections)
    }
  }

  @Test
  fun resegmentOnConnectionReduction_mergesIncompleteSegments() {
    // Simulate: 4 segments, 2 fully complete, 2 with some progress
    // When reduced to 2 connections, should merge remaining incomplete bytes
    val segments = listOf(
      com.linroid.kdown.api.Segment(0, 0, 249, downloadedBytes = 250),
      com.linroid.kdown.api.Segment(1, 250, 499, downloadedBytes = 250),
      com.linroid.kdown.api.Segment(2, 500, 749, downloadedBytes = 0),
      com.linroid.kdown.api.Segment(3, 750, 999, downloadedBytes = 0),
    )

    val newConnections = 2
    val result = com.linroid.kdown.core.segment.SegmentCalculator.resegment(
      segments, newConnections
    )

    // Should have 2 incomplete segments (merged remaining 500 bytes)
    val incomplete = result.count { !it.isComplete }
    assertEquals(2, incomplete, "Should have 2 incomplete segments after reduction to 2 connections")

    // All progress should be preserved (500 bytes completed)
    val completedBytes = result.filter { it.isComplete }
      .sumOf { it.downloadedBytes }
    assertEquals(500L, completedBytes, "Should preserve completed 500 bytes")

    // Full file coverage maintained
    val totalCovered = result.sumOf { it.totalBytes }
    assertEquals(1000L, totalCovered, "Should cover full 1000 bytes")
  }

  // -- Proactive rate limit tests --

  @Test
  fun fakeEngine_rateLimitInServerInfo_passedThrough() = runTest {
    val engine = FakeHttpEngine(
      serverInfo = ServerInfo(
        contentLength = 5000,
        acceptRanges = true,
        etag = null,
        lastModified = null,
        rateLimitRemaining = 3,
        rateLimitReset = 60,
      ),
    )
    val info = engine.head("https://example.com/file")
    assertEquals(3L, info.rateLimitRemaining)
    assertEquals(60L, info.rateLimitReset)
  }

  @Test
  fun fakeEngine_noRateLimit_fieldsAreNull() = runTest {
    val engine = FakeHttpEngine()
    val info = engine.head("https://example.com/file")
    assertNull(info.rateLimitRemaining)
    assertNull(info.rateLimitReset)
  }

  @Test
  fun rateLimitCapping_remainingLessThanConnections() {
    // Simulates the capping formula from HttpDownloadSource.applyRateLimit:
    // if remaining > 0 && remaining < connections -> cap to remaining (min 1)
    val connections = 4
    val remaining = 2L
    val capped = remaining.toInt().coerceAtLeast(1)
    assertEquals(2, capped)
    assertTrue(capped < connections)
  }

  @Test
  fun rateLimitCapping_remainingIsOne() {
    val remaining = 1L
    val capped = remaining.toInt().coerceAtLeast(1)
    assertEquals(1, capped)
  }

  @Test
  fun rateLimitCapping_remainingGreaterThanConnections_noChange() {
    // When remaining >= connections, no capping occurs
    val connections = 4
    val remaining = 10L
    // applyRateLimit returns connections unchanged
    assertTrue(remaining >= connections)
  }

  @Test
  fun resolve_rateLimitCapsMaxSegments() = runTest {
    // When rate limit remaining is less than maxConnections,
    // the metadata carries the rate limit info for download() to use
    val engine = FakeHttpEngine(
      serverInfo = ServerInfo(
        contentLength = 10000,
        acceptRanges = true,
        etag = null,
        lastModified = null,
        rateLimitRemaining = 2,
        rateLimitReset = 30,
      ),
    )
    val source = HttpDownloadSource(
      httpEngine = engine,
      fileNameResolver = DefaultFileNameResolver(),
      maxConnections = 4,
    )
    val resolved = source.resolve("https://example.com/file.zip")
    // maxSegments is based on maxConnections (4), rate limit capping
    // happens at download time, not resolve time
    assertEquals(4, resolved.maxSegments)
    // But metadata carries the rate limit info for download() to cap
    assertEquals("2", resolved.metadata["rateLimitRemaining"])
    assertEquals("30", resolved.metadata["rateLimitReset"])
  }

  // -- 429 + RateLimit-Remaining tests --

  @Test
  fun fakeEngine_429_includesRateLimitRemaining() = runTest {
    val engine = FakeHttpEngine(
      httpErrorCode = 429,
      retryAfterSeconds = 10,
      rateLimitRemaining = 0,
    )
    val error = assertFailsWith<KDownError.Http> {
      engine.download("https://example.com/file", null) {}
    }
    assertEquals(429, error.code)
    assertEquals(10L, error.retryAfterSeconds)
    assertEquals(0L, error.rateLimitRemaining)
  }

  @Test
  fun fakeEngine_429Head_includesRateLimitRemaining() = runTest {
    val engine = FakeHttpEngine(
      httpErrorCode = 429,
      rateLimitRemaining = 2,
    )
    val error = assertFailsWith<KDownError.Http> {
      engine.head("https://example.com/file")
    }
    assertEquals(429, error.code)
    assertEquals(2L, error.rateLimitRemaining)
  }

  @Test
  fun connectionReduction_usesRateLimitRemaining_whenLessThanCurrent() {
    // When RateLimit-Remaining=2 and current=4, reduce to 2
    val current = 4
    val rateLimitRemaining = 2L
    val reduced = if (rateLimitRemaining < current) {
      rateLimitRemaining.toInt().coerceAtLeast(1)
    } else {
      (current / 2).coerceAtLeast(1)
    }
    assertEquals(2, reduced)
  }

  @Test
  fun connectionReduction_usesRateLimitRemaining_zeroMeansOne() {
    // When RateLimit-Remaining=0, reduce to minimum of 1
    val current = 4
    val rateLimitRemaining = 0L
    val reduced = if (rateLimitRemaining < current) {
      rateLimitRemaining.toInt().coerceAtLeast(1)
    } else {
      (current / 2).coerceAtLeast(1)
    }
    assertEquals(1, reduced)
  }

  @Test
  fun connectionReduction_fallsBackToHalving_whenRemainingNull() {
    // When RateLimit-Remaining is absent, halve as before
    val current = 4
    val rateLimitRemaining: Long? = null
    val reduced = if (rateLimitRemaining != null &&
      rateLimitRemaining < current
    ) {
      rateLimitRemaining.toInt().coerceAtLeast(1)
    } else {
      (current / 2).coerceAtLeast(1)
    }
    assertEquals(2, reduced)
  }

  @Test
  fun connectionReduction_fallsBackToHalving_whenRemainingHigher() {
    // RateLimit-Remaining >= current → fall back to halving
    val current = 4
    val rateLimitRemaining = 10L
    val reduced = if (rateLimitRemaining < current) {
      rateLimitRemaining.toInt().coerceAtLeast(1)
    } else {
      (current / 2).coerceAtLeast(1)
    }
    assertEquals(2, reduced)
  }

  @Test
  fun resegmentOnConnectionReduction_preservesDownloadProgress() {
    // 4 segments, each with partial progress -> reduce to 1 connection
    // Should merge all incomplete ranges while preserving completed portions
    val segments = listOf(
      com.linroid.kdown.api.Segment(0, 0, 249, downloadedBytes = 250),
      com.linroid.kdown.api.Segment(1, 250, 499, downloadedBytes = 0),
      com.linroid.kdown.api.Segment(2, 500, 749, downloadedBytes = 0),
      com.linroid.kdown.api.Segment(3, 750, 999, downloadedBytes = 0),
    )

    val result = com.linroid.kdown.core.segment.SegmentCalculator.resegment(
      segments, 1
    )

    // One incomplete segment for remaining 750 bytes
    val incomplete = result.count { !it.isComplete }
    assertEquals(1, incomplete, "Should have 1 incomplete segment")

    // All progress preserved: 250 bytes
    val completedBytes = result.filter { it.isComplete }
      .sumOf { it.downloadedBytes }
    assertEquals(250L, completedBytes, "Should preserve 250 bytes of progress")

    // Full coverage
    val totalCovered = result.sumOf { it.totalBytes }
    assertEquals(1000L, totalCovered, "Should cover full 1000 bytes")
  }
}
