package com.linroid.ketch.torrent.net

import com.linroid.ketch.core.engine.HttpEngine
import com.linroid.ketch.core.engine.ServerInfo
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TorrentHttpTest {
  private class Engine(private val delayed: Boolean = false) : HttpEngine {
    var closed = false
    override suspend fun head(url: String, headers: Map<String, String>): ServerInfo = error("unused")
    override suspend fun download(
      url: String,
      range: LongRange?,
      headers: Map<String, String>,
      onData: suspend (ByteArray) -> Unit,
    ) {
      if (delayed) delay(10_000)
      onData(byteArrayOf(1, 2))
      onData(byteArrayOf(3, 4))
    }
    override fun close() { closed = true }
  }

  @Test
  fun fetch_limitsAccumulatedBytesAndPreservesBorrowedEngine() = runTest {
    val engine = Engine()
    val http = TorrentHttp(engine)
    assertContentEquals(byteArrayOf(1, 2, 3, 4), http.fetch("https://test", 4))
    assertFailsWith<IllegalArgumentException> { http.fetch("https://test", 3) }
    http.close()
    assertFalse(engine.closed)
  }

  @Test
  fun fetch_deadlineCancelsRequest() = runTest {
    assertFailsWith<TimeoutCancellationException> {
      TorrentHttp(Engine(delayed = true)).fetch("https://test", 4, timeoutMs = 100)
    }
  }

  /** Sends one chunk, waits [stallMs], then sends another. */
  private class StallingEngine(private val stallMs: Long) : HttpEngine {
    override suspend fun head(url: String, headers: Map<String, String>): ServerInfo =
      error("unused")
    override suspend fun download(
      url: String,
      range: LongRange?,
      headers: Map<String, String>,
      onData: suspend (ByteArray) -> Unit,
    ) {
      onData(byteArrayOf(1))
      delay(stallMs)
      onData(byteArrayOf(2))
    }
    override fun close() = Unit
  }

  /** Sends a byte at each of [at], in ms from the request, then nothing until it is cancelled. */
  private class HangingEngine(private vararg val at: Long) : HttpEngine {
    override suspend fun head(url: String, headers: Map<String, String>): ServerInfo =
      error("unused")
    override suspend fun download(
      url: String,
      range: LongRange?,
      headers: Map<String, String>,
      onData: suspend (ByteArray) -> Unit,
    ) {
      var sent = 0L
      for (time in at) {
        delay(time - sent)
        sent = time
        onData(byteArrayOf(1))
      }
      awaitCancellation()
    }
    override fun close() = Unit
  }

  @Test
  fun fetchRange_silentServer_failsIdle() = runTest {
    assertFailsWith<HttpIdleException> {
      TorrentHttp(StallingEngine(10_000), nowMs = { testScheduler.currentTime })
        .fetchRange("https://test", 0L..1L, 1_000) { }
    }
    // A second after its first byte.
    assertEquals(1_000L, testScheduler.currentTime)
  }

  @Test
  fun fetchRange_serverHangsAfterData_failsTheTimeoutAfterTheLastChunk() = runTest {
    val http = TorrentHttp(HangingEngine(0, 20_000), nowMs = { testScheduler.currentTime })
    assertFailsWith<HttpIdleException> { http.fetchRange("https://test", 0L..9L, 30_000) { } }
    // 30 s after the byte at 20 s, within the second between checks; not a whole timeout later.
    val failedAt = testScheduler.currentTime
    assertTrue(failedAt in 50_000L..51_000L, "Failed $failedAt ms after the request")
  }

  @Test
  fun fetchRange_slowConsumer_isNotIdle() = runTest {
    val received = mutableListOf<Byte>()
    TorrentHttp(StallingEngine(500), nowMs = { testScheduler.currentTime })
      .fetchRange("https://test", 0L..1L, 1_000) { bytes ->
      // A rate limit holding the first chunk for longer than the idle timeout.
      if (received.isEmpty()) delay(5_000)
      received += bytes.toList()
    }
    assertEquals(listOf<Byte>(1, 2), received)
  }
}
