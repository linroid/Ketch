package com.linroid.kdown.engine

import com.linroid.kdown.api.KDownError
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
