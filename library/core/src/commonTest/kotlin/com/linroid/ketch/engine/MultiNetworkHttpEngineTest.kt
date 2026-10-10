package com.linroid.ketch.engine

import com.linroid.ketch.api.ProxyConfig
import com.linroid.ketch.core.engine.HttpEngine
import com.linroid.ketch.core.engine.MultiNetworkHttpEngine
import com.linroid.ketch.core.engine.ServerInfo
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

class MultiNetworkHttpEngineTest {
  @Test
  fun requests_rotateAndForwardArgumentsAndData() = runTest {
    val first = RecordingEngine()
    val second = RecordingEngine()
    val delegates = mutableListOf<HttpEngine>(first, second)
    val engine = MultiNetworkHttpEngine(delegates)
    delegates.clear()
    val headers = mapOf("Authorization" to "Bearer test")

    assertSame(first.info, engine.head("https://example.com/file", headers))
    val chunks = mutableListOf<Byte>()
    engine.download("https://example.com/file", 10L..19L, headers) { chunks.addAll(it.toList()) }
    engine.download("https://example.com/whole", null) {}

    assertEquals(listOf(Request("https://example.com/file", null, headers)), first.heads)
    assertEquals(listOf(Request("https://example.com/file", 10L..19L, headers)), second.gets)
    assertEquals(listOf(Request("https://example.com/whole", null, emptyMap())), first.gets)
    assertEquals(listOf<Byte>(1, 2), chunks)
  }

  @Test
  fun concurrentSegments_useBothNetworksWithoutSerializingTransfers() = runTest {
    val release = CompletableDeferred<Unit>()
    val started = CompletableDeferred<Unit>()
    var active = 0
    val delegates = List(2) {
      RecordingEngine(beforeData = {
        active++
        if (active == 4) started.complete(Unit)
        release.await()
      })
    }
    val engine = MultiNetworkHttpEngine(delegates)
    val requests = List(4) { index ->
      async { engine.download("https://example.com/file", index * 10L..index * 10L + 9) {} }
    }
    started.await()
    assertEquals(listOf(2, 2), delegates.map { it.gets.size })
    release.complete(Unit)
    requests.awaitAll()
  }

  @Test
  fun partialFailure_isNotReplayedAndNextRequestUsesNextNetwork() = runTest {
    val failure = IllegalStateException("Disconnected after delivering data")
    val first = RecordingEngine(afterData = { throw failure })
    val second = RecordingEngine()
    val engine = MultiNetworkHttpEngine(listOf(first, second))
    var callbacks = 0
    val actual = assertFailsWith<IllegalStateException> {
      engine.download("https://example.com/file", 0L..9L) { callbacks++ }
    }
    assertSame(failure, actual)
    assertEquals(1, callbacks)
    assertTrue(second.gets.isEmpty())
    engine.download("https://example.com/file", 2L..9L) {}
    assertEquals(2L..9L, second.gets.single().range)
  }

  @Test
  fun cancellation_propagatesWithoutFallback() = runTest {
    val cancellation = CancellationException("Canceled")
    val first = RecordingEngine(beforeData = { throw cancellation })
    val second = RecordingEngine()
    val engine = MultiNetworkHttpEngine(listOf(first, second))
    assertSame(cancellation, assertFailsWith<CancellationException> {
      engine.download("https://example.com/file", null) {}
    })
    assertTrue(second.gets.isEmpty())
  }

  @Test
  fun close_closesDistinctDelegatesDespiteFailureAndRejectsRequests() = runTest {
    val first = RecordingEngine(closeFailure = IllegalStateException("close failed"))
    val second = RecordingEngine(closeFailure = IllegalArgumentException("also failed"))
    val engine = MultiNetworkHttpEngine(listOf(first, first, second))
    val failure = assertFailsWith<IllegalStateException> { engine.close() }
    assertSame(first.closeFailure, failure)
    assertTrue(second.closeFailure === failure.suppressedExceptions.single())
    engine.close()
    assertEquals(1, first.closeCount)
    assertEquals(1, second.closeCount)
    assertFailsWith<IllegalStateException> { engine.head("https://example.com/file") }
    assertFailsWith<IllegalStateException> {
      engine.download("https://example.com/file", null) {}
    }
  }

  @Test
  fun withProxy_takesTurnsWithEngineAndAppliesProxyOnEachNetwork() = runTest {
    val first = RecordingEngine()
    val second = RecordingEngine()
    val engine = MultiNetworkHttpEngine(listOf(first, second))
    val proxy = ProxyConfig.manual("socks5://proxy.test:1080")

    engine.withProxy(proxy).head("https://example.com/file")
    engine.download("https://example.com/file", null) {}
    engine.withProxy(proxy).download("https://example.com/file", 0L..9L) {}
    engine.withProxy(proxy).close()

    assertEquals(listOf(proxy, proxy), first.proxies)
    assertEquals(1, first.heads.size)
    assertEquals(listOf(0L..9L), first.gets.map { it.range })
    assertTrue(second.proxies.isEmpty())
    assertEquals(1, second.gets.size)
    assertEquals(0, first.closeCount)
  }

  @Test
  fun emptyDelegates_areRejected() {
    assertFailsWith<IllegalArgumentException> { MultiNetworkHttpEngine(emptyList()) }
  }

  private data class Request(
    val url: String,
    val range: LongRange?,
    val headers: Map<String, String>,
  )

  private class RecordingEngine(
    val closeFailure: Exception? = null,
    val beforeData: suspend () -> Unit = {},
    val afterData: suspend () -> Unit = {},
  ) : HttpEngine {
    val info = ServerInfo(100, true, "etag", null)
    val heads = mutableListOf<Request>()
    val gets = mutableListOf<Request>()
    val proxies = mutableListOf<ProxyConfig>()
    var closeCount = 0

    override suspend fun head(url: String, headers: Map<String, String>): ServerInfo {
      heads.add(Request(url, null, headers))
      return info
    }

    override suspend fun download(
      url: String,
      range: LongRange?,
      headers: Map<String, String>,
      onData: suspend (ByteArray) -> Unit,
    ) {
      gets.add(Request(url, range, headers))
      beforeData()
      onData(byteArrayOf(1, 2))
      afterData()
    }

    override fun withProxy(proxy: ProxyConfig): HttpEngine {
      proxies.add(proxy)
      return this
    }

    override fun close() {
      closeCount++
      closeFailure?.let { throw it }
    }
  }
}
