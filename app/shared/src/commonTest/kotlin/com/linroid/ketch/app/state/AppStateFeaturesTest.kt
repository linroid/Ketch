package com.linroid.ketch.app.state

import com.linroid.ketch.api.DownloadRequest
import com.linroid.ketch.api.DownloadState
import com.linroid.ketch.api.KetchApi
import com.linroid.ketch.api.KetchFeatures
import com.linroid.ketch.api.KetchStatus
import com.linroid.ketch.app.fixtureTest
import com.linroid.ketch.app.i18n.load
import com.linroid.ketch.app.i18n.warmStrings
import com.linroid.ketch.app.instance.InstanceEntry
import com.linroid.ketch.app.testStatus
import com.linroid.ketch.app.ui.inspector.autoConnectionsSupported
import com.linroid.ketch.app.ui.shell.Fleet
import com.linroid.ketch.app.ui.shell.FleetFixtures.NAS_ID
import com.linroid.ketch.app.ui.shell.fleet
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.TimeZone
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * What the app takes each device to support: everything for its own engine, and for a remote
 * device only what its status lists, nothing until that status has been read.
 */
class AppStateFeaturesTest {

  // Loading a string for the first time lets virtual time run.
  @BeforeTest
  fun loadStrings() = runTest { warmStrings() }

  /** A device with one slot whose status arrives when the test [answer]s it. */
  private class StatusApi(
    val recording: RecordingKetchApi = RecordingKetchApi(maxActive = 1),
  ) : KetchApi by recording {
    private val status = CompletableDeferred<KetchStatus>()

    fun answer(features: Set<String>) {
      status.complete(testStatus(recording.backendLabel, features = features))
    }

    override suspend fun status(): KetchStatus = status.await()
  }

  private fun TestScope.fleet() = fleet(StatusApi(), StatusApi())

  private fun featuresTest(block: suspend TestScope.(Fleet<StatusApi>) -> Unit) =
    fixtureTest({ fleet() }, { it.controller.close() }, block)

  /** Two tasks waiting behind the one running on [api], at positions 1 and 2. */
  private suspend fun waitInLine(api: StatusApi) {
    api.recording.add(DownloadState.Downloading(RecordingTask.PROGRESS))
    api.download(DownloadRequest("https://example.com/first.iso"))
    api.download(DownloadRequest("https://example.com/second.iso"))
  }

  /**
   * The waiting rows of [entry]'s device in a list over the features the app gives it; the
   * app's own list builds rows on another thread, which a test's virtual time cannot drive.
   */
  private fun TestScope.waitingRows(fleet: Fleet<*>, entry: InstanceEntry): TaskListModel {
    val source = TaskListSource(
      deviceId = entry.deviceId,
      device = ListFixtures.device,
      tasks = entry.instance.tasks,
      features = fleet.state.featuresFlow(entry),
    )
    return TaskListModel(
      sources = flowOf(listOf(source)),
      scope = backgroundScope,
      clock = ListFixtures.clock(this),
      timeZone = { TimeZone.UTC },
      dispatcher = StandardTestDispatcher(testScheduler),
    )
  }

  private suspend fun TaskListModel.waitingDetails(): List<String> = rows.value
    .filter { it.state is DownloadState.Queued }
    .sortedBy { it.key.taskId }
    .map { it.content.detail.load() }

  @Test
  fun featuresOf_embedded_isAll() = featuresTest { fleet ->
    assertEquals(KetchFeatures.ALL, fleet.state.featuresOf(LOCAL_DEVICE_ID))
  }

  @Test
  fun featuresOf_unknownDevice_isEmpty() = featuresTest { fleet ->
    assertEquals(emptySet(), fleet.state.featuresOf("gone.local:8642"))
  }

  @Test
  fun featuresOf_remote_isEmptyUntilItsStatusListsThem() = featuresTest { fleet ->
    assertEquals(emptySet(), fleet.state.featuresOf(NAS_ID))

    fleet.nas.answer(setOf(KetchFeatures.QUEUE_POSITION))
    advanceTimeBy(1.seconds)

    assertEquals(setOf(KetchFeatures.QUEUE_POSITION), fleet.state.featuresOf(NAS_ID))
  }

  @Test
  fun autoConnectionsSupported_olderRemoteInTheSelection_isFalse() = featuresTest { fleet ->
    val here = rowOf(fleet.mac.recording.add(DownloadState.Queued))
    val there = rowOf(fleet.nas.recording.add(DownloadState.Queued), deviceId = NAS_ID)
    assertTrue(autoConnectionsSupported(fleet.state, listOf(here)))
    assertFalse(autoConnectionsSupported(fleet.state, listOf(here, there)))

    fleet.nas.answer(setOf(KetchFeatures.AUTO_CONNECTIONS))
    advanceTimeBy(1.seconds)

    assertTrue(autoConnectionsSupported(fleet.state, listOf(here, there)))
  }

  @Test
  fun rows_embeddedDevice_showPositions() = featuresTest { fleet ->
    val model = waitingRows(fleet, fleet.local)
    waitInLine(fleet.mac)
    advanceTimeBy(1.seconds)

    val details = model.waitingDetails()

    assertEquals(2, details.size)
    assertTrue(details[0].endsWith("· next in line"), details[0])
    assertTrue(details[1].endsWith("· 1 ahead"), details[1])
  }

  @Test
  fun rows_remote_showPositionsOnceItsStatusListsThem() = featuresTest { fleet ->
    val model = waitingRows(fleet, fleet.remote)
    waitInLine(fleet.nas)
    advanceTimeBy(1.seconds)
    assertEquals(2, model.waitingDetails().size)
    assertTrue(model.waitingDetails().none { "ahead" in it || "next in line" in it })

    fleet.nas.answer(setOf(KetchFeatures.QUEUE_POSITION))
    advanceTimeBy(1.seconds)

    val details = model.waitingDetails()
    assertTrue(details[0].endsWith("· next in line"), details[0])
    assertTrue(details[1].endsWith("· 1 ahead"), details[1])
  }
}
