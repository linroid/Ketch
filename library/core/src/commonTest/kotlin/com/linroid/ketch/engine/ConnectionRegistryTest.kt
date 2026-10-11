package com.linroid.ketch.engine

import com.linroid.ketch.api.ActiveConnection
import com.linroid.ketch.api.ActiveConnections
import com.linroid.ketch.api.ConnectionRoute
import com.linroid.ketch.api.ProxyConfig
import com.linroid.ketch.core.engine.ConnectionHandle
import com.linroid.ketch.core.engine.ConnectionRegistry
import com.linroid.ketch.core.engine.ConnectionSample
import com.linroid.ketch.core.engine.ConnectionSpec
import com.linroid.ketch.core.engine.httpConnectionSpec
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

class ConnectionRegistryTest {
  private val spec = ConnectionSpec("http", "cdn.example.com", 443, null, true)

  @Test
  fun samples_rateAveragesTheLastTwoTicks() = runTest {
    val registry = registry()
    val handle = registry.reporter("task").open(spec)
    handle.received(500)
    val samples = collectSamples(registry)

    runCurrent()
    handle.received(1000)
    tick()
    handle.received(3000)
    tick()
    tick()

    val rates = samples.map { it.connections.single().downloadBps }
    // Opened as sampling started; then 1000 B over 1 s, 4000 B over 2 s and 3000 B over 2 s.
    assertEquals(listOf(0L, 1000L, 2000L, 1500L), rates)
    assertEquals(4500L, samples.last().connections.single().downloadedBytes)
  }

  @Test
  fun samples_uploadAndDownloadCountedApart() = runTest {
    val registry = registry()
    val handle = registry.reporter("task").open(spec)
    val samples = collectSamples(registry)
    runCurrent()

    handle.sent(2000)
    tick()

    val connection = samples.last().connections.single()
    assertEquals(0L, connection.downloadBps)
    assertEquals(2000L, connection.uploadBps)
    assertEquals(2000L, connection.uploadedBytes)
  }

  @Test
  fun snapshot_limit_keepsFastestOrderedByOpening() {
    val sample = ConnectionSample(
      sampledAt = Instant.fromEpochSeconds(100),
      connections = listOf(
        connection(id = 1, openedAt = 1, down = 10),
        connection(id = 2, openedAt = 2, down = 500, up = 500),
        connection(id = 3, openedAt = 3, down = 0, up = 2000),
        connection(id = 4, openedAt = 0, down = 5),
      ),
      dropped = 3,
    )

    val snapshot = sample.snapshot(limit = 2)

    assertEquals(listOf(2L, 3L), snapshot.connections.map { it.id })
    assertEquals(4, snapshot.total)
    assertEquals(515L, snapshot.downloadBps)
    assertEquals(2500L, snapshot.uploadBps)
    assertEquals(3L, snapshot.dropped)
    assertEquals(listOf(4L, 1L, 2L, 3L), sample.snapshot(limit = 4).connections.map { it.id })
  }

  @Test
  fun close_removesConnectionByNextTickAndIsIdempotent() = runTest {
    val registry = registry()
    val reporter = registry.reporter("task")
    val first = reporter.open(spec)
    val second = reporter.open(spec.copy(host = "mirror.example.com"))
    val samples = collectSamples(registry)
    runCurrent()
    assertEquals(2, samples.last().connections.size)

    first.close()
    first.close()
    tick()

    assertEquals(listOf("mirror.example.com"), samples.last().connections.map { it.host })
    assertEquals(1, registry.size)
    second.close()
    assertEquals(0, registry.size)
  }

  @Test
  fun closeTask_closesOnlyThatTasksConnections() = runTest {
    val registry = registry()
    registry.reporter("a").open(spec)
    registry.reporter("a").open(spec)
    registry.reporter("b").open(spec)

    registry.closeTask("a")

    assertEquals(1, registry.size)
    registry.closeAll()
    assertEquals(0, registry.size)
  }

  @Test
  fun open_pastMaximum_countsDroppedAndReturnsNoOpHandle() = runTest {
    val registry = registry(maxRegistered = 2)
    val reporter = registry.reporter("task")
    val first = reporter.open(spec)
    reporter.open(spec)

    assertSame(ConnectionHandle.None, reporter.open(spec))
    first.close()
    val samples = collectSamples(registry)
    runCurrent()

    assertEquals(1, samples.last().connections.size)
    assertEquals(1L, samples.last().dropped)
    assertFalse(reporter.open(spec) === ConnectionHandle.None)
  }

