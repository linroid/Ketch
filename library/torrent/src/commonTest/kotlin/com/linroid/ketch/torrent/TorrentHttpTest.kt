package com.linroid.ketch.torrent

import com.linroid.ketch.core.engine.HttpEngine
import com.linroid.ketch.core.engine.ServerInfo
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.TimeoutCancellationException
import kotlin.test.Test
import kotlin.test.assertContentEquals
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
}
