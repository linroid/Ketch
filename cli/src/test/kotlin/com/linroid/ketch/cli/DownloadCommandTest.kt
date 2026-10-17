package com.linroid.ketch.cli

import com.linroid.ketch.api.Destination
import com.linroid.ketch.api.DownloadRequest
import com.linroid.ketch.api.KetchError
import com.linroid.ketch.core.Ketch
import com.linroid.ketch.core.engine.HttpEngine
import com.linroid.ketch.core.engine.ServerInfo
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DownloadCommandTest {
  private val folder: File = Files.createTempDirectory("ketch-cli-download").toFile()
  private val out = ByteArrayOutputStream()
  private val err = ByteArrayOutputStream()

  @AfterTest
  fun cleanUp() {
    folder.deleteRecursively()
  }

  @Test
  fun downloadUntilDone_linkNoSourceHandles_failsWithoutAStackTrace() {
    val status = download("gopher://example.com/a.iso", ServingEngine(ByteArray(0)))

    assertEquals(DownloadExit.FAILED, status)
    val message = err.toString(Charsets.UTF_8)
    assertTrue(
      "Download failed: No download source handles this link" in message,
      message,
    )
    assertFalse("\tat " in message, message)
  }

  @Test
  fun downloadUntilDone_downloadFails_exitsWithFailureAndSaysWhyOnStderr() {
    val status = download("https://example.com/missing.iso", FailingEngine())

    assertEquals(DownloadExit.FAILED, status)
    val message = err.toString(Charsets.UTF_8)
    assertTrue("Download failed: HTTP error 404" in message, message)
  }

  @Test
  fun downloadUntilDone_downloadCompletes_exitsWithSuccess() {
    val status = download("https://example.com/a.bin", ServingEngine(byteArrayOf(1, 2, 3)))

    assertEquals(DownloadExit.OK, status)
    assertTrue("Download completed: " in out.toString(Charsets.UTF_8))
    assertEquals(3L, File(folder, "a.bin").length())
  }

  private fun download(url: String, engine: HttpEngine): Int = runBlocking {
    val ketch = Ketch(httpEngine = engine)
    try {
      downloadUntilDone(
        ketch = ketch,
        request = DownloadRequest(url = url, destination = Destination(folder.path + "/")),
        out = PrintStream(out, true, Charsets.UTF_8),
        err = PrintStream(err, true, Charsets.UTF_8),
      )
    } finally {
      ketch.shutdown()
    }
  }

  /** Answers every request with 404. */
  private class FailingEngine : HttpEngine {
    override suspend fun head(url: String, headers: Map<String, String>): ServerInfo =
      throw KetchError.Http(404, "Not Found")

    override suspend fun download(
      url: String,
      range: LongRange?,
      headers: Map<String, String>,
      onData: suspend (ByteArray) -> Unit,
    ) = throw KetchError.Http(404, "Not Found")

    override fun close() {}
  }

  /** Serves [content] without byte ranges. */
  private class ServingEngine(private val content: ByteArray) : HttpEngine {
    override suspend fun head(url: String, headers: Map<String, String>): ServerInfo =
      ServerInfo(
        contentLength = content.size.toLong(),
        acceptRanges = false,
        etag = null,
        lastModified = null,
      )

    override suspend fun download(
      url: String,
      range: LongRange?,
      headers: Map<String, String>,
      onData: suspend (ByteArray) -> Unit,
    ) = onData(content)

    override fun close() {}
  }
}
