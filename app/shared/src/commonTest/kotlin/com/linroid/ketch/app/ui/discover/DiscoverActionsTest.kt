package com.linroid.ketch.app.ui.discover

import com.linroid.ketch.app.feedback.AppMessage
import com.linroid.ketch.app.feedback.MessageLevel
import com.linroid.ketch.app.i18n.load
import com.linroid.ketch.app.instance.InstanceFactory
import com.linroid.ketch.app.instance.InstanceManager
import com.linroid.ketch.app.instance.displayName
import com.linroid.ketch.app.state.AiCandidate
import com.linroid.ketch.app.state.AppController
import com.linroid.ketch.app.state.LOCAL_DEVICE_ID
import com.linroid.ketch.app.state.RecordingKetchApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DiscoverActionsTest {

  private fun TestScope.controller(api: RecordingKetchApi): AppController = AppController(
    instanceManager = InstanceManager(
      factory = InstanceFactory(deviceName = "Lins-MacBook-Pro", embeddedFactory = { api }),
    ),
    context = StandardTestDispatcher(testScheduler),
  )

  private fun candidate(name: String) = AiCandidate(
    url = "https://example.com/$name",
    title = name,
    fileName = name,
    sourceUrl = "https://example.com/downloads",
    confidence = 0.9f,
    description = "",
  )

  private fun AppController.lastMessage(): AppMessage = messages.history.value.last()

  private suspend fun AppController.click(label: String) {
    lastMessage().actions.first { it.label.load() == label }.onClick()
  }

  private suspend fun AppController.deviceName(): String =
    checkNotNull(state.activeInstance.value).displayName.load()

  @Test
  fun addDiscovered_oneFails_addsTheOthersAndReportsBoth() = runTest {
    val api = RecordingKetchApi().apply {
      downloadFailure = { if ("broken" in it.url) IllegalStateException("Not found") else null }
    }
    val controller = controller(api)
    runCurrent()
    val picked = listOf(candidate("a.iso"), candidate("broken.iso"), candidate("b.iso"))
    controller.state.aiDiscover.draft.selected = picked.map { it.url }.toSet()

    controller.state.addDiscovered(picked)
    runCurrent()

    assertEquals(2, api.requests.size)
    assertTrue(api.requests.all { it.properties["ketch.origin"] == "discover" })
    val message = controller.lastMessage()
    assertEquals(MessageLevel.Warning, message.level)
    assertEquals(
      "Added 2 downloads → ${controller.deviceName()} · 1 failed",
      message.title.load(),
    )
    assertEquals(listOf("Review", "Undo"), message.actions.map { it.label }.load())
    val stillSelected = controller.state.aiDiscover.draft.selected
    assertEquals(setOf("https://example.com/broken.iso"), stillSelected)
    controller.close()
  }

  @Test
  fun addDiscovered_oneCandidate_namesItAndOffersShow() = runTest {
    val api = RecordingKetchApi()
    val controller = controller(api)
    runCurrent()

    controller.state.addDiscovered(listOf(candidate("blender.dmg")))
    runCurrent()

    val message = controller.lastMessage()
    assertEquals(MessageLevel.Success, message.level)
    assertEquals("Added blender.dmg → ${controller.deviceName()}", message.title.load())
    assertEquals(listOf("Show", "Undo"), message.actions.map { it.label }.load())
    controller.close()
  }

  @Test
  fun addDiscovered_allFail_postsAnErrorThatReviewsThem() = runTest {
    val api = RecordingKetchApi().apply { downloadFailure = { IllegalStateException("Gone") } }
    val controller = controller(api)
    runCurrent()

    controller.state.addDiscovered(listOf(candidate("broken.iso")))
    runCurrent()
    controller.click("Review")

    assertEquals("Couldn't add broken.iso", controller.lastMessage().title.load())
    assertEquals(MessageLevel.Error, controller.lastMessage().level)
    val seed = checkNotNull(controller.state.intakeRequest).seeds.single()
    assertEquals("https://example.com/broken.iso", seed.url)
    assertEquals("https://example.com/downloads", seed.headers["Referer"])
    controller.close()
  }

  @Test
  fun addDiscovered_undo_removesTheAddedTasksAndTheirFiles() = runTest {
    val api = RecordingKetchApi()
    val controller = controller(api)
    runCurrent()
    controller.state.addDiscovered(listOf(candidate("a.iso")))
    runCurrent()

    controller.click("Undo")
    runCurrent()

    assertTrue(api.tasks.value.isEmpty())
    controller.close()
  }

  @Test
  fun reviewDiscovered_unknownTarget_opensTheSheetForTheActiveDevice() = runTest {
    val controller = controller(RecordingKetchApi())
    runCurrent()
    controller.state.aiDiscover.draft.target = "gone.local:8642"

    controller.state.reviewDiscovered(listOf(candidate("a.iso"), candidate("b.iso")))

    val request = checkNotNull(controller.state.intakeRequest)
    assertEquals(LOCAL_DEVICE_ID, request.targetDeviceId)
    assertEquals(2, request.seeds.size)
    controller.close()
  }
}
