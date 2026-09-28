package com.linroid.ketch.remote

import com.linroid.ketch.api.DownloadConfig
import com.linroid.ketch.endpoints.model.ErrorResponse
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class RemoteUpdateConfigTest {
  @Test
  fun updateConfig_rejected_reportsServerMessage() = runTest {
    val remote = RemoteKetch("remote.example", 8642, null, false, MockEngine {
      respond(
        Json.encodeToString(
          ErrorResponse("bad_request", "Download folder does not exist: /nope"),
        ),
        HttpStatusCode.BadRequest,
        headersOf(HttpHeaders.ContentType, "application/json"),
      )
    })
    try {
      val error = assertFailsWith<IllegalArgumentException> {
        remote.updateConfig(DownloadConfig(defaultDirectory = "/nope"))
      }
      assertEquals("Download folder does not exist: /nope", error.message)
    } finally {
      remote.close()
    }
  }
}
