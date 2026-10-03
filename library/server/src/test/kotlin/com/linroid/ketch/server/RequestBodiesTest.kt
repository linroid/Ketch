package com.linroid.ketch.server

import com.linroid.ketch.api.DownloadConfig
import com.linroid.ketch.endpoints.model.ErrorResponse
import com.linroid.ketch.server.api.MAX_JSON_BODY_BYTES
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.writeFully
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

class RequestBodiesTest {
  private val json = Json { ignoreUnknownKeys = true }

  @Test
  fun `JSON body longer than the limit is refused unread`() = testApplication {
    val ketch = createTestKetch()
    application {
      with(createTestServer(ketch)) { configureServer() }
    }

    val response = client.put("/api/config") {
      contentType(ContentType.Application.Json)
      setBody(paddedConfig(MAX_JSON_BODY_BYTES + 1))
    }

    assertEquals(HttpStatusCode.PayloadTooLarge, response.status)
    val error = json.decodeFromString<ErrorResponse>(response.bodyAsText())
    assertEquals("payload_too_large", error.error)
    assertEquals(DownloadConfig.Default, ketch.status().config)
  }

  @Test
  fun `chunked JSON body is read only up to the limit`() = testApplication {
    application {
      with(createTestServer()) { configureServer() }
    }
    val body = paddedConfig(MAX_JSON_BODY_BYTES + 1).encodeToByteArray()

    val response = client.put("/api/config") {
      // No length, so the server learns the size only by reading.
      setBody(object : OutgoingContent.WriteChannelContent() {
        override val contentType = ContentType.Application.Json
        override suspend fun writeTo(channel: ByteWriteChannel) = channel.writeFully(body)
      })
    }

    assertEquals(HttpStatusCode.PayloadTooLarge, response.status)
  }

  @Test
  fun `JSON body at the limit is accepted`() = testApplication {
    application {
      with(createTestServer()) { configureServer() }
    }

    val response = client.put("/api/config") {
      contentType(ContentType.Application.Json)
      setBody(paddedConfig(MAX_JSON_BODY_BYTES))
    }

    assertEquals(HttpStatusCode.OK, response.status)
  }

  @Test
  fun `body that is not JSON is refused`() = testApplication {
    application {
      with(createTestServer()) { configureServer() }
    }

    val response = client.post("/api/tasks") {
      contentType(ContentType.Text.Plain)
      setBody("""{"url":"https://example.com/file.zip"}""")
    }

    assertEquals(HttpStatusCode.UnsupportedMediaType, response.status)
  }

  /** A download configuration [size] bytes long, padded with a field the server ignores. */
  private fun paddedConfig(size: Int): String {
    val prefix = """{"padding":""""
    val suffix = "\"}"
    return prefix + "x".repeat(size - prefix.length - suffix.length) + suffix
  }
}
