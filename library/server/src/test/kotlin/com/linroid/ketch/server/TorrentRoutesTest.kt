package com.linroid.ketch.server

import com.linroid.ketch.api.KetchApi
import com.linroid.ketch.api.torrent.TorrentActivity
import com.linroid.ketch.api.torrent.TorrentCapabilities
import com.linroid.ketch.api.torrent.TorrentCommandContext
import com.linroid.ketch.api.torrent.TorrentCommandError
import com.linroid.ketch.api.torrent.TorrentCommandException
import com.linroid.ketch.api.torrent.TorrentCommandResult
import com.linroid.ketch.api.torrent.TorrentController
import com.linroid.ketch.api.torrent.TorrentFileEntry
import com.linroid.ketch.api.torrent.TorrentFileOrder
import com.linroid.ketch.api.torrent.TorrentFilePage
import com.linroid.ketch.api.torrent.TorrentPageRequest
import com.linroid.ketch.api.torrent.TorrentRevision
import com.linroid.ketch.api.torrent.TorrentSnapshot
import com.linroid.ketch.endpoints.model.ErrorResponse
import com.linroid.ketch.endpoints.model.TorrentSeedingRequest
import com.linroid.ketch.endpoints.model.TorrentSelectionRequest
import io.ktor.client.request.get
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The `/api/torrents` routes over a scripted [TorrentController]. */
class TorrentRoutesTest {
  private val json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
  }

  private val current = TorrentRevision("epoch", 7)

  private fun snapshot(taskId: String) = TorrentSnapshot(
    taskId = taskId,
    revision = current,
    activity = TorrentActivity.DOWNLOADING,
    selectionGeneration = 1,
    selectionComplete = false,
  )

  /** Answers like a controller with one torrent, `t1`; `gone` is removed after a snapshot. */
  private inner class ScriptedController : TorrentController {
    val pages = mutableListOf<Triple<TorrentPageRequest, TorrentFileOrder, Boolean>>()

    override suspend fun capabilities() =
      TorrentCapabilities(names = setOf("inspect", "file-selection"), maxPageSize = 1000)

    override suspend fun snapshot(taskId: String): TorrentSnapshot? =
      if (taskId == "t1") this@TorrentRoutesTest.snapshot(taskId) else null

    override fun observe(taskId: String): Flow<TorrentSnapshot?> = flow {
      when (taskId) {
        "busy" -> throw TorrentCommandException(
          TorrentCommandError.RESOURCE_EXHAUSTED,
          "Too many torrent subscriptions",
        )
        "gone" -> {
          emit(this@TorrentRoutesTest.snapshot(taskId))
          emit(null)
        }
        else -> emit(null)
      }
    }

    override suspend fun files(
      taskId: String,
      page: TorrentPageRequest,
      order: TorrentFileOrder,
      descending: Boolean,
    ): TorrentFilePage? {
      if (taskId != "t1") return null
      pages += Triple(page, order, descending)
      if (page.cursor == "stale") {
        throw TorrentCommandException(TorrentCommandError.STALE_CURSOR, "Stale cursor")
      }
      return TorrentFilePage(
        taskId, current, 1, 1, listOf(TorrentFileEntry("0", "a.bin", 10, true)),
      )
    }

    override suspend fun select(
      taskId: String,
      fileIds: Set<String>,
      context: TorrentCommandContext,
    ): TorrentCommandResult {
      if (context.expectedRevision != current) {
        throw TorrentCommandException(
          TorrentCommandError.CONFLICT,
          "The download changed since that revision",
          currentRevision = current,
        )
      }
      return TorrentCommandResult(taskId, TorrentRevision("epoch", 8), 2)
    }

    override suspend fun setSeeding(
      taskId: String,
      seeding: Boolean,
      context: TorrentCommandContext,
    ): TorrentCommandResult =
      throw TorrentCommandException(TorrentCommandError.POLICY_DENIED, "Seeding is switched off")
  }

  private class ControlledKetch(
    delegate: KetchApi,
    override val torrents: TorrentController?,
  ) : KetchApi by delegate

  private suspend fun HttpResponse.error(): ErrorResponse = json.decodeFromString(bodyAsText())

  private fun controlledServer(controller: TorrentController = ScriptedController()) =
    createTestServer(ControlledKetch(createTestKetch(), controller))

  @Test
  fun capabilities_withoutController_isEmpty() = testApplication {
    application { with(createTestServer()) { configureServer() } }

    val capabilities = client.get("/api/torrents/capabilities")
    val snapshot = client.get("/api/torrents/t1")

    assertEquals(HttpStatusCode.OK, capabilities.status)
    assertEquals(
      emptySet(),
      json.decodeFromString<TorrentCapabilities>(capabilities.bodyAsText()).names,
    )
    assertEquals(HttpStatusCode.NotImplemented, snapshot.status)
    assertEquals("unsupported", snapshot.error().error)
  }

  @Test
  fun snapshot_unknownTask_answersNotFound() = testApplication {
    application { with(controlledServer()) { configureServer() } }

    val known = client.get("/api/torrents/t1")
    val unknown = client.get("/api/torrents/other")

    assertEquals(HttpStatusCode.OK, known.status)
    assertEquals(snapshot("t1"), json.decodeFromString<TorrentSnapshot>(known.bodyAsText()))
    assertEquals(HttpStatusCode.NotFound, unknown.status)
    assertEquals("not_found", unknown.error().error)
  }

  @Test
  fun selection_staleRevision_answersConflictWithRevision() = testApplication {
    application { with(controlledServer()) { configureServer() } }

    val response = client.put("/api/torrents/t1/selection") {
      contentType(ContentType.Application.Json)
      setBody(
        json.encodeToString(
          TorrentSelectionRequest(
            setOf("0"), TorrentCommandContext("key", TorrentRevision("epoch", 3)),
          )
        )
      )
    }
    val accepted = client.put("/api/torrents/t1/selection") {
      contentType(ContentType.Application.Json)
      setBody(
        json.encodeToString(
          TorrentSelectionRequest(setOf("0"), TorrentCommandContext("key", current))
        )
      )
    }

    assertEquals(HttpStatusCode.Conflict, response.status)
    val error = response.error()
    assertEquals("revision_conflict", error.error)
    assertEquals(current, error.revision)
    assertEquals(HttpStatusCode.OK, accepted.status)
    assertEquals(
      2,
      json.decodeFromString<TorrentCommandResult>(accepted.bodyAsText()).selectionGeneration,
    )
  }

  @Test
  fun files_staleCursor_answersConflict() = testApplication {
    application { with(controlledServer()) { configureServer() } }

    val response = client.get("/api/torrents/t1/files?limit=10&cursor=stale")

    assertEquals(HttpStatusCode.Conflict, response.status)
    assertEquals("stale_cursor", response.error().error)
  }

  @Test
  fun files_sortAndDesc_reachTheController() = testApplication {
    val controller = ScriptedController()
    application { with(controlledServer(controller)) { configureServer() } }

    val sorted = client.get("/api/torrents/t1/files?limit=5&sort=size&desc=true")
    val plain = client.get("/api/torrents/t1/files")
    val missing = client.get("/api/torrents/other/files")

    assertEquals(HttpStatusCode.OK, sorted.status)
    assertEquals(1, json.decodeFromString<TorrentFilePage>(sorted.bodyAsText()).files.size)
    assertEquals(HttpStatusCode.OK, plain.status)
    assertEquals(
      listOf(
        Triple(TorrentPageRequest(5), TorrentFileOrder.SIZE, true),
        Triple(TorrentPageRequest(100), TorrentFileOrder.TORRENT, false),
      ),
      controller.pages,
    )
    assertEquals(HttpStatusCode.NotFound, missing.status)
  }

  @Test
  fun files_unknownSort_answersBadRequest() = testApplication {
    val controller = ScriptedController()
    application { with(controlledServer(controller)) { configureServer() } }

    val response = client.get("/api/torrents/t1/files?sort=kind")

    assertEquals(HttpStatusCode.BadRequest, response.status)
    assertEquals("invalid_input", response.error().error)
    assertTrue(controller.pages.isEmpty())
  }

  @Test
  fun files_oversizedLimit_answersBadRequest() = testApplication {
    val controller = ScriptedController()
    application { with(controlledServer(controller)) { configureServer() } }

    for (query in listOf("limit=1001", "limit=0", "cursor=" + "c".repeat(4097))) {
      val response = client.get("/api/torrents/t1/files?$query")
      assertEquals(HttpStatusCode.BadRequest, response.status, query)
      assertEquals("invalid_input", response.error().error)
    }
    assertTrue(controller.pages.isEmpty())
  }

  @Test
  fun seeding_policyOff_answersForbidden() = testApplication {
    application { with(controlledServer()) { configureServer() } }

    val response = client.put("/api/torrents/t1/seeding") {
      contentType(ContentType.Application.Json)
      setBody(
        json.encodeToString(TorrentSeedingRequest(true, TorrentCommandContext("key", current)))
      )
    }

    assertEquals(HttpStatusCode.Forbidden, response.status)
    assertEquals("policy_denied", response.error().error)
  }

  @Test
  fun events_removedTask_sendsTombstoneAndCloses() = testApplication {
    application { with(controlledServer()) { configureServer() } }

    val body = client.get("/api/torrents/gone/events").bodyAsText()

    val events = Regex("event: ?(\\w+)").findAll(body).map { it.groupValues[1] }.toList()
    assertEquals(listOf("snapshot", "removed"), events)
    assertTrue("\"revision\":{\"epoch\":\"epoch\",\"sequence\":7}" in body, body)
  }

  @Test
  fun events_overLimit_sendsResourceExhausted() = testApplication {
    application { with(controlledServer()) { configureServer() } }

    val body = client.get("/api/torrents/busy/events").bodyAsText()

    val events = Regex("event: ?(\\w+)").findAll(body).map { it.groupValues[1] }.toList()
    assertEquals(listOf("error"), events)
    assertTrue("resource_exhausted" in body, body)
  }

  @Test
  fun routes_withoutToken_areUnauthorized() = testApplication {
    application {
      val ketch = ControlledKetch(createTestKetch(), ScriptedController())
      with(KetchServer(ketch, apiToken = "secret-token")) { configureServer() }
    }

    for (path in listOf(
      "/api/torrents/capabilities", "/api/torrents/t1", "/api/torrents/t1/files",
      "/api/torrents/t1/events",
    )) {
      assertEquals(HttpStatusCode.Unauthorized, client.get(path).status, path)
    }
    val put = client.put("/api/torrents/t1/seeding") {
      contentType(ContentType.Application.Json)
      setBody(
        json.encodeToString(TorrentSeedingRequest(true, TorrentCommandContext("key", current)))
      )
    }
    assertEquals(HttpStatusCode.Unauthorized, put.status)
  }
}
