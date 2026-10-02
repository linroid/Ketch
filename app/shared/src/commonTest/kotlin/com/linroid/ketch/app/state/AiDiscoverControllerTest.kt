package com.linroid.ketch.app.state

import com.linroid.ketch.config.AiSettings
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class AiDiscoverControllerTest {

  private fun candidate(name: String) = AiCandidate(
    url = "https://example.com/$name",
    title = name,
    sourceUrl = "https://example.com/downloads",
    confidence = 0.9f,
    description = "",
  )

  @Test
  fun add_oneCandidateFails_addsTheOthers() = runTest {
    val api = RecordingKetchApi().apply {
      downloadFailure = { if ("broken" in it.url) IllegalStateException("Not found") else null }
    }
    val controller = AiDiscoverController(AiSettingsController(), backgroundScope)
    val broken = candidate("broken.iso")

    val result = controller.add(
      api = api,
      candidates = listOf(candidate("a.iso"), broken, candidate("b.iso")),
      query = "ubuntu iso",
    )

    assertEquals(
      listOf("https://example.com/a.iso", "https://example.com/b.iso"),
      api.requests.map { it.url },
    )
    assertEquals(listOf(broken), result.failed.map { it.first })
    assertEquals(2, result.added.size)
  }

  @Test
  fun add_candidate_recordsWhereItCameFrom() = runTest {
    val api = RecordingKetchApi()
    val controller = AiDiscoverController(AiSettingsController(), backgroundScope)

    controller.add(api, listOf(candidate("a.iso")), query = "ubuntu iso")

    val request = api.requests.single()
    assertEquals("https://example.com/downloads", request.headers["Referer"])
    assertEquals("discover", request.properties["ketch.origin"])
    assertEquals("ubuntu iso", request.properties["ketch.query"])
  }

  @Test
  fun discover_request_fillsTheDraftAndSearches() = runTest {
    val provider = object : AiDiscoveryProvider {
      var request: AiDiscoverRequest? = null

      override suspend fun discover(request: AiDiscoverRequest): AiDiscoverResponse {
        this.request = request
        return AiDiscoverResponse(request.query, listOf(candidate("blender.dmg")))
      }

      override suspend fun verify(): String = "ok"
    }
    val settings = AiSettingsController(factory = { provider })
    settings.save(AiSettings(enabled = true))
    val controller = AiDiscoverController(settings, backgroundScope)

    controller.discover(DiscoverRequest("Blender for Apple silicon", listOf("blender.org")))
    runCurrent()

    assertEquals("Blender for Apple silicon", controller.draft.submittedQuery)
    assertEquals("blender.org", controller.draft.sites)
    assertEquals(listOf("blender.org"), provider.request?.sites)
    assertIs<AiDiscoverState.Results>(controller.state)
  }
}
