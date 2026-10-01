package com.linroid.ketch.app.ui.pulse

import com.linroid.ketch.api.DownloadProgress
import com.linroid.ketch.api.DownloadState
import com.linroid.ketch.api.KetchError
import com.linroid.ketch.app.RecordingConfigStore
import com.linroid.ketch.app.instance.InstanceFactory
import com.linroid.ketch.app.instance.InstanceManager
import com.linroid.ketch.app.state.AppSettingsController
import com.linroid.ketch.app.state.AppState
import com.linroid.ketch.app.state.RecordingKetchApi
import com.linroid.ketch.app.state.StatusFilter
import com.linroid.ketch.config.KetchConfig
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.seconds

class PulseBarTest {

  @Test
  fun sparklineSamples_history_scalesItsPeakNearTheTop() {
    val samples = sparklineSamples(listOf(0, 13_000_000, 26_000_000))

    assertEquals(listOf(0L, 409_600L, 819_200L), samples)
  }

  @Test
  fun sparklineSamples_tooShortOrIdle_isNull() {
    assertNull(sparklineSamples(listOf(5_000_000)))
    assertNull(sparklineSamples(listOf(0, 0, 0)))
    assertNull(sparklineSamples(emptyList()))
  }

  @Test
  fun pulseCounts_everyState_equalTheStatusTabCounts() = runTest {
    val api = RecordingKetchApi()
    listOf(
      DownloadState.Downloading(DownloadProgress(10, 100, 5)),
      DownloadState.Queued,
      DownloadState.Queued,
      DownloadState.Paused(DownloadProgress(10, 100)),
      DownloadState.Completed("/d/a.bin"),
      DownloadState.Failed(KetchError.Http(403)),
      DownloadState.Canceled
    ).forEach { api.add(it) }
    val store = RecordingConfigStore(KetchConfig())
    val state = AppState(
      instanceManager = InstanceManager(
        factory = InstanceFactory(deviceName = "This Mac", embeddedFactory = { api }),
        configStore = store,
      ),
      scope = backgroundScope,
      appSettings = AppSettingsController(store),
    )

    val tabs = state.taskList.counts.first { it[StatusFilter.All] == 7 }
    advanceTimeBy(1.seconds)
    runCurrent()

    val pulse = state.pulse.state.value.counts
    StatusFilter.entries.forEach { filter ->
      assertEquals(tabs[filter], pulse.count(filter), "tab ${filter.label}")
    }
    val parts = countParts(pulse, state.pulse.state.value.failures)
    assertEquals(listOf("1↓", "2 waiting", "2 failed"), parts.map { it.text })
  }
}