  @Test
  fun samples_stopWhenTheLastCollectorLeaves() = runTest {
    val clock = CountingClock()
    val registry = ConnectionRegistry(backgroundScope, clock, testScheduler.timeSource)
    val collector = backgroundScope.launch { registry.samples.collect {} }
    runCurrent()
    tick()
    assertEquals(2, clock.calls)

    collector.cancel()
    advanceTimeBy(10.seconds)
    runCurrent()

    assertEquals(2, clock.calls)
  }

  @Test
  fun snapshots_limitOutOfRange_throws() = runTest {
    val registry = registry()
    assertFailsWith<IllegalArgumentException> { registry.snapshots(0) }
    assertFailsWith<IllegalArgumentException> {
      registry.snapshots(ActiveConnections.MAX_LIMIT + 1)
    }
  }

  @Test
  fun snapshots_idleConnections_areNotSentAgain() = runTest {
    val registry = registry()
    val handle = registry.reporter("task").open(spec)
    val snapshots = mutableListOf<ActiveConnections>()
    backgroundScope.launch { registry.snapshots(10).collect { snapshots += it } }
    runCurrent()

    repeat(5) { tick() }
    assertEquals(1, snapshots.size)

    handle.received(100)
    tick()
    assertEquals(2, snapshots.size)
    assertEquals(100L, snapshots.last().connections.single().downloadedBytes)
  }

  @Test
  fun httpConnectionSpec_usesUrlHostPortAndProxyRoute() {
    val direct = httpConnectionSpec("http", "https://User:pw@CDN.Example.com/a", ProxyConfig.Direct)
    assertEquals("cdn.example.com", direct.host)
    assertEquals(443, direct.port)
    assertEquals(true, direct.secure)
    assertEquals(ConnectionRoute.DIRECT, direct.route)

    val ipv6 = httpConnectionSpec("http", "http://[::1]:8080/a", ProxyConfig.System)
    assertEquals("::1", ipv6.host)
    assertEquals(8080, ipv6.port)
    assertEquals(false, ipv6.secure)
    assertEquals(ConnectionRoute.UNKNOWN, ipv6.route)

    val socks = ProxyConfig.manual("socks5://127.0.0.1:1080", bypass = listOf("example.org"))
    assertEquals(ConnectionRoute.SOCKS5_PROXY,
      httpConnectionSpec("http", "http://cdn.example.com/a", socks).route)
    assertEquals(ConnectionRoute.DIRECT,
      httpConnectionSpec("http", "http://dl.example.org/a", socks).route)
    assertEquals(ConnectionRoute.HTTP_PROXY, httpConnectionSpec("http", "http://cdn.example.com/a",
      ProxyConfig.manual("http://127.0.0.1:3128")).route)
  }

  @Test
  fun spec_toString_leavesHostOut() {
    assertFalse("cdn.example.com" in spec.toString())
    assertTrue("http" in spec.toString())
  }

  private fun TestScope.registry(maxRegistered: Int = ConnectionRegistry.MAX_REGISTERED) =
    ConnectionRegistry(
      scope = backgroundScope,
      clock = FixedClock,
      timeSource = testScheduler.timeSource,
      maxRegistered = maxRegistered,
    )

  private fun TestScope.collectSamples(registry: ConnectionRegistry): List<ConnectionSample> {
    val samples = mutableListOf<ConnectionSample>()
    backgroundScope.launch { registry.samples.collect { samples += it } }
    return samples
  }

  private fun TestScope.tick(interval: Duration = 1.seconds) {
    advanceTimeBy(interval)
    runCurrent()
  }

  private fun connection(id: Long, openedAt: Long, down: Long = 0, up: Long = 0) =
    ActiveConnection(
      id = id,
      taskId = "task",
      source = "http",
      host = "host$id",
      downloadBps = down,
      uploadBps = up,
      openedAt = Instant.fromEpochSeconds(openedAt),
    )

  private object FixedClock : Clock {
    override fun now(): Instant = Instant.fromEpochSeconds(1_000)
  }

  private class CountingClock : Clock {
    var calls = 0

    override fun now(): Instant {
      calls++
      return Instant.fromEpochSeconds(1_000)
    }
  }
}
