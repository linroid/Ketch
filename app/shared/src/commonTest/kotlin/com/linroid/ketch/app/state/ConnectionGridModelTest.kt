package com.linroid.ketch.app.state

import com.linroid.ketch.api.ActiveConnection
import com.linroid.ketch.api.ActiveConnections
import com.linroid.ketch.api.DownloadState
import com.linroid.ketch.api.KetchApi
import com.linroid.ketch.app.FakeKetchApi
import com.linroid.ketch.app.i18n.verbatim
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class ConnectionGridModelTest {
  @Test
  fun trafficLevel_boundaries_stepAtFixedRates() {
    assertEquals(0, trafficLevel(0))
    assertEquals(1, trafficLevel(1))
    assertEquals(1, trafficLevel(16 * KIB - 1))
    assertEquals(2, trafficLevel(16 * KIB))
    assertEquals(2, trafficLevel(256 * KIB - 1))
    assertEquals(3, trafficLevel(256 * KIB))
    assertEquals(3, trafficLevel(2 * MIB - 1))
    assertEquals(4, trafficLevel(2 * MIB))
    assertEquals(4, trafficLevel(500 * MIB))
  }

  @Test
  fun trafficDirection_rates_mapToDownUpBothOrIdle() {
    assertEquals(TrafficDirection.Down, trafficDirection(10, 0))
    assertEquals(TrafficDirection.Up, trafficDirection(0, 10))
    assertEquals(TrafficDirection.Both, trafficDirection(10, 10))
    assertEquals(TrafficDirection.Idle, trafficDirection(0, 0))
  }

  @Test
  fun connectionCell_bothDirections_takesTheLevelOfTheBusierAndItsAge() {
    val snapshot = snapshot(
      connection(1, "a", down = 1_000, up = 3 * MIB, openedSecondsAgo = 90),
    )

    val cell = connectionCell("mac", snapshot.connections.single(), snapshot)

    assertEquals(TrafficDirection.Both, cell.direction)
    assertEquals(4, cell.level)
    assertEquals(90.seconds, cell.age)
    assertEquals(TaskKey("mac", "a"), cell.task)
  }

  @Test
  fun connectionRetryDelay_failuresInARow_doubleUpTo30Seconds() {
    assertEquals(
      listOf(1, 2, 4, 8, 16, 30, 30).map { it.seconds },
      (0..6).map(::connectionRetryDelay),
    )
  }

  @Test
  fun observe_tasks_followTheListOrderThenTheRest() = runTest {
    val api = FakeConnections(
      snapshot(
        connection(1, "b", openedSecondsAgo = 50),
        connection(2, "z", openedSecondsAgo = 40),
        connection(3, "a", openedSecondsAgo = 30),
        connection(4, "b", openedSecondsAgo = 60),
      ),
    )
    val rows = listOf(row("a"), row("b"))
    val model = ConnectionGridModel(
      flowOf(listOf(source("mac", api))),
      flowOf(rows),
      backgroundScope,
    )

    val state = model.observe(36).first { it.total > 0 }

    val tasks = state.devices.single().tasks
    assertEquals(listOf("a", "b", "z"), tasks.map { it.key.taskId })
    assertEquals(listOf("a.bin", "b.bin", null), tasks.map { it.name })
    // Within a task, oldest first.
    assertEquals(listOf(4L, 1L), tasks[1].cells.map { it.key.id })
    // The strip fills oldest first across tasks.
    assertEquals(listOf(4L, 1L, 2L, 3L), state.cells.map { it.key.id })
  }

  @Test
  fun observe_overflowAndRates_comeFromTheSnapshotTotals() = runTest {
    val api = FakeConnections(
      snapshot(connection(1, "a", down = 100), total = 40, down = 5_000, up = 70),
    )
    val model = model(source("mac", api))

    val state = model.observe(36).first { it.total > 0 }

    assertEquals(39, state.overflow)
    assertEquals(5_000, state.downloadBps)
    assertEquals(70, state.uploadBps)
    assertTrue(state.supported)
  }

  @Test
  fun observe_severalDevices_shareTheLimitAndGroupByDevice() = runTest {
    val mac = FakeConnections(snapshot(connection(1, "a", down = 10)))
    val nas = FakeConnections(snapshot(connection(1, "a", up = 10)))
    val sources = listOf(source("mac", mac), source("nas", nas))
    val model = ConnectionGridModel(flowOf(sources), flowOf(emptyList()), backgroundScope)

    val state = model.observe(512).first { it.devices.size == 2 }

    assertEquals(listOf(256), mac.limits)
    assertEquals(listOf(256), nas.limits)
    assertEquals(listOf("mac", "nas"), state.devices.map { it.deviceId })
    assertEquals(
      listOf(ConnectionCellKey("mac", 1), ConnectionCellKey("nas", 1)),
      state.cells.map { it.key },
    )
    assertEquals(1, state.downloading)
    assertEquals(1, state.uploading)
  }

  @Test
  fun observe_unsupportedAndOfflineDevices_areNotAskedAndUnsupportedAreListed() = runTest {
    val mac = FakeConnections(snapshot(connection(1, "a", down = 10)))
    val old = FakeConnections(snapshot(connection(1, "a")))
    val away = FakeConnections(snapshot(connection(1, "a")))
    val sources = listOf(
      source("mac", mac),
      source("old", old, supported = false),
      source("away", away, online = false),
    )
    val model = ConnectionGridModel(flowOf(sources), flowOf(emptyList()), backgroundScope)

    val state = model.observe(36).first { it.total > 0 }

    assertEquals(listOf("mac"), state.devices.map { it.deviceId })
    assertEquals(listOf(verbatim("old")), state.unsupported)
    // The whole limit goes to the one device asked.
    assertEquals(listOf(36), mac.limits)
    assertTrue(old.limits.isEmpty())
    assertTrue(away.limits.isEmpty())
  }

  @Test
  fun observe_noDeviceSupportsIt_saysSoWithoutAskingAny() = runTest {
    val old = FakeConnections(snapshot(connection(1, "a")))
    val model = ConnectionGridModel(
      flowOf(listOf(source("old", old, supported = false))),
      flowOf(emptyList()),
      backgroundScope,
    )

    val state = model.observe(36).first { it.unsupported.isNotEmpty() }

    assertFalse(state.supported)
    assertTrue(state.cells.isEmpty())
    assertTrue(old.limits.isEmpty())
  }

  @Test
  fun observe_oneDeviceFails_keepsTheOthersAndAsksItAgainAfterABackoff() = runTest {
    val mac = FakeConnections(snapshot(connection(1, "a", down = 10)))
    var attempts = 0
    val nas = FakeConnections { limit ->
      attempts++
      if (attempts == 1) {
        flow { throw IllegalStateException("Connection lost") }
      } else {
        flow {
          emit(snapshot(connection(7, "n", down = 10)))
          awaitCancellation()
        }
      }.also { check(limit > 0) }
    }
    val model = ConnectionGridModel(
      flowOf(listOf(source("mac", mac), source("nas", nas))),
      flowOf(emptyList()),
      backgroundScope,
    )
    val states = collect(model.observe(36))

    runCurrent()
    assertEquals(listOf("mac"), states.value.devices.map { it.deviceId })
    assertEquals(1, attempts)

    advanceTimeBy(999.milliseconds)
    runCurrent()
    assertEquals(1, attempts)
    advanceTimeBy(1.milliseconds)
    runCurrent()

    assertEquals(2, attempts)
    assertEquals(listOf("mac", "nas"), states.value.devices.map { it.deviceId })
  }

  @Test
  fun observe_streamEnds_isAskedAgainAndEmptyMeanwhile() = runTest {
    var attempts = 0
    val api = FakeConnections { _ ->
      attempts++
      flowOf(snapshot(connection(attempts.toLong(), "a", down = 10)))
    }
    val model = model(source("mac", api))
    val states = collect(model.observe(36))

    runCurrent()
    assertEquals(1, attempts)
    assertTrue(states.value.cells.isEmpty())

    advanceTimeBy(1.seconds)
    runCurrent()
    assertEquals(2, attempts)
  }

  @Test
  fun observe_deviceRefusesAsUnsupported_isListedAndNotAskedAgain() = runTest {
    var attempts = 0
    val api = FakeConnections { _ ->
      attempts++
      flow { throw UnsupportedOperationException("Not supported") }
    }
    val model = model(source("nas", api))
    val states = collect(model.observe(36))

    advanceTimeBy(1.minutes)
    runCurrent()

    assertEquals(1, attempts)
    assertEquals(listOf(verbatim("nas")), states.value.unsupported)
    assertFalse(states.value.supported)
  }

  @Test
  fun state_collectorsLeave_stopsTheStreamAfterFiveSeconds() = runTest {
    val api = FakeConnections(snapshot(connection(1, "a", down = 10)))
    val model = model(source("mac", api))

    val strip = model.state(36)
    assertTrue(strip === model.state(36), "The same limit shares one stream")
    runCurrent()
    assertEquals(0, api.active, "Nothing is asked before the grid shows")

    val job = launch { strip.collect {} }
    runCurrent()
    assertEquals(1, api.active)

    job.cancel()
    advanceTimeBy(4.seconds)
    runCurrent()
    assertEquals(1, api.active)
    advanceTimeBy(1.seconds + 1.milliseconds)
    runCurrent()
    assertEquals(0, api.active)
  }

  @Test
  fun observe_deviceGoesOnline_isAskedThen() = runTest {
    val api = FakeConnections(snapshot(connection(1, "a", down = 10)))
    val sources = MutableStateFlow(listOf(source("nas", api, online = false)))
    val model = ConnectionGridModel(sources, flowOf(emptyList()), backgroundScope)
    val states = collect(model.observe(36))

    runCurrent()
    assertEquals(0, api.active)

    sources.value = listOf(source("nas", api, online = true))
    runCurrent()

    assertEquals(1, api.active)
    assertEquals(1, states.value.total)
  }

  private fun TestScope.model(vararg sources: ConnectionSource) =
    ConnectionGridModel(flowOf(sources.toList()), flowOf(emptyList()), backgroundScope)

  private fun TestScope.collect(
    flow: Flow<ConnectionGridState>,
  ): MutableStateFlow<ConnectionGridState> {
    val latest = MutableStateFlow(ConnectionGridState())
    backgroundScope.launch { flow.collect { latest.value = it } }
    return latest
  }

  private fun row(id: String): TaskRow =
    ListFixtures.row(id, DownloadState.Queued, deviceId = "mac")

  private fun source(
    id: String,
    api: KetchApi,
    supported: Boolean = true,
    online: Boolean = true,
  ) = ConnectionSource(id, verbatim(id), api, supported, online)

  private fun snapshot(
    vararg connections: ActiveConnection,
    total: Int = connections.size,
    down: Long = connections.sumOf { it.downloadBps },
    up: Long = connections.sumOf { it.uploadBps },
  ) = ActiveConnections(
    sampledAt = NOW,
    connections = connections.toList(),
    total = total,
    downloadBps = down,
    uploadBps = up,
  )

  private fun connection(
    id: Long,
    taskId: String,
    down: Long = 0,
    up: Long = 0,
    openedSecondsAgo: Long = 10,
  ) = ActiveConnection(
    id = id,
    taskId = taskId,
    source = "http",
    host = "example.com",
    port = 443,
    downloadBps = down,
    uploadBps = up,
    openedAt = NOW - openedSecondsAgo.seconds,
  )

  /** Answers [activeConnections] from [stream], counting the streams asked for and running. */
  private class FakeConnections(
    private val stream: (Int) -> Flow<ActiveConnections>,
  ) : KetchApi by FakeKetchApi() {
    /** A device whose stream sends [snapshot] and stays open. */
    constructor(snapshot: ActiveConnections) : this({
      flow {
        emit(snapshot)
        awaitCancellation()
      }
    })

    /** The limit of each stream asked for, in order. */
    val limits = mutableListOf<Int>()

    /** Streams running now. */
    var active = 0
      private set

    override fun activeConnections(limit: Int): Flow<ActiveConnections> = flow {
      limits += limit
      active++
      try {
        emitAll(stream(limit))
      } finally {
        active--
      }
    }
  }

  private companion object {
    val NOW: Instant = Instant.parse("2026-10-01T14:30:00Z")
    const val KIB = 1024L
    const val MIB = 1024L * 1024
  }
}
