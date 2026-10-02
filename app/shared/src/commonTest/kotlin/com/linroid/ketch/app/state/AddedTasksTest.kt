package com.linroid.ketch.app.state

import com.linroid.ketch.app.RecordingConfigStore
import com.linroid.ketch.app.instance.InstanceFactory
import com.linroid.ketch.app.instance.InstanceManager
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** [AppState.addedTasks]: which adds the Downloads page hears about. */
class AddedTasksTest {
  private fun TestScope.controller(api: RecordingKetchApi): AppController = AppController(
    instanceManager = InstanceManager(
      factory = InstanceFactory(deviceName = "This Mac", embeddedFactory = { api }),
      configStore = RecordingConfigStore(),
    ),
    context = backgroundScope.coroutineContext +
      SupervisorJob(backgroundScope.coroutineContext[Job]),
  )

  @Test
  fun quickAdd_links_announcesTheNewTasksOnTheirDevice() = runTest {
    val api = RecordingKetchApi()
    val controller = controller(api)
    val added = async { controller.state.addedTasks.first() }
    runCurrent()

    controller.state.quickAdd(listOf("https://example.com/a.iso", "https://example.com/b.iso"))
    runCurrent()

    val keys = api.tasks.value.map { TaskKey(LOCAL_DEVICE_ID, it.taskId) }
    assertEquals(keys.toSet(), added.await().toSet())
    controller.close()
  }

  @Test
  fun quickAdd_everyLinkFails_announcesNothing() = runTest {
    val api = RecordingKetchApi()
    api.downloadFailure = { IllegalStateException("disk full") }
    val controller = controller(api)
    val heard = mutableListOf<List<TaskKey>>()
    val listener = backgroundScope.async { controller.state.addedTasks.collect { heard += it } }
    runCurrent()

    controller.state.quickAdd(listOf("https://example.com/a.iso"))
    runCurrent()

    assertTrue(heard.isEmpty())
    listener.cancel()
    controller.close()
  }

  @Test
  fun announceAdded_noKeys_announcesNothing() = runTest {
    val controller = controller(RecordingKetchApi())
    val heard = mutableListOf<List<TaskKey>>()
    val listener = backgroundScope.async { controller.state.addedTasks.collect { heard += it } }
    runCurrent()

    controller.state.announceAdded(emptyList())
    controller.state.announceAdded(listOf(TaskKey("nas", "t1")))
    runCurrent()

    assertEquals(listOf(listOf(TaskKey("nas", "t1"))), heard)
    listener.cancel()
    controller.close()
  }
}
