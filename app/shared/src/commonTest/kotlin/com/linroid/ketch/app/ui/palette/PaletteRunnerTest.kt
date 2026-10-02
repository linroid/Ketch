package com.linroid.ketch.app.ui.palette

import com.linroid.ketch.api.DownloadState
import com.linroid.ketch.app.RecordingConfigStore
import com.linroid.ketch.app.fixtureTest
import com.linroid.ketch.app.input.KetchCommand
import com.linroid.ketch.app.input.KetchCommands
import com.linroid.ketch.app.instance.InstanceFactory
import com.linroid.ketch.app.instance.InstanceManager
import com.linroid.ketch.app.state.AppController
import com.linroid.ketch.app.state.DiscoverRequest
import com.linroid.ketch.app.state.IntakeRequest
import com.linroid.ketch.app.state.LOCAL_DEVICE_ID
import com.linroid.ketch.app.state.RecordingKetchApi
import com.linroid.ketch.app.state.RecordingTask
import com.linroid.ketch.app.state.SettingsTarget
import com.linroid.ketch.app.state.StatusFilter
import com.linroid.ketch.app.ui.list.RowCommands
import com.linroid.ketch.config.KetchConfig
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class PaletteRunnerTest {
  private class Fixture(
    val api: RecordingKetchApi,
    val controller: AppController,
    val runner: PaletteRunner,
    val commands: List<KetchCommand>,
  )

  private fun TestScope.fixture(): Fixture {
    val api = RecordingKetchApi()
    val controller = AppController(
      instanceManager = InstanceManager(
        factory = InstanceFactory(deviceName = "This Mac", embeddedFactory = { api }),
        configStore = RecordingConfigStore(KetchConfig()),
      ),
      context = StandardTestDispatcher(testScheduler),
    )
    val ran = mutableListOf<KetchCommand>()
    val rowCommands = RowCommands(controller.state, null, null, backgroundScope) {}
    val runner = PaletteRunner(controller.state, rowCommands) { ran += it; true }
    return Fixture(api, controller, runner, ran)
  }

  private fun paletteTest(block: suspend TestScope.(Fixture) -> Unit) =
    fixtureTest({ fixture() }, { it.controller.close() }, block)

  @Test
  fun run_downloadNow_addsTheLinkOnTheDevice() = paletteTest { fixture ->
    runCurrent()
    val url = "https://example.com/ubuntu.iso"

    fixture.runner.run(PaletteAction.Download(url, listOf(url), LOCAL_DEVICE_ID, now = true))
    runCurrent()

    assertEquals(url, fixture.api.requests.single().url)
  }

  @Test
  fun run_downloadNeedingTheSheet_opensItForTheDevice() = paletteTest { fixture ->
    runCurrent()
    val magnet = "magnet:?xt=urn:btih:0123456789abcdef0123456789abcdef01234567"

    val download = PaletteAction.Download(magnet, listOf(magnet), LOCAL_DEVICE_ID, now = false)
    fixture.runner.run(download)

    val expected = IntakeRequest(text = magnet, targetDeviceId = LOCAL_DEVICE_ID)
    assertEquals(expected, fixture.controller.state.intakeRequest)
    assertTrue(fixture.api.requests.isEmpty())
  }

  @Test
  fun run_deviceBatchPause_pausesTheTasksOfThatDevice() = paletteTest { fixture ->
    runCurrent()
    val task = fixture.api.add(DownloadState.Downloading(RecordingTask.PROGRESS))
    runCurrent()

    fixture.runner.run(PaletteAction.DeviceBatch(LOCAL_DEVICE_ID, BatchVerb.Pause))
    runCurrent()

    assertEquals(listOf("pause"), task.calls)
  }

  @Test
  fun run_command_runsItAsItsShortcutDoes() = paletteTest { fixture ->
    fixture.runner.run(PaletteAction.Command(KetchCommands.PauseAll))

    assertEquals(listOf(KetchCommands.PauseAll), fixture.commands)
  }

  @Test
  fun run_settingsPage_opensSettingsThere() = paletteTest { fixture ->
    fixture.runner.run(PaletteAction.Settings(SettingsTarget.Page.Speed))

    assertEquals(SettingsTarget.Page.Speed, fixture.controller.state.settingsRequest?.page)
  }

  @Test
  fun run_search_filtersTheDownloadsList() = paletteTest { fixture ->
    fixture.controller.state.statusFilter = StatusFilter.Paused

    fixture.runner.run(PaletteAction.Search("is:failed"))

    assertEquals("is:failed", fixture.controller.state.searchQuery)
    assertEquals(StatusFilter.All, fixture.controller.state.statusFilter)
  }

  @Test
  fun run_discover_asksForDiscover() = paletteTest { fixture ->
    fixture.runner.run(PaletteAction.Discover("blender for mac"))

    assertEquals(DiscoverRequest("blender for mac"), fixture.controller.state.discoverRequest)
  }
}
