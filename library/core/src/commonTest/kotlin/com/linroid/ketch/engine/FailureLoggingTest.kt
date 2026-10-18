package com.linroid.ketch.engine

import com.linroid.ketch.api.Destination
import com.linroid.ketch.api.DownloadConfig
import com.linroid.ketch.api.DownloadRequest
import com.linroid.ketch.api.DownloadState
import com.linroid.ketch.api.KetchError
import com.linroid.ketch.api.ResolveInput
import com.linroid.ketch.api.ResolvedSource
import com.linroid.ketch.api.log.Logger
import com.linroid.ketch.api.resolve
import com.linroid.ketch.core.Ketch
import com.linroid.ketch.core.KetchDispatchers
import com.linroid.ketch.core.engine.DownloadContext
import com.linroid.ketch.core.engine.DownloadSource
import com.linroid.ketch.core.engine.TransferStalledException
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.builtins.serializer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** How routine refusals and real failures are logged: a warning, or an error with its trace. */
class FailureLoggingTest {

  private class Record(val level: String, val message: String, val throwable: Throwable?)

  private class Capture : Logger {
    val records = mutableListOf<Record>()
    override fun v(message: String) { records += Record("v", message, null) }
    override fun d(message: String) { records += Record("d", message, null) }
    override fun i(message: String) { records += Record("i", message, null) }
    override fun w(message: String, throwable: Throwable?) {
      records += Record("w", message, throwable)
    }
    override fun e(message: String, throwable: Throwable?) {
      records += Record("e", message, throwable)
    }
  }

  /**
   * `fail:refused` fails as a source refuses content, `fail:wrapped-refusal` as one that says why
   * with the refusal it caught, `fail:stalled` as a transfer stalls; `fail:ended-early`,
   * `fail:other-range` and `fail:no-tls` as the HTTP engine reports a response that ends early or
   * covers another range, and the FTP client FTPS where it has no TLS; `fail:bug` with an
   * unexpected error and `fail:wrapped-bug` with a bug the source reported as its own error.
   */
  private class FailingSource : DownloadSource<Unit> {
    override val type = "failing"
    override val stateSerializer = Unit.serializer()
    override fun canHandle(input: ResolveInput): Boolean =
      input is ResolveInput.Url && input.url.startsWith("fail:")
    override suspend fun resolve(input: ResolveInput, config: DownloadConfig): ResolvedSource =
      ResolvedSource(
        url = (input as ResolveInput.Url).url, sourceType = type, totalBytes = 4,
        supportsResume = false, suggestedFileName = "file", maxSegments = 1,
      )
    override suspend fun download(context: DownloadContext, state: Unit?) {
      when (context.url) {
        "fail:refused" -> throw KetchError.SourceError(type, detail = "The pieces are too large")
        "fail:wrapped-refusal" -> throw KetchError.SourceError(
          sourceType = type,
          cause = IllegalArgumentException("Resume torrent changed"),
          detail = "Resume torrent changed",
        )
        "fail:stalled" -> throw KetchError.Network(TransferStalledException("No data for 60s"))
        "fail:ended-early" -> throw KetchError.Network(
          IllegalStateException("Response ended before requested length"),
        )
        "fail:other-range" -> throw KetchError.Unsupported(
          IllegalStateException("Server returned a different range"),
        )
        "fail:no-tls" -> throw KetchError.Unsupported(
          UnsupportedOperationException("FTPS is not available on this platform"),
        )
        "fail:wrapped-bug" -> throw KetchError.SourceError(
          sourceType = type,
          cause = IndexOutOfBoundsException("Index 5 out of bounds"),
          detail = "Index 5 out of bounds",
        )
        else -> throw IllegalStateException("a bug")
      }
    }
  }

  private fun TestScope.ketch(logger: Logger): Ketch {
    val dispatcher = StandardTestDispatcher(testScheduler)
    return Ketch(
      httpEngine = FakeHttpEngine(),
      config = DownloadConfig(retryCount = 0),
      additionalSources = listOf(FailingSource()),
      logger = logger,
      dispatchers = KetchDispatchers(dispatcher, dispatcher, dispatcher),
    )
  }

  private fun request(url: String) =
    DownloadRequest(url = url, destination = Destination("/tmp/ketch-failure-logging/"))

  @Test
  fun download_sourceRefuses_logsAWarningWithoutATrace() = runTest {
    val capture = Capture()
    val ketch = ketch(capture)
    try {
      val task = ketch.download(request("fail:refused"))
      runCurrent()
      assertIs<DownloadState.Failed>(task.state.value)

      val line = capture.records.single { "Download failed" in it.message }
      assertEquals("w", line.level)
      assertNull(line.throwable)
      assertTrue("The pieces are too large" in line.message, line.message)
    } finally {
      ketch.close()
    }
  }

  @Test
  fun download_unknownFailure_logsAnErrorWithItsTrace() = runTest {
    val capture = Capture()
    val ketch = ketch(capture)
    try {
      val task = ketch.download(request("fail:bug"))
      runCurrent()
      assertIs<DownloadState.Failed>(task.state.value)

      val line = capture.records.single { "Download failed" in it.message }
      assertEquals("e", line.level)
      assertIs<KetchError.Unknown>(assertNotNull(line.throwable))
    } finally {
      ketch.close()
    }
  }

  @Test
  fun download_sourceWrapsAnExpectedFailure_logsAWarningWithoutATrace() = runTest {
    for (url in listOf("fail:wrapped-refusal", "fail:stalled")) {
      val capture = Capture()
      val ketch = ketch(capture)
      try {
        val task = ketch.download(request(url))
        runCurrent()
        assertIs<DownloadState.Failed>(task.state.value)

        val line = capture.records.single { "Download failed" in it.message }
        assertEquals("w", line.level, url)
        assertNull(line.throwable, url)
      } finally {
        ketch.close()
      }
    }
  }

  @Test
  fun download_engineReportsAServerOrPlatformLimit_logsAWarningWithoutATrace() = runTest {
    for (url in listOf("fail:ended-early", "fail:other-range", "fail:no-tls")) {
      val capture = Capture()
      val ketch = ketch(capture)
      try {
        val task = ketch.download(request(url))
        runCurrent()
        assertIs<DownloadState.Failed>(task.state.value)

        val line = capture.records.single { "Download failed" in it.message }
        assertEquals("w", line.level, url)
        assertNull(line.throwable, url)
      } finally {
        ketch.close()
      }
    }
  }

  @Test
  fun download_sourceWrapsABug_logsAnErrorWithItsTrace() = runTest {
    val capture = Capture()
    val ketch = ketch(capture)
    try {
      val task = ketch.download(request("fail:wrapped-bug"))
      runCurrent()
      assertIs<DownloadState.Failed>(task.state.value)

      val line = capture.records.single { "Download failed" in it.message }
      assertEquals("e", line.level)
      val error = assertIs<KetchError.SourceError>(assertNotNull(line.throwable))
      assertIs<IndexOutOfBoundsException>(error.cause)
    } finally {
      ketch.close()
    }
  }

  @Test
  fun resolve_noSourceHandlesTheUrl_logsNoError() = runTest {
    val capture = Capture()
    val ketch = ketch(capture)
    try {
      assertFailsWith<KetchError.Unsupported> { ketch.resolve("gopher://example.com/file") }

      assertTrue(capture.records.none { it.level == "e" || it.level == "w" })
    } finally {
      ketch.close()
    }
  }
}
