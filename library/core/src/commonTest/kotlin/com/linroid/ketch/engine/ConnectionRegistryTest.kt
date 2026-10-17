package com.linroid.ketch.engine

import com.linroid.ketch.api.ActiveConnection
import com.linroid.ketch.api.ActiveConnections
import com.linroid.ketch.api.ConnectionRoute
import com.linroid.ketch.api.Proxy
import com.linroid.ketch.core.engine.ConnectionRegistry
import com.linroid.ketch.core.engine.ConnectionSample
import com.linroid.ketch.core.engine.ConnectionSpec
import com.linroid.ketch.core.engine.httpConnectionSpec
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
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
  fun samples_nameEachHostsCountry() = runTest {
    val registry = ConnectionRegistry(
      scope = backgroundScope,
      clock = FixedClock,
      timeSource = testScheduler.timeSource,
      countryOf = { host -> "DE".takeIf { host == "cdn.example.com" } },
    )
    registry.reporter("task").open(spec)
    registry.reporter("task").open(spec.copy(host = "192.0.2.1"))
    val samples = collectSamples(registry)
    runCurrent()

    assertEquals(listOf("DE", null), samples.last().connections.map { it.country })
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
      closed = listOf(connection(id = 9, openedAt = 0), connection(id = 8, openedAt = 0)),
      receivedBytes = 7_000,
      sentBytes = 900,
    )

    val snapshot = sample.snapshot(limit = 1)

    assertEquals(listOf(2L, 3L), sample.snapshot(limit = 2).connections.map { it.id })
    assertEquals(listOf(9L), snapshot.closed.map { it.id })
    assertEquals(7_000L, snapshot.receivedBytes)
    assertEquals(900L, snapshot.sentBytes)
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
  fun open_pastMaximum_countsDroppedAndOnlyItsBytes() = runTest {
    val registry = registry(maxRegistered = 2)
    val reporter = registry.reporter("task")
    val first = reporter.open(spec)
    val second = reporter.open(spec)

    val untracked = reporter.open(spec)
    untracked.received(700)
    untracked.sent(30)
    untracked.close()
    first.close()
    val samples = collectSamples(registry)
    runCurrent()

    assertEquals(1, samples.last().connections.size)
    assertEquals(1L, samples.last().dropped)
    // Never listed, open or closed, yet in the totals.
    assertEquals(listOf(1L), samples.last().closed.map { it.id })
    assertEquals(700L, samples.last().receivedBytes)
    assertEquals(30L, samples.last().sentBytes)
    assertTrue(reporter.open(spec) !== untracked)
    second.close()
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
  fun samples_freshCollection_idleConnection_reportsZero() = runTest {
    val registry = registry()
    val handle = registry.reporter("task").open(spec)
    handle.received(100_000)
    advanceTimeBy(10.seconds)

    // A one-shot read, as GET /api/connections makes, while nothing else collects.
    val first = async { registry.snapshots(10).first() }
    runCurrent()
    assertFalse(first.isCompleted)
    tick()

    val connection = first.await().connections.single()
    assertEquals(0L, connection.downloadBps)
    assertEquals(100_000L, connection.downloadedBytes)
  }

  @Test
  fun samples_freshCollection_measuresOverOneInterval() = runTest {
    val registry = registry()
    val handle = registry.reporter("task").open(spec)
    handle.received(100_000)
    advanceTimeBy(10.seconds)

    val first = async { registry.snapshots(10).first() }
    runCurrent()
    handle.received(3000)
    tick()

    assertEquals(3000L, first.await().connections.single().downloadBps)
  }

  @Test
  fun samples_collectionSoonAfterAnother_measuresFromItsSamples() = runTest {
    val registry = registry()
    val handle = registry.reporter("task").open(spec)
    advanceTimeBy(10.seconds)
    val warmUp = async { registry.snapshots(10).first() }
    tick()
    warmUp.await()

    handle.received(4000)
    advanceTimeBy(1.seconds)
    val next = async { registry.snapshots(10).first() }
    runCurrent()

    // Sent at once, measured over the 2 s since the first collection noted the counters.
    assertTrue(next.isCompleted)
    assertEquals(2000L, next.await().connections.single().downloadBps)
  }

  @Test
  fun samples_newConnection_measuredSinceItOpenedAtOnce() = runTest {
    val registry = registry()
    val handle = registry.reporter("task").open(spec)
    advanceTimeBy(500.milliseconds)
    handle.received(1000)

    val first = async { registry.snapshots(10).first() }
    runCurrent()

    assertTrue(first.isCompleted)
    assertEquals(2000L, first.await().connections.single().downloadBps)
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
  fun totals_keepTheBytesOfClosedConnections() = runTest {
    val registry = registry()
    val reporter = registry.reporter("task")
    val first = reporter.open(spec)
    first.received(1000)
    first.sent(200)
    val samples = collectSamples(registry)
    runCurrent()
    assertEquals(1000L, samples.last().receivedBytes)

    first.close()
    val second = reporter.open(spec.copy(host = "mirror.example.com"))
    second.received(500)
    tick()

    assertEquals(1500L, samples.last().receivedBytes)
    assertEquals(200L, samples.last().sentBytes)
    second.close()
    tick()
    val last = samples.last()
    assertEquals(1500L, last.receivedBytes)
    assertTrue(last.connections.isEmpty())
    assertEquals(samples.map { it.receivedBytes }.sorted(), samples.map { it.receivedBytes })
  }

  @Test
  fun closed_keepsFinalCountsAndWhenItClosed_newestFirst() = runTest {
    val registry = registry()
    val reporter = registry.reporter("task")
    val first = reporter.open(spec)
    first.received(1000)
    advanceTimeBy(3.seconds)
    first.close()
    reporter.open(spec.copy(host = "mirror.example.com")).close()

    val snapshot = registry.snapshots(10).first()

    assertTrue(snapshot.connections.isEmpty())
    assertEquals(listOf("mirror.example.com", "cdn.example.com"), snapshot.closed.map { it.host })
    val closed = snapshot.closed.last()
    assertEquals(FixedClock.now(), closed.closedAt)
    assertEquals(1000L, closed.downloadedBytes)
    assertEquals(0L, closed.downloadBps)
    assertEquals(1000L, snapshot.receivedBytes)
  }

  @Test
  fun closed_keepsOnlyTheLatest() = runTest {
    val registry = registry(maxClosed = 3)
    val reporter = registry.reporter("task")
    repeat(5) { reporter.open(spec).close() }

    val snapshot = registry.snapshots(10).first()

    assertEquals(listOf(5L, 4L, 3L), snapshot.closed.map { it.id })
    assertEquals(listOf(5L, 4L), registry.snapshots(2).first().closed.map { it.id })
  }

  @Test
  fun closed_expireAfterTheRetention() = runTest {
    val registry = registry()
    val reporter = registry.reporter("task")
    reporter.open(spec).also { it.received(100) }.close()
    advanceTimeBy(ConnectionRegistry.CLOSED_RETENTION - 1.seconds)
    reporter.open(spec).close()

    assertEquals(listOf(2L, 1L), registry.snapshots(10).first().closed.map { it.id })
    advanceTimeBy(2.seconds)
    val later = registry.snapshots(10).first()
    assertEquals(listOf(2L), later.closed.map { it.id })
    // Its bytes stay in the totals.
    assertEquals(100L, later.receivedBytes)
    advanceTimeBy(ConnectionRegistry.CLOSED_RETENTION)
    assertTrue(registry.snapshots(10).first().closed.isEmpty())
  }

  @Test
  fun snapshots_idleWithClosedConnections_areNotSentAgain() = runTest {
    val registry = registry()
    val reporter = registry.reporter("task")
    reporter.open(spec).also { it.received(100) }.close()
    reporter.open(spec)
    val snapshots = mutableListOf<ActiveConnections>()
    backgroundScope.launch { registry.snapshots(10).collect { snapshots += it } }
    runCurrent()

    repeat(5) { tick() }

    assertEquals(1, snapshots.size)
    assertEquals(1, snapshots.single().closed.size)
  }

  @Test
  fun httpConnectionSpec_usesUrlHostPortAndProxyRoute() {
    val direct = httpConnectionSpec("http", "https://User:pw@CDN.Example.com/a", Proxy.Direct)
    assertEquals("cdn.example.com", direct.host)
    assertEquals(443, direct.port)
    assertEquals(true, direct.secure)
    assertEquals(ConnectionRoute.DIRECT, direct.route)

    val ipv6 = httpConnectionSpec("http", "http://[::1]:8080/a", Proxy.System)
    assertEquals("::1", ipv6.host)
    assertEquals(8080, ipv6.port)
    assertEquals(false, ipv6.secure)
    assertEquals(ConnectionRoute.UNKNOWN, ipv6.route)

    val socks = Proxy.Manual("socks5://127.0.0.1:1080", bypass = listOf("example.org"))
    assertEquals(ConnectionRoute.SOCKS5_PROXY,
      httpConnectionSpec("http", "http://cdn.example.com/a", socks).route)
    assertEquals(ConnectionRoute.DIRECT,
      httpConnectionSpec("http", "http://dl.example.org/a", socks).route)
    assertEquals(ConnectionRoute.HTTP_PROXY, httpConnectionSpec("http", "http://cdn.example.com/a",
      Proxy.Manual("http://127.0.0.1:3128")).route)
  }

  @Test
  fun spec_toString_leavesHostOut() {
    assertFalse("cdn.example.com" in spec.toString())
    assertTrue("http" in spec.toString())
  }

  private fun TestScope.registry(
    maxRegistered: Int = ConnectionRegistry.MAX_REGISTERED,
    maxClosed: Int = ConnectionRegistry.MAX_CLOSED,
  ) = ConnectionRegistry(
    scope = backgroundScope,
    clock = FixedClock,
    timeSource = testScheduler.timeSource,
    maxRegistered = maxRegistered,
    maxClosed = maxClosed,
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
