package com.linroid.ketch.app.ui.devices

import com.linroid.ketch.api.DownloadPriority
import com.linroid.ketch.api.DownloadProgress
import com.linroid.ketch.api.DownloadRequest
import com.linroid.ketch.api.DownloadSchedule
import com.linroid.ketch.api.DownloadState
import com.linroid.ketch.api.DownloadTask
import com.linroid.ketch.api.SpeedLimit
import com.linroid.ketch.app.FakeKetchApi
import com.linroid.ketch.app.i18n.load
import com.linroid.ketch.app.i18n.verbatim
import com.linroid.ketch.app.instance.DevicePresence
import com.linroid.ketch.app.instance.EmbeddedInstance
import com.linroid.ketch.app.instance.InstanceEntry
import com.linroid.ketch.app.instance.RemoteInstance
import com.linroid.ketch.app.state.DeviceHealth
import com.linroid.ketch.app.state.ListTestTask
import com.linroid.ketch.app.state.PulseCounts
import com.linroid.ketch.app.state.SpeedMode
import com.linroid.ketch.config.RemoteConfig
import com.linroid.ketch.remote.ConnectionState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

class DeviceWorkTest {

  private val now = Instant.parse("2026-10-01T14:30:00Z")
  private val nasEntry = RemoteInstance(
    instance = FakeKetchApi("NAS"),
    remoteConfig = RemoteConfig(host = "nas.local"),
    connectionState = MutableStateFlow(ConnectionState.Connected),
  )

  private fun task(
    id: String,
    state: DownloadState,
    minutesAgo: Int = 0,
    priority: DownloadPriority = DownloadPriority.NORMAL,
  ): Pair<DownloadTask, DownloadState> {
    val request = DownloadRequest("https://example.com/$id.iso", priority = priority)
    return ListTestTask(id, state, request, now - minutesAgo.minutes) to state
  }

  private fun downloading(downloaded: Long, total: Long, speed: Long = 100) =
    DownloadState.Downloading(DownloadProgress(downloaded, total, speed))

  private fun device(
    name: String = "This Mac",
    entry: InstanceEntry = EmbeddedInstance(FakeKetchApi(), name),
    health: DeviceHealth = DeviceHealth.Local(),
    connected: Boolean = true,
    counts: PulseCounts = PulseCounts(),
    failures: Int = 0,
    speed: Long = 0,
  ) = DevicePresence(
    entry = entry,
    name = verbatim(name),
    detail = name,
    health = health,
    connected = connected,
    watched = true,
    status = null,
    statusAt = null,
    lastSeen = null,
    speed = speed,
    counts = counts,
    failures = failures,
    unseenFailures = 0,
    cap = SpeedLimit.Unlimited,
    disk = null,
    speedMode = SpeedMode.Full,
    history = emptyList(),
  )

  @Test
  fun deviceWork_mixedTasks_sumsTheDownloadingOnesOldestFirst() {
    val work = deviceWork(
      listOf(
        task("newer", downloading(300, 1000), minutesAgo = 1),
        task("older", downloading(100, 500), minutesAgo = 5),
        task("unknown", downloading(50, -1), minutesAgo = 3),
        task("done", DownloadState.Completed("/tmp/done.iso", totalBytes = 10)),
      )
    )

    assertEquals(
      listOf(LaneBlock("older", 400), LaneBlock("unknown", null), LaneBlock("newer", 700)),
      work.blocks
    )
    assertEquals(400, work.downloadedBytes)
    assertEquals(1500, work.sizeBytes)
    assertEquals(false, work.sizesKnown)
    assertEquals(1100, work.pendingBytes)
  }

  @Test
  fun nextWaiting_queuedAndScheduled_picksTheQueuedTaskOfHighestPriority() {
    val scheduled = DownloadState.Scheduled(DownloadSchedule.AtTime(now))
    val tasks = listOf(
      task("later", scheduled, minutesAgo = 9, priority = DownloadPriority.URGENT),
      task("old", DownloadState.Queued, minutesAgo = 8),
      task("high", DownloadState.Queued, minutesAgo = 2, priority = DownloadPriority.HIGH),
      task("running", downloading(1, 10))
    )

    assertEquals("high", nextWaiting(tasks)?.taskId)
    assertEquals("later", nextWaiting(tasks.take(1))?.taskId)
    assertNull(nextWaiting(tasks.takeLast(1)))
  }

  @Test
  fun laneShares_blocks_followRemainingBytesWithAFloor() {
    val shares = laneShares(
      listOf(LaneBlock("a", 900), LaneBlock("b", 100), LaneBlock("c", 0))
    )

    assertEquals(3, shares.size)
    assertEquals(1f, shares.sum(), 0.001f)
    assertTrue(shares[0] > shares[1] && shares[1] > shares[2])
    assertTrue(shares[2] > 0.03f)
  }

  @Test
  fun laneShares_unknownSizes_takeTheAverage() {
    val shares = laneShares(listOf(LaneBlock("a", 400), LaneBlock("b", null)))

    assertEquals(listOf(0.5f, 0.5f), shares)
    assertEquals(listOf(1f), laneShares(listOf(LaneBlock("only", null))))
    assertEquals(emptyList(), laneShares(emptyList()))
  }

  @Test
  fun nextActions_busyDeviceWithFailures_offersEveryAction() = runTest {
    val waiting = task("blender-4.2-macos-arm64", DownloadState.Queued).first
    val actions = nextActions(
      device(counts = PulseCounts(downloading = 1, waiting = 1, failed = 2), failures = 1),
      DeviceWork(next = waiting),
    )

    assertEquals(
      listOf("Retry 1 failed", "Pause all here", "Start blender-4.2-macos-a… now"),
      actions.map { it.label }.load()
    )
  }

  @Test
  fun nextActions_idleDevice_offersNothing() {
    assertEquals(emptyList(), nextActions(device(counts = PulseCounts(done = 4)), DeviceWork()))
  }

  @Test
  fun fleetSentence_twoBusyDevices_countsFilesAndFinish() = runTest {
    val mac = device(counts = PulseCounts(downloading = 2), speed = 1000)
    val nas = device(
      name = "NAS-Basement",
      entry = nasEntry,
      health = DeviceHealth.Live,
      counts = PulseCounts(downloading = 1),
      speed = 1000,
    )
    val work = mapOf(
      mac.deviceId to DeviceWork(downloadedBytes = 0, sizeBytes = 60_000),
      nas.deviceId to DeviceWork(downloadedBytes = 0, sizeBytes = 60_000),
    )

    val sentence = fleetSentence(listOf(mac, nas), work, SpeedMode.Full, now, TimeZone.UTC)

    assertEquals("Downloading 3 files on 2 devices · all done ≈ 14:31", sentence.load())
  }

  @Test
  fun fleetSentence_deviceNotKeptConnected_isLeftOut() = runTest {
    val mac = device(counts = PulseCounts(done = 1))
    val nas = device(
      name = "NAS",
      entry = nasEntry,
      health = DeviceHealth.Offline(),
      connected = false,
    )

    val sentence = fleetSentence(listOf(mac, nas), emptyMap(), SpeedMode.Full, now, TimeZone.UTC)

    assertEquals("All quiet", sentence.load())
  }
}
