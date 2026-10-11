package com.linroid.ketch.hooks

import com.linroid.ketch.api.DownloadCategory
import com.linroid.ketch.api.DownloadConfig
import com.linroid.ketch.api.DownloadRequest
import com.linroid.ketch.api.DownloadState
import com.linroid.ketch.api.KetchError
import com.linroid.ketch.config.HookEvent
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

class HookPayloadTest {
  private val at = Instant.parse("2026-10-01T12:00:00Z")

  private fun payload(
    url: String = "https://user:pa55@cdn.example.com:8443/movie.mkv?token=abc",
    state: DownloadState,
    config: DownloadConfig? = null,
  ) = HookPayload.of(
    event = HookEvent.Completed,
    at = at,
    deliveryId = "d1",
    taskId = "t1",
    request = DownloadRequest(url = url, properties = mapOf("ketch.origin" to "browser")),
    state = state,
    outputPath = null,
    createdAt = at - 60.seconds,
    config = config,
  )

  @Test
  fun of_completedDownload_redactsTheUrlAndNamesTheCategory() {
    val payload = payload(
      state = DownloadState.Completed(
        outputPath = "/data/Videos/movie.mkv",
        totalBytes = 1234,
        downloadTime = 5.seconds,
        completedAt = at,
      ),
      config = DownloadConfig(
        categories = listOf(DownloadCategory("Videos", extensions = listOf("mkv"))),
      ),
    )

    assertFalse("pa55" in payload.url, payload.url)
    assertFalse("abc" in payload.url, payload.url)
    assertEquals("cdn.example.com", payload.host)
    assertEquals("movie.mkv", payload.fileName)
    assertEquals("/data/Videos/movie.mkv", payload.outputPath)
    assertEquals(1234, payload.totalBytes)
    assertEquals(5000, payload.downloadTimeMillis)
    assertEquals("Videos", payload.category)
    assertEquals("browser", payload.origin)
    assertEquals("completed", payload.state)
  }

  @Test
  fun of_failedDownload_describesTheErrorWithoutUrls() {
    val error = KetchError.Unknown(errorMessage = "GET https://x.io/f?key=s3cret failed")
    val payload = payload(state = DownloadState.Failed(error))

    assertEquals("unknown", payload.error?.kind)
    assertFalse("s3cret" in payload.error?.message.orEmpty(), payload.error?.message)
    assertEquals("unknown", payload.environment()["KETCH_ERROR_KIND"])
  }

  @Test
  fun toJson_leavesOutWhatDoesNotApply() {
    val json = payload(url = "magnet:?xt=urn:btih:abc", state = DownloadState.Queued).toJson()
    val fields = Json.parseToJsonElement(json).jsonObject

    assertFalse("host" in fields, json)
    assertFalse("error" in fields, json)
    assertEquals("\"completed\"", fields["event"].toString())
  }

  @Test
  fun environment_namesEachFieldThatIsSet() {
    val env = payload(state = DownloadState.Completed("/d/movie.mkv", 7)).environment()

    assertEquals("completed", env["KETCH_EVENT"])
    assertEquals("t1", env["KETCH_TASK_ID"])
    assertEquals("/d/movie.mkv", env["KETCH_OUTPUT_PATH"])
    assertEquals("7", env["KETCH_TOTAL_BYTES"])
    assertNull(env["KETCH_ERROR_KIND"])
    assertNull(env["KETCH_CATEGORY"])
  }

  @Test
  fun hostOf_readsHostsOfEveryShape() {
    assertEquals("example.com", hostOf("HTTPS://u:p@Example.COM.:80/a"))
    assertEquals("::1", hostOf("http://[::1]:8080/"))
    assertEquals("h.io", hostOf("ftp://h.io?x"))
    assertNull(hostOf("magnet:?xt=urn:btih:abc"))
  }
}
