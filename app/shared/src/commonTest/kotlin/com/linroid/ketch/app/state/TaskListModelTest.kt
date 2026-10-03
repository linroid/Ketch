package com.linroid.ketch.app.state

import com.linroid.ketch.api.DownloadConfig
import com.linroid.ketch.api.DownloadProgress
import com.linroid.ketch.api.DownloadRequest
import com.linroid.ketch.api.DownloadState
import com.linroid.ketch.api.DownloadTask
import com.linroid.ketch.api.KetchError
import com.linroid.ketch.api.KetchFeatures
import com.linroid.ketch.app.i18n.load
import com.linroid.ketch.app.i18n.verbatim
import com.linroid.ketch.app.i18n.warmStrings
import com.linroid.ketch.app.state.ListFixtures.downloading
import com.linroid.ketch.app.util.RowStatus
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.TimeZone
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class TaskListModelTest {
  @BeforeTest
  fun loadStrings() = runTest { warmStrings() }

  private val nas = DeviceInfo(verbatim("NAS-Basement"), RowCapabilities.remote())

  @Test
  fun rows_twoDevices_keyEachTaskByItsDevice() = runTest {
    val local = ListTestTask("1", DownloadState.Queued)
    val remote = ListTestTask("1", DownloadState.Completed("/srv/a.iso", 10))
    val model = model(
      listOf(
        TaskListSource(LOCAL_DEVICE_ID, ListFixtures.device, flowOf(listOf(local))),
        TaskListSource("nas.local:8642", nas, flowOf(listOf(remote)))
      )
    )

    advanceTimeBy(300)

    val rows = model.rows.value
    assertEquals(
      listOf(TaskKey(LOCAL_DEVICE_ID, "1"), TaskKey("nas.local:8642", "1")),
      rows.map { it.key }
    )
    assertEquals("Saved on NAS-Basement", rows[1].content.detail.load())
    assertSame(remote, model.row(TaskKey("nas.local:8642", "1"))?.task)
  }

  @Test
  fun view_tabAndSearch_filterRowsAndCountMatches() = runTest {
    val tasks = listOf(
      task("ubuntu", downloading(10)),
      task("debian", DownloadState.Paused(DownloadProgress(5, 10))),
      task("ubuntu-old", DownloadState.Completed("/d/ubuntu-old.iso", 10)),
      task("fedora", DownloadState.Completed("/d/fedora.iso", 10)),
      task("arch", DownloadState.Failed(KetchError.Network())),
      task("mint", DownloadState.Canceled)
    )
    val filter = MutableStateFlow(StatusFilter.Done)
    val query = MutableStateFlow("ubuntu")
    val model = model(listOf(source(tasks)), filter, query)

    advanceTimeBy(300)
    val done = model.view.value
    filter.value = StatusFilter.All
    advanceTimeBy(1)
    val all = model.view.value
    query.value = "is:failed"
    advanceTimeBy(1)
    val failed = model.view.value

    assertEquals(listOf("ubuntu-old"), done.rows.map { it.key.taskId })
    assertEquals(2, done.total)
    assertEquals(1, done.matched)
    assertEquals(listOf("ubuntu", "ubuntu-old"), all.rows.map { it.key.taskId })
    assertEquals(6, all.total)
    assertEquals(listOf("arch", "mint"), failed.keys.map { it.taskId })
    assertEquals(
      mapOf(
        StatusFilter.All to 6,
        StatusFilter.Downloading to 1,
        StatusFilter.Waiting to 0,
        StatusFilter.Paused to 1,
        StatusFilter.Done to 2,
        StatusFilter.Failed to 2
      ),
      model.counts.value
    )
  }

  @Test
  fun rows_queuedTask_explainsWhyItWaits() = runTest {
    val tasks = listOf(task("running", downloading(10)), task("next", DownloadState.Queued))
    val config = DownloadConfig(maxConcurrentDownloads = 1)
    val model = model(
      listOf(TaskListSource(LOCAL_DEVICE_ID, ListFixtures.device, flowOf(tasks), flowOf(config)))
    )

    advanceTimeBy(300)

    assertEquals(
      "Waiting for a free slot (1 of 1 in use)",
      model.rows.value[1].content.detail.load()
    )
  }

  @Test
  fun rows_queuePositionChanges_rebuildsRow() = runTest {
    val waiting = task("waiting", DownloadState.Queued)
    waiting.queuePosition.value = 2
    val source = TaskListSource(
      deviceId = LOCAL_DEVICE_ID,
      device = ListFixtures.device,
      tasks = flowOf(listOf(waiting)),
      features = flowOf(KetchFeatures.ALL),
    )
    val model = model(listOf(source))
    advanceTimeBy(300)
    val before = model.rows.value.single()

    waiting.queuePosition.value = 1
    advanceTimeBy(300)
    val after = model.rows.value.single()

    assertEquals("Waiting to start · 1 ahead", before.content.detail.load())
    assertEquals("Waiting to start · next in line", after.content.detail.load())
    assertEquals(1, after.queuePosition)
  }

  @Test
  fun rows_noDataForMoreThanFiveSeconds_reportStalledWithoutNewStates() = runTest {
    val model = model(listOf(source(listOf(task("a", downloading(10))))))

    advanceTimeBy(4_000)
    val moving = model.rows.value.single()
    advanceTimeBy(3_000)
    val stalled = model.rows.value.single()

    assertEquals(RowStatus.Downloading, moving.content.status)
    assertEquals(RowStatus.Stalled, stalled.content.status)
    assertTrue(stalled.isStalled)
    assertTrue(stalled.content.detail.load().startsWith("Stalled · no data for "))
  }

  @Test
  fun rows_downloading_sampleSpeedOnceASecondUpToSixty() = runTest {
    val model = model(listOf(source(listOf(task("a", downloading(10, speed = 300))))))

    advanceTimeBy(3_100)
    val early = model.rows.value.single().speedSamples
    advanceTimeBy(70_000)
    val full = model.rows.value.single().speedSamples

    assertEquals(listOf(300L, 300L, 300L), early)
    assertEquals(TaskListModel.SPEED_SAMPLES, full.size)
  }

  @Test
  fun view_heldOrderWithNothingElseChanging_resortsOnceTheIntervalPasses() = runTest {
    val running = task("a", downloading(10))
    val model = model(listOf(source(listOf(running, task("b", completed("b"))))))

    advanceTimeBy(300)
    running.state.value = completed("a")
    advanceTimeBy(300)
    val held = model.view.value
    advanceTimeBy(3_000)
    val resorted = model.view.value

    assertEquals(listOf("Downloading", "Added today"), held.groups.map { it.title }.load())
    assertEquals(listOf("Added today"), resorted.groups.map { it.title }.load())
    assertEquals(listOf("a", "b"), resorted.keys.map { it.taskId })
  }

  @Test
  fun view_frozen_holdsOrderUntilReleased() = runTest {
    val running = task("a", downloading(10))
    val frozen = MutableStateFlow(true)
    val model = model(listOf(source(listOf(running, task("b", completed("b"))))), frozen = frozen)

    advanceTimeBy(300)
    running.state.value = completed("a")
    advanceTimeBy(5_000)
    val held = model.view.value
    frozen.value = false
    advanceTimeBy(1)
    val released = model.view.value

    assertEquals(listOf("Downloading", "Added today"), held.groups.map { it.title }.load())
    assertEquals(listOf("Added today"), released.groups.map { it.title }.load())
  }

  @Test
  fun rows_thousandTasksWithThirtyTicking_emitAtMostEvery250msAndKeepUnchangedRows() = runTest {
    val active = (0 until 30).map { task("a$it", downloading(0, total = TOTAL)) }
    val idle = (0 until 970).map { task("i$it", DownloadState.Completed("/d/i$it", 10)) }
    val model = model(listOf(source(active + idle)))
    val emissions = mutableListOf<Pair<Long, List<TaskRow>>>()
    val views = mutableListOf<Pair<Long, List<TaskRow>>>()
    backgroundScope.launch {
      model.rows.collect { if (it.isNotEmpty()) emissions += testScheduler.currentTime to it }
    }
    backgroundScope.launch {
      model.view.collect { if (it.rows.isNotEmpty()) views += testScheduler.currentTime to it.rows }
    }
    backgroundScope.launch {
      var bytes = 0L
      while (true) {
        delay(200)
        bytes += 1000
        active.forEach { it.state.value = downloading(bytes, total = TOTAL, speed = 5000) }
      }
    }

    // Long enough to cross a minute, when countdowns and dates are checked again.
    advanceTimeBy(65_000)

    assertTrue(emissions.size > 200, "emitted ${emissions.size} times")
    for ((previous, next) in emissions.zipWithNext()) {
      val gap = next.first - previous.first
      assertTrue(gap >= 250, "emitted ${gap}ms apart at ${next.first}")
      for (index in 30 until 1000) assertSame(previous.second[index], next.second[index])
      assertNotSame(previous.second[0], next.second[0])
    }
    assertTrue(views.size > 200, "view emitted ${views.size} times")
    for ((previous, next) in views.zipWithNext()) {
      assertTrue(next.first - previous.first >= 250, "view emitted at ${next.first}")
      // The idle rows follow the 30 downloading ones in the same order and as the same rows.
      for (index in 30 until 1000) assertSame(previous.second[index], next.second[index])
    }
  }

  private fun TestScope.model(
    sources: List<TaskListSource>,
    filter: Flow<StatusFilter> = flowOf(StatusFilter.All),
    query: Flow<String> = flowOf(""),
    frozen: Flow<Boolean> = flowOf(false),
  ) = TaskListModel(
    sources = flowOf(sources),
    scope = backgroundScope,
    filter = filter,
    query = query,
    frozen = frozen,
    clock = ListFixtures.clock(this),
    timeZone = { TimeZone.UTC },
    dispatcher = StandardTestDispatcher(testScheduler),
  )

  private fun source(tasks: List<DownloadTask>) =
    TaskListSource(LOCAL_DEVICE_ID, ListFixtures.device, flowOf(tasks))

  private fun task(id: String, state: DownloadState) =
    ListTestTask(id, state, DownloadRequest("https://example.com/$id.iso"))

  private fun completed(id: String) = DownloadState.Completed("/d/$id.iso", 10)

  private companion object {
    const val TOTAL = 1_000_000_000L
  }
}
