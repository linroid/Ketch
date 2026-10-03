package com.linroid.ketch.app.state

import com.linroid.ketch.api.DownloadProgress
import com.linroid.ketch.api.DownloadRequest
import com.linroid.ketch.api.DownloadSchedule
import com.linroid.ketch.api.DownloadState
import com.linroid.ketch.api.DownloadTask
import com.linroid.ketch.api.KetchError
import com.linroid.ketch.app.i18n.verbatim
import com.linroid.ketch.app.instance.ServerState
import com.linroid.ketch.config.ServerConfig
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
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
import kotlin.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class ForegroundPolicyTest {

  private val downloading = DownloadState.Downloading(
    DownloadProgress(downloadedBytes = 100, totalBytes = 1000, bytesPerSecond = 50)
  )
  private val running = ServerState.Running(ServerConfig(port = 9000, apiToken = "secret"))
  private val stopped: ServerState = ServerState.Stopped

  @Test
  fun evaluate_queuedOrDownloadingTask_isRequired() {
    assertTrue(ForegroundPolicy.evaluate(listOf(DownloadState.Queued), stopped).isRequired)
    assertTrue(ForegroundPolicy.evaluate(listOf(downloading), stopped).isRequired)
  }

  @Test
  fun evaluate_onlyIdleTasks_isNotRequired() {
    val idle = listOf(
      DownloadState.Scheduled(DownloadSchedule.AfterDelay(30.minutes)),
      DownloadState.Paused(DownloadProgress(downloadedBytes = 100, totalBytes = 1000)),
      DownloadState.Completed("/downloads/file.iso", totalBytes = 1000),
      DownloadState.Failed(KetchError.Network()),
      DownloadState.Canceled
    )
    assertFalse(ForegroundPolicy.evaluate(idle, stopped).isRequired)
  }

  @Test
  fun evaluate_serverRunningWithoutTasks_isRequiredWithPortOnly() {
    val status = ForegroundPolicy.evaluate(emptyList(), running)
    assertTrue(status.isRequired)
    assertEquals(ForegroundStatus(serverPort = 9000), status)
  }

  @Test
  fun evaluate_serverFailedWithoutTasks_isNotRequired() {
    val failed = ServerState.Failed(verbatim("Port in use"))
    assertFalse(ForegroundPolicy.evaluate(emptyList(), failed).isRequired)
  }

  @Test
  fun evaluate_mixedStates_countsDownloadingAndQueued() {
    val states = listOf(downloading, DownloadState.Queued, downloading, DownloadState.Canceled)
    val status = ForegroundPolicy.evaluate(states, stopped)
    assertEquals(2, status.downloading)
    assertEquals(1, status.queued)
  }

  @Test
  fun observe_lastTaskCompletes_releasesWithinOneSecond() = runTest {
    val task = fakeTask(downloading)
    val emitted = observe(MutableStateFlow(listOf(task)), MutableStateFlow(stopped))
    advanceTimeBy(ForegroundPolicy.samplePeriod)
    runCurrent()
    assertTrue(emitted.last().isRequired)

    advanceTimeBy(300.milliseconds)
    task.state.value = DownloadState.Completed("/downloads/file.iso", totalBytes = 1000)
    advanceTimeBy(ForegroundPolicy.samplePeriod)
    runCurrent()
    assertFalse(emitted.last().isRequired)
  }

  @Test
  fun observe_taskAddedLater_followsItsState() = runTest {
    val tasks = MutableStateFlow(emptyList<DownloadTask>())
    val emitted = observe(tasks, MutableStateFlow(stopped))
    advanceTimeBy(ForegroundPolicy.samplePeriod)
    runCurrent()
    assertFalse(emitted.last().isRequired)

    val task = fakeTask(DownloadState.Queued)
    tasks.value = listOf(task)
    advanceTimeBy(ForegroundPolicy.samplePeriod)
    runCurrent()
    assertEquals(ForegroundStatus(queued = 1), emitted.last())

    task.state.value = downloading
    advanceTimeBy(ForegroundPolicy.samplePeriod)
    runCurrent()
    assertEquals(ForegroundStatus(downloading = 1), emitted.last())
  }

  @Test
  fun observe_lastTaskRemoved_releases() = runTest {
    val tasks = MutableStateFlow<List<DownloadTask>>(listOf(fakeTask(downloading)))
    val emitted = observe(tasks, MutableStateFlow(stopped))
    advanceTimeBy(ForegroundPolicy.samplePeriod)
    runCurrent()
    assertTrue(emitted.last().isRequired)

    tasks.value = emptyList()
    advanceTimeBy(ForegroundPolicy.samplePeriod)
    runCurrent()
    assertEquals(ForegroundStatus(), emitted.last())
  }

  @Test
  fun observe_serverStartsAndStops_followsServer() = runTest {
    val server = MutableStateFlow<ServerState>(stopped)
    val emitted = observe(MutableStateFlow(emptyList()), server)
    server.value = running
    advanceTimeBy(ForegroundPolicy.samplePeriod)
    runCurrent()
    assertTrue(emitted.last().isRequired)

    server.value = stopped
    advanceTimeBy(ForegroundPolicy.samplePeriod)
    runCurrent()
    assertFalse(emitted.last().isRequired)
  }

  @Test
  fun observe_rapidStateChanges_emitsAtMostOncePerPeriod() = runTest {
    val task = fakeTask(DownloadState.Queued)
    val emitted = observe(MutableStateFlow(listOf(task)), MutableStateFlow(stopped))
    repeat(30) { step ->
      task.state.value = if (step % 2 == 0) downloading else DownloadState.Queued
      advanceTimeBy(100.milliseconds)
      runCurrent()
    }
    assertTrue(emitted.size <= 3, "emitted $emitted")
  }

  private fun TestScope.observe(
    tasks: Flow<List<DownloadTask>>,
    server: Flow<ServerState>,
  ): List<ForegroundStatus> {
    val emitted = mutableListOf<ForegroundStatus>()
    backgroundScope.launch {
      ForegroundPolicy.observe(tasks, server).collect { emitted += it }
    }
    runCurrent()
    return emitted
  }

  private fun fakeTask(state: DownloadState) = ListTestTask(
    "task",
    state,
    DownloadRequest(url = "https://example.com/file.iso"),
    Instant.fromEpochMilliseconds(0)
  )
}
