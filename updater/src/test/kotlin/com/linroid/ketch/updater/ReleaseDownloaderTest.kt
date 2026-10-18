package com.linroid.ketch.updater

import com.linroid.ketch.api.log.Logger
import com.linroid.ketch.engine.KtorHttpEngine
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ReleaseDownloaderTest {
  private val dir = Files.createTempDirectory("ketch-updater-test").toFile()
  private val content = Random(7).nextBytes(300_000)
  private val url = "https://example.com/ketch.tar.gz"

  @AfterTest
  fun cleanUp() {
    dir.deleteRecursively()
  }

  @Test
  fun download_matchingDigest_keepsTheFile() = runTest {
    val file = File(dir, "nested/ketch.tar.gz")
    val engine = FakeHttpEngine(mapOf(url to content))
    downloader(engine).download(asset(sha256 = sha256Of(content)), file)

    assertContentEquals(content, file.readBytes())
    assertTrue(engine.closed)
  }

  @Test
  fun download_otherDigest_deletesTheFile() = runTest {
    val file = File(dir, "ketch.tar.gz")
    val error = assertFailsWith<UpdateException> {
      downloader(FakeHttpEngine(mapOf(url to content)))
        .download(asset(sha256 = "0".repeat(64)), file)
    }
    assertContains(error.message.orEmpty(), "checksum")
    assertFalse(file.exists())
  }

  @Test
  fun download_noDigest_failsBeforeDownloading() = runTest {
    val engine = FakeHttpEngine(mapOf(url to content))
    assertFailsWith<UpdateException> {
      downloader(engine).download(asset(sha256 = null), File(dir, "ketch.tar.gz"))
    }
    assertTrue(engine.requests.isEmpty())
  }

  @Test
  fun download_failedRequest_leavesNoFile() = runTest {
    val file = File(dir, "ketch.tar.gz").apply { writeText("previous") }
    assertFailsWith<UpdateException> {
      downloader(FakeHttpEngine(emptyMap())).download(asset(sha256 = sha256Of(content)), file)
    }
    assertFalse(file.exists())
  }

  @Test
  fun download_malformedDigest_failsBeforeDownloading() = runTest {
    val engine = FakeHttpEngine(mapOf(url to content))
    assertFailsWith<UpdateException> {
      downloader(engine).download(asset(sha256 = "00"), File(dir, "ketch.tar.gz"))
    }
    assertTrue(engine.requests.isEmpty())
  }

  @Test
  fun download_signedTargetRefused_saysWhereWithoutItsQuery() = runTest {
    // GitHub sends release files to a signed link, whose query holds tokens not every one of
    // which a log redacts; the message shows in the apps' settings and on stderr.
    val signed = "https://release-assets.example/asset/1?sp=r&sig=abc&jwt=eyJhbGciOi.e30.c2ln"
    val engine = {
      KtorHttpEngine(HttpClient(MockEngine { request ->
        if (request.url.toString() == url) {
          respond("", HttpStatusCode.Found, headersOf(HttpHeaders.Location, signed))
        } else {
          respond("", HttpStatusCode.Forbidden)
        }
      }))
    }
    val error = assertFailsWith<UpdateException> {
      ReleaseDownloader(engine, Logger.None)
        .download(asset(sha256 = sha256Of(content)), File(dir, "ketch.tar.gz"))
    }

    val message = error.message.orEmpty()
    assertContains(message, "Couldn't download ketch.tar.gz: Http: HTTP error 403")
    assertContains(message, "GET https://release-assets.example/asset/1")
    assertFalse('?' in message, message)
  }

  private fun downloader(engine: FakeHttpEngine) = ReleaseDownloader({ engine }, Logger.None)

  private fun asset(sha256: String?) =
    ReleaseAsset("ketch.tar.gz", url, content.size.toLong(), sha256)

  private fun sha256Of(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
