package com.linroid.ketch.app.state

import com.linroid.ketch.api.DownloadConfig
import com.linroid.ketch.api.DownloadProgress
import com.linroid.ketch.api.DownloadRequest
import com.linroid.ketch.api.DownloadSchedule
import com.linroid.ketch.api.DownloadState
import com.linroid.ketch.api.DownloadTask
import com.linroid.ketch.api.KetchError
import com.linroid.ketch.api.ResolvedSource
import com.linroid.ketch.api.SpeedLimit
import com.linroid.ketch.app.instance.ServerState
import com.linroid.ketch.app.testStatus
import com.linroid.ketch.app.testSystem
import com.linroid.ketch.config.ServerConfig
import com.linroid.ketch.remote.ConnectionState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class PulseStateTest {

  private val gb = 1024L * 1024 * 1024
  private val mb = 1024L * 1024
  private val now = Instant.parse("2026-10-01T14:31:00Z")

  private val allStates: List<DownloadState> = listOf(
    DownloadState.Scheduled(DownloadSchedule.AfterDelay(30.minutes)),
    DownloadState.Queued,
    DownloadState.Downloading(DownloadProgress(100, 1000, 50)),
    DownloadState.Paused(DownloadProgress(100, 1000)),
    DownloadState.Completed("/downloads/file", totalBytes = 1000),
    DownloadState.Failed(KetchError.Network()),
    DownloadState.Canceled
  )

  private fun fakeTask(
    id: String,
    state: DownloadState,
    request: DownloadRequest = DownloadRequest(url = "https://example.com/$id"),
  ) = ListTestTask(id, state, request, Instant.fromEpochMilliseconds(0))

  private class FakeDevice(
    val id: String,
    tasks: List<DownloadTask> = emptyList(),
    usableSpace: Long = 0,
  ) {
    val tasks = MutableStateFlow(tasks)
    val config = MutableStateFlow<DownloadConfig?>(DownloadConfig())
    val health = MutableStateFlow<DeviceHealth>(DeviceHealth.Local())
    var usableSpace = usableSpace
    var statusCalls = 0

    fun source(): PulseSource = PulseSource(
      deviceId = id,
      name = id,
      tasks = tasks,
      config = config,
      status = {
        statusCalls++
        testStatus(
          name = id,
          system = testSystem(
            totalSpace = usableSpace * 2,
            freeSpace = usableSpace,
            usableSpace = usableSpace,
          ),
        )
      },
      health = health,
    )
  }

  private fun downloading(downloaded: Long, total: Long, speed: Long) =
    DownloadState.Downloading(DownloadProgress(downloaded, total, speed))

  private fun TestScope.model(
    vararg devices: FakeDevice,
    scope: PulseScope = PulseScope.AllDevices,
    mode: SpeedMode = SpeedMode.Full,
  ): PulseModel {
    val model = PulseModel(
      sources = MutableStateFlow(devices.map { it.source() }),
      pulseScope = MutableStateFlow(scope),
      mode = MutableStateFlow(mode),
      scope = backgroundScope,
    )
    runCurrent()
    return model
  }

  private fun device(
    name: String = "This Mac",
    health: DeviceHealth = DeviceHealth.Local(),
    counts: PulseCounts = PulseCounts(),
    failures: Int = 0,
    speed: Long = 0,
    cap: SpeedLimit = SpeedLimit.Unlimited,
    downloadedBytes: Long = 0,
    sizeBytes: Long = 0,
    sizesKnown: Boolean = true,
    pendingBytes: Long = 0,
    disk: DiskSpace? = null,
    history: List<Long> = emptyList(),
  ) = DevicePulse(
    deviceId = name,
    name = name,
    health = health,
    counts = counts,
    failures = failures,
    speed = speed,
    cap = cap,
    downloadedBytes = downloadedBytes,
    sizeBytes = sizeBytes,
    sizesKnown = sizesKnown,
    pendingBytes = pendingBytes,
    disk = disk,
    history = history,
  )

  private fun PulseState.sentenceAtNow(): String = sentence(now, TimeZone.UTC)

  @Test
  fun state_tasksInEveryState_countsMatchStatusFilter() = runTest {
    val tasks = allStates.mapIndexed { index, state -> fakeTask("t$index", state) }
    val model = model(FakeDevice("local", tasks))

    val counts = model.state.value.counts
    StatusFilter.entries.forEach { filter ->
      assertEquals(allStates.count(filter::matches), counts.count(filter), "count of $filter")
    }
    assertEquals(1, model.state.value.failures)
  }

  @Test
  fun state_stateChange_updatesCounts() = runTest {
    val task = fakeTask("a", DownloadState.Queued)
    val model = model(FakeDevice("local", listOf(task)))

    task.state.value = downloading(0, 1000, 10)
    advanceTimeBy(PulseModel.UPDATE_INTERVAL)
    runCurrent()

    assertEquals(PulseCounts(downloading = 1), model.state.value.counts)
  }

  @Test
  fun state_downloadingTasks_sumsSpeedAcrossDevices() = runTest {
    val local = FakeDevice("local", listOf(fakeTask("a", downloading(0, 1000, 100))))
    val nas = FakeDevice("nas", listOf(fakeTask("b", downloading(0, 1000, 200))))
    nas.health.value = DeviceHealth.Live
    val model = model(local, nas)

    assertEquals(300, model.state.value.totalSpeed)
    assertTrue(model.state.value.allDevices)
  }

  @Test
  fun state_offlineDevice_reportsNoSpeed() = runTest {
    val nas = FakeDevice("nas", listOf(fakeTask("b", downloading(0, 1000, 200))))
    nas.health.value = DeviceHealth.Offline()
    val model = model(nas)

    assertEquals(0, model.state.value.totalSpeed)
    assertEquals(1, model.state.value.counts.downloading)
  }

  @Test
  fun state_deviceScope_onlySumsThatDevice() = runTest {
    val local = FakeDevice("local", listOf(fakeTask("a", downloading(0, 1000, 100))))
    val nas = FakeDevice("nas", listOf(fakeTask("b", downloading(0, 1000, 200))))
    val model = model(local, nas, scope = PulseScope.Device("nas"))

    assertEquals(listOf("nas"), model.state.value.devices.map { it.deviceId })
    assertEquals(200, model.state.value.totalSpeed)
    assertFalse(model.state.value.allDevices)
  }

  @Test
  fun state_configChange_updatesCap() = runTest {
    val local = FakeDevice("local")
    val model = model(local)
    assertEquals(SpeedLimit.Unlimited, model.state.value.cap)

    local.config.value = DownloadConfig(speedLimit = SpeedLimit.mbps(5))
    advanceTimeBy(PulseModel.UPDATE_INTERVAL)
    runCurrent()

    assertEquals(SpeedLimit.mbps(5), model.state.value.cap)
  }

  @Test
  fun state_mode_passedThrough() = runTest {
    val model = model(FakeDevice("local"), mode = SpeedMode.SlowLane)

    assertEquals(SpeedMode.SlowLane, model.state.value.mode)
  }

  @Test
  fun history_whileDownloading_keepsLastSixtySamples() = runTest {
    val task = fakeTask("a", downloading(0, 1000, 100))
    val model = model(FakeDevice("local", listOf(task)))

    advanceTimeBy(70.seconds)
    runCurrent()

    val history = model.state.value.history
    assertEquals(PulseModel.HISTORY_SIZE, history.size)
    assertTrue(history.all { it == 100L })
  }

  @Test
  fun history_idle_staysEmpty() = runTest {
    val model = model(FakeDevice("local", listOf(fakeTask("a", DownloadState.Queued))))

    advanceTimeBy(10.seconds)
    runCurrent()

    assertEquals(emptyList(), model.state.value.history)
  }

  @Test
  fun history_downloadsStop_recordsZeros() = runTest {
    val task = fakeTask("a", downloading(0, 1000, 100))
    val model = model(FakeDevice("local", listOf(task)))
    advanceTimeBy(3.seconds)
    runCurrent()

    task.state.value = DownloadState.Paused(DownloadProgress(500, 1000))
    advanceTimeBy(2.seconds)
    runCurrent()

    assertEquals(listOf(100L, 100L, 100L, 0L, 0L), model.state.value.history)
  }

  @Test
  fun disk_polledAtStartPeriodicallyAndAfterCompletion() = runTest {
    val task = fakeTask("a", downloading(0, 1000, 100))
    val local = FakeDevice("local", listOf(task), usableSpace = 412 * gb)
    val model = model(local)
    assertEquals(1, local.statusCalls)
    assertEquals(412 * gb, model.state.value.devices.single().disk?.usableBytes)

    advanceTimeBy(PulseModel.DISK_POLL_INTERVAL)
    runCurrent()
    assertEquals(2, local.statusCalls)

    task.state.value = DownloadState.Completed("/downloads/a", totalBytes = 1000)
    advanceTimeBy(PulseModel.UPDATE_INTERVAL)
    runCurrent()
    assertEquals(3, local.statusCalls)
  }

  @Test
  fun disk_deviceOffline_notPolled() = runTest {
    val nas = FakeDevice("nas", usableSpace = gb)
    nas.health.value = DeviceHealth.Offline()
    val model = model(nas)

    advanceTimeBy(PulseModel.DISK_POLL_INTERVAL * 2)
    runCurrent()

    assertEquals(0, nas.statusCalls)
    assertNull(model.state.value.devices.single().disk)
  }

  @Test
  fun disk_unreadableDisk_leftUnknown() = runTest {
    val resolved = ResolvedSource(
      url = "https://example.com/big.iso",
      sourceType = "http",
      totalBytes = gb,
      supportsResume = true,
      suggestedFileName = "big.iso",
      maxSegments = 4,
    )
    val request = DownloadRequest(url = resolved.url, resolvedSource = resolved)
    val local = FakeDevice("local", listOf(fakeTask("a", DownloadState.Queued, request)))
    val model = model(local)

    assertEquals(1, local.statusCalls)
    assertNull(model.state.value.devices.single().disk)
    assertFalse(model.state.value.isDiskShort)
    assertEquals("All quiet", model.state.value.sentence(now, TimeZone.UTC))
  }

  @Test
  fun devices_deviceScope_listsEveryDevice() = runTest {
    val local = FakeDevice("local", listOf(fakeTask("a", downloading(0, 1000, 100))))
    val nas = FakeDevice("nas", listOf(fakeTask("b", downloading(0, 1000, 200))))
    val model = model(local, nas, scope = PulseScope.Device("nas"))

    assertEquals(listOf("local", "nas"), model.devices.value.map { it.deviceId })
    assertEquals(listOf(100L, 200L), model.devices.value.map { it.speed })
  }

  @Test
  fun state_waitingTasksLargerThanDisk_diskShort() = runTest {
    val resolved = ResolvedSource(
      url = "https://example.com/big.iso",
      sourceType = "http",
      totalBytes = 2 * gb,
      supportsResume = true,
      suggestedFileName = "big.iso",
      maxSegments = 4,
    )
    val request = DownloadRequest(url = resolved.url, resolvedSource = resolved)
    val task = fakeTask("a", DownloadState.Queued, request)
    val model = model(FakeDevice("local", listOf(task), usableSpace = gb))

    assertEquals(2 * gb, model.state.value.devices.single().pendingBytes)
    assertTrue(model.state.value.isDiskShort)
  }

  @Test
  fun sentence_idleWithDisk_namesFreeSpace() {
    val disk = DiskSpace(usableBytes = 412 * gb, totalBytes = 1000 * gb, directory = "/d")
    val state = PulseState(listOf(device(disk = disk)))

    assertEquals("All quiet · 412 GB free on This Mac", state.sentenceAtNow())
  }

  @Test
  fun sentence_idleAcrossDevices_namesDeviceWithLeastSpace() {
    val roomy = DiskSpace(usableBytes = 2 * 1024 * gb, totalBytes = 4096 * gb, directory = "/d")
    val tight = DiskSpace(usableBytes = 8 * gb + 200 * mb, totalBytes = 64 * gb, directory = "/d")
    val state = PulseState(
      devices = listOf(device(disk = roomy), device(name = "NAS-Basement", disk = tight)),
      allDevices = true,
    )

    assertEquals("All quiet · 8.2 GB free on NAS-Basement", state.sentenceAtNow())
  }

  @Test
  fun sentence_idleWithoutDisk_isAllQuiet() {
    assertEquals("All quiet", PulseState(listOf(device())).sentenceAtNow())
  }

  @Test
  fun sentence_downloading_estimatesFinishTime() {
    val state = PulseState(
      listOf(
        device(
          counts = PulseCounts(downloading = 3),
          speed = 10 * mb,
          downloadedBytes = 400 * mb,
          sizeBytes = 1000 * mb,
        )
      )
    )

    assertEquals("Downloading 3 files · all done ≈ 14:32", state.sentenceAtNow())
  }

  @Test
  fun sentence_downloadingOnTwoDevices_namesDeviceCount() {
    val state = PulseState(
      devices = listOf(
        device(counts = PulseCounts(downloading = 2), speed = 5 * mb, sizeBytes = 300 * mb),
        device(
          name = "NAS-Basement",
          health = DeviceHealth.Live,
          counts = PulseCounts(downloading = 1),
          speed = 5 * mb,
          sizeBytes = 300 * mb,
        )
      ),
      allDevices = true,
    )

    assertEquals(
      "Downloading 3 files on 2 devices · all done ≈ 14:32",
      state.sentenceAtNow()
    )
  }

  @Test
  fun sentence_slowerSecondDevice_finishesWhenItDoes() {
    val state = PulseState(
      devices = listOf(
        device(counts = PulseCounts(downloading = 2), speed = 5 * mb, sizeBytes = 300 * mb),
        device(
          name = "NAS-Basement",
          health = DeviceHealth.Live,
          counts = PulseCounts(downloading = 1),
          speed = mb,
          sizeBytes = 300 * mb,
        )
      ),
      allDevices = true,
    )

    assertEquals(
      "Downloading 3 files on 2 devices · all done ≈ 14:36",
      state.sentenceAtNow()
    )
  }

  @Test
  fun sentence_capBelowSpeed_estimatesWithCap() {
    val state = PulseState(
      listOf(
        device(
          counts = PulseCounts(downloading = 1),
          speed = 10 * mb,
          cap = SpeedLimit.mbps(1),
          sizeBytes = 600 * mb,
        )
      )
    )

    assertEquals("Downloading 1 file · all done ≈ 14:41", state.sentenceAtNow())
  }

  @Test
  fun sentence_unknownSize_omitsFinishTime() {
    val state = PulseState(
      listOf(device(counts = PulseCounts(downloading = 1), speed = mb, sizesKnown = false))
    )

    assertEquals("Downloading 1 file", state.sentenceAtNow())
  }

  @Test
  fun sentence_finishTomorrow_namesWeekday() {
    val state = PulseState(
      listOf(device(counts = PulseCounts(downloading = 1), speed = mb, sizeBytes = 36_000 * mb))
    )

    assertEquals("Downloading 1 file · all done ≈ Fri 00:31", state.sentenceAtNow())
  }

  @Test
  fun sentence_failures_needAttention() {
    val one = PulseState(listOf(device(counts = PulseCounts(failed = 2), failures = 1)))
    val two = PulseState(listOf(device(counts = PulseCounts(failed = 2), failures = 2)))

    assertEquals("1 download needs attention", one.sentenceAtNow())
    assertEquals("2 downloads need attention", two.sentenceAtNow())
  }

  @Test
  fun sentence_onlyCanceledTasks_isAllQuiet() {
    val state = PulseState(listOf(device(counts = PulseCounts(failed = 1), failures = 0)))

    assertEquals("All quiet", state.sentenceAtNow())
  }

  @Test
  fun sentence_offlineDevice_saysRetrying() {
    val state = PulseState(
      listOf(device(name = "NAS-Basement", health = DeviceHealth.Offline(), failures = 1))
    )

    assertEquals("NAS-Basement is offline · retrying", state.sentenceAtNow())
  }

  @Test
  fun sentence_offlineDeviceWhileAnotherDownloads_reportsDownloads() {
    val state = PulseState(
      devices = listOf(
        device(counts = PulseCounts(downloading = 1), speed = mb, sizesKnown = false),
        device(name = "NAS-Basement", health = DeviceHealth.Offline())
      ),
      allDevices = true,
    )

    assertEquals("Downloading 1 file", state.sentenceAtNow())
  }

  @Test
  fun sentence_slowLaneByHand_appendsSlowLane() {
    val state = PulseState(listOf(device()), mode = SpeedMode.SlowLane)

    assertEquals("All quiet · Slow lane", state.sentenceAtNow())
  }

  @Test
  fun sentence_slowLaneByRule_appendsEndTime() {
    val mode = SpeedMode.Auto(slowLane = true, until = Instant.parse("2026-10-01T18:00:00Z"))
    val state = PulseState(listOf(device(failures = 1)), mode = mode)

    assertEquals("1 download needs attention · Slow lane until 18:00", state.sentenceAtNow())
  }

  @Test
  fun sentence_autoOutsideRule_appendsNothing() {
    val mode = SpeedMode.Auto(slowLane = false, until = Instant.parse("2026-10-01T18:00:00Z"))

    assertEquals("All quiet", PulseState(listOf(device()), mode = mode).sentenceAtNow())
  }

  @Test
  fun shortSentence_downloading_countsAndPercent() {
    val state = PulseState(
      listOf(
        device(
          counts = PulseCounts(downloading = 3),
          speed = mb,
          downloadedBytes = 45,
          sizeBytes = 100,
        )
      )
    )

    assertEquals("3 downloading · 45%", state.shortSentence())
  }

  @Test
  fun tabTitle_downloadingAndIdle_showsTheShareOrTheName() {
    val downloading = PulseState(
      listOf(device(counts = PulseCounts(downloading = 3), downloadedBytes = 45, sizeBytes = 100))
    )

    assertEquals("↓ 45% · Ketch", downloading.tabTitle())
    assertEquals("Ketch", PulseState(listOf(device(counts = PulseCounts(done = 2)))).tabTitle())
  }

  @Test
  fun shortSentence_offlineDeviceDownloading_ignoresItsProgress() {
    val state = PulseState(
      devices = listOf(
        device(counts = PulseCounts(downloading = 1), downloadedBytes = 45, sizeBytes = 100),
        device(
          name = "NAS-Basement",
          health = DeviceHealth.Offline(),
          counts = PulseCounts(downloading = 2),
          downloadedBytes = 0,
          sizeBytes = 900,
        )
      ),
      allDevices = true,
    )

    assertEquals("1 downloading · 45%", state.shortSentence())
    assertEquals(0.45f, state.progress)
  }

  @Test
  fun shortSentence_idle_isNull() {
    assertNull(PulseState(listOf(device(counts = PulseCounts(done = 3)))).shortSentence())
  }

  @Test
  fun history_twoDevices_sumsByAge() {
    val state = PulseState(
      devices = listOf(device(history = listOf(1, 2, 3)), device(history = listOf(10, 20))),
      allDevices = true,
    )

    assertEquals(listOf(1L, 12L, 23L), state.history)
  }

  @Test
  fun toDeviceHealth_connectionStates_mapToHealth() {
    assertEquals(DeviceHealth.Live, ConnectionState.Connected.toDeviceHealth())
    assertEquals(DeviceHealth.Connecting, ConnectionState.Connecting.toDeviceHealth())
    assertEquals(
      DeviceHealth.Offline("refused"),
      ConnectionState.Disconnected("refused").toDeviceHealth()
    )
    assertEquals(DeviceHealth.Unauthorized, ConnectionState.Unauthorized.toDeviceHealth())
  }

  @Test
  fun toDeviceHealth_serverStates_shareOnlyBeyondLoopback() {
    val shared = ServerState.Running(ServerConfig(port = 8642)).toDeviceHealth()
    val loopback = ServerState.Running(ServerConfig(host = "127.0.0.1")).toDeviceHealth()

    assertEquals(DeviceHealth.Local(sharingPort = 8642), shared)
    assertEquals(DeviceHealth.Local(), loopback)
    assertEquals(DeviceHealth.Local(), ServerState.Stopped.toDeviceHealth())
  }
}
