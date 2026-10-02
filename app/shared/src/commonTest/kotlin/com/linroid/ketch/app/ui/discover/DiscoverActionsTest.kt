package com.linroid.ketch.app.ui.discover

import com.linroid.ketch.app.feedback.AppMessage
import com.linroid.ketch.app.feedback.MessageLevel
import com.linroid.ketch.app.fixtureTest
import com.linroid.ketch.app.instance.displayName
import com.linroid.ketch.app.state.AiCandidate
import com.linroid.ketch.app.state.AppController
import com.linroid.ketch.app.state.LOCAL_DEVICE_ID
import com.linroid.ketch.app.state.RecordingKetchApi
import com.linroid.ketch.app.testController
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DiscoverActionsTest {

  private fun TestScope.controller(api: RecordingKetchApi): AppController =
    testController(api, deviceName = "Lins-MacBook-Pro")

  private fun discoverTest(
    api: RecordingKetchApi = RecordingKetchApi(),
    block: suspend TestScope.(RecordingKetchApi, AppController) -> Unit,
  ) = fixtureTest({ controller(api) }, AppController::close) { block(api, it) }

  private fun candidate(name: String) = AiCandidate(
    url = "https://example.com/$name",
    title = name,
    fileName = name,
    sourceUrl = "https://example.com/downloads",
    confidence = 0.9f,
    description = "",
  )

  private fun AppController.lastMessage(): AppMessage = messages.history.value.last()

  private fun AppController.click(label: String) {
    lastMessage().actions.first { it.label == label }.onClick()
  }

  private fun AppController.deviceName(): String =
    checkNotNull(state.activeInstance.value).displayName

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
    assertEquals("Added 2 downloads → ${controller.deviceName()} · 1 failed", message.title)
    assertEquals(listOf("Review", "Undo"), message.actions.map { it.label })
    val stillSelected = controller.state.aiDiscover.draft.selected
    assertEquals(setOf("https://example.com/broken.iso"), stillSelected)
    controller.close()
  }

  @Test
  fun addDiscovered_oneCandidate_namesItAndOffersShow() = discoverTest { api, controller ->
    runCurrent()

    controller.state.addDiscovered(listOf(candidate("blender.dmg")))
    runCurrent()

    val message = controller.lastMessage()
    assertEquals(MessageLevel.Success, message.level)
    assertEquals("Added blender.dmg → ${controller.deviceName()}", message.title)
    assertEquals(listOf("Show", "Undo"), message.actions.map { it.label })
  }

  @Test
  fun addDiscovered_allFail_postsAnErrorThatReviewsThem() = runTest {
    val api = RecordingKetchApi().apply { downloadFailure = { IllegalStateException("Gone") } }
    val controller = controller(api)
    runCurrent()

    controller.state.addDiscovered(listOf(candidate("broken.iso")))
    runCurrent()
    controller.click("Review")

    assertEquals("Couldn't add broken.iso", controller.lastMessage().title)
    assertEquals(MessageLevel.Error, controller.lastMessage().level)
    val seed = checkNotNull(controller.state.intakeRequest).seeds.single()
    assertEquals("https://example.com/broken.iso", seed.url)
    assertEquals("https://example.com/downloads", seed.headers["Referer"])
    controller.close()
  }

  @Test
  fun addDiscovered_undo_removesTheAddedTasksAndTheirFiles() = discoverTest { api, controller ->
    runCurrent()
    controller.state.addDiscovered(listOf(candidate("a.iso")))
    runCurrent()

    controller.click("Undo")
    runCurrent()

    assertTrue(api.tasks.value.isEmpty())
  }

  @Test
  fun reviewDiscovered_unknownTarget_opensTheSheetForTheActiveDevice() =
    discoverTest { _, controller ->
      runCurrent()
      controller.state.aiDiscover.draft.target = "gone.local:8642"

      controller.state.reviewDiscovered(listOf(candidate("a.iso"), candidate("b.iso")))

      val request = checkNotNull(controller.state.intakeRequest)
      assertEquals(LOCAL_DEVICE_ID, request.targetDeviceId)
      assertEquals(2, request.seeds.size)
    }
}
