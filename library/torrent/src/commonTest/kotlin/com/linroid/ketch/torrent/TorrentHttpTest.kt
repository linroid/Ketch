package com.linroid.ketch.torrent

import com.linroid.ketch.core.engine.HttpEngine
import com.linroid.ketch.core.engine.ServerInfo
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.TimeoutCancellationException
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

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

  @Test
  fun fetchRange_silentServer_failsIdle() = runTest {
    assertFailsWith<HttpIdleException> {
      TorrentHttp(StallingEngine(10_000)).fetchRange("https://test", 0L..1L, 1_000) { }
    }
  }

  @Test
  fun fetchRange_slowConsumer_isNotIdle() = runTest {
    val received = mutableListOf<Byte>()
    TorrentHttp(StallingEngine(500)).fetchRange("https://test", 0L..1L, 1_000) { bytes ->
      // A rate limit holding the first chunk for longer than the idle timeout.
      if (received.isEmpty()) delay(5_000)
      received += bytes.toList()
    }
    assertEquals(listOf<Byte>(1, 2), received)
  }
}
