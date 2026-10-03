package com.linroid.ketch.app.util

import com.linroid.ketch.api.DownloadRequest
import com.linroid.ketch.api.KetchApi
import com.linroid.ketch.api.KetchError
import com.linroid.ketch.app.i18n.load
import com.linroid.ketch.app.i18n.verbatim
import com.linroid.ketch.app.state.DeviceInfo
import com.linroid.ketch.app.state.RowAction
import com.linroid.ketch.app.state.RowCapabilities
import kotlinx.coroutines.test.runTest
import okio.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ErrorCopyTest {
  private val request = DownloadRequest("https://github.com/releases/app.zip")
  private val local =
    DeviceInfo(verbatim("This Mac"), RowCapabilities.local(), usableSpace = 1024L * 1024)
  private val remote = DeviceInfo(verbatim("NAS-Basement"), RowCapabilities.remote())

  @Test
  fun toCopy_everySubtype_hasTitleAndPrimary() = runTest {
    val expected = mapOf<KetchError, Pair<String, RowAction>>(
      KetchError.Network() to ("Connection lost" to RowAction.Retry),
      KetchError.Http(500) to ("Server error (500)" to RowAction.Retry),
      KetchError.Disk() to ("Couldn't write the file" to RowAction.ShowInFolder),
      KetchError.Unsupported() to
        ("This server can't do this download" to RowAction.DownloadAgain),
      KetchError.FileChanged("ETag changed") to
        ("File changed on the server" to RowAction.DownloadAgain),
      KetchError.CorruptResumeState() to
        ("Saved progress is damaged" to RowAction.DownloadAgain),
      KetchError.Canceled() to ("Canceled" to RowAction.DownloadAgain),
      KetchError.SourceError("torrent") to ("The torrent stopped" to RowAction.Retry),
      KetchError.AuthenticationFailed("ftp") to
        ("Wrong user name or password" to RowAction.EnterCredentials),
      KetchError.Unknown(errorMessage = "boom") to ("Something went wrong" to RowAction.Retry)
    )

    for ((error, copy) in expected) {
      val actual = error.toCopy(request, retryCount = 3, device = local)
      assertEquals(copy.first, actual.title.load(), "title of $error")
      assertEquals(copy.second, actual.primary, "primary of $error")
    }
  }

  @Test
  fun toCopy_httpBands_matchCatalog() = runTest {
    val expected = mapOf(
      401 to ("Sign-in required (401)" to RowAction.EditLink),
      407 to ("Sign-in required (407)" to RowAction.EditLink),
      403 to ("Access denied (403)" to RowAction.EditLink),
      404 to ("File not found (404)" to RowAction.CopyLink),
      410 to ("File not found (410)" to RowAction.CopyLink),
      416 to ("Server refused to resume" to RowAction.DownloadAgain),
      429 to ("Server is rate-limiting" to RowAction.RetryWithConnections(1)),
      500 to ("Server error (500)" to RowAction.Retry),
      503 to ("Server error (503)" to RowAction.Retry),
      418 to ("Server answered 418" to RowAction.Retry)
    )

    for ((code, copy) in expected) {
      val actual = KetchError.Http(code).toCopy(request, retryCount = 0, device = local)
      assertEquals(copy.first, actual.title.load(), "title of $code")
      assertEquals(copy.second, actual.primary, "primary of $code")
    }
  }

  @Test
  fun toCopy_network_namesHostAndRetries() = runTest {
    val copy = KetchError.Network().toCopy(request, retryCount = 3, device = local)

    assertEquals("Couldn't reach github.com. Ketch retried 3 times.", copy.hint.load())
    assertEquals(listOf(RowAction.RetryWithConnections(2)), copy.secondary)
  }

  @Test
  fun toCopy_networkWithoutRetries_omitsRetryCount() = runTest {
    val once = KetchError.Network().toCopy(request, retryCount = 1, device = local)
    val never = KetchError.Network().toCopy(request, retryCount = 0, device = local)

    assertEquals("Couldn't reach github.com. Ketch retried once.", once.hint.load())
    assertEquals("Couldn't reach github.com.", never.hint.load())
  }

  @Test
  fun toCopy_disk_reportsSpaceOnFailingDevice() = runTest {
    val copy = KetchError.Disk().toCopy(request, retryCount = 0, device = local)

    assertEquals("1.0 MB free on This Mac. Check space and folder permissions.", copy.hint.load())
    assertEquals(listOf(RowAction.Retry), copy.secondary)
  }

  @Test
  fun toCopy_diskOnRemote_retriesWithoutShowingFolder() = runTest {
    val copy = KetchError.Disk().toCopy(request, retryCount = 0, device = remote)

    assertEquals(RowAction.Retry, copy.primary)
    assertEquals("Check free space and folder permissions on NAS-Basement.", copy.hint.load())
  }

  @Test
  fun toCopy_forbiddenFromBrowser_namesBrowserAndOffersSourcePage() = runTest {
    val captured = request.copy(
      headers = mapOf(
        "referer" to "https://github.com/releases",
        "User-Agent" to "Mozilla/5.0 AppleWebKit/537.36 Chrome/128.0 Safari/537.36"
      ),
    )

    val copy = KetchError.Http(403).toCopy(captured, retryCount = 0, device = local)

    assertEquals(
      "The link may have expired. Captured from Chrome? Capture it again from the page.",
      copy.hint.load()
    )
    assertEquals(listOf(RowAction.OpenSourcePage), copy.secondary)
    assertEquals("the link may have expired", copy.shortHint.load())
  }

  @Test
  fun toCopy_forbiddenWithoutBrowser_keepsGenericHint() = runTest {
    val copy = KetchError.Http(403).toCopy(request, retryCount = 0, device = local)

    assertEquals("The link may have expired.", copy.hint.load())
    assertTrue(copy.secondary.isEmpty())
  }

  @Test
  fun toCopy_notFoundWithDiscover_findsAnotherSource() {
    val device = DeviceInfo(verbatim("This Mac"), RowCapabilities.local(canDiscover = true))

    val copy = KetchError.Http(404).toCopy(request, retryCount = 0, device = device)

    assertEquals(RowAction.FindAnotherSource, copy.primary)
    assertEquals(listOf(RowAction.CopyLink), copy.secondary)
  }

  @Test
  fun toCopy_rateLimited_namesRetryAfter() = runTest {
    val withDelay = KetchError.Http(429, retryAfterSeconds = 30)
      .toCopy(request, retryCount = 0, device = local)
    val withoutDelay = KetchError.Http(429).toCopy(request, retryCount = 0, device = local)

    assertEquals("Try again in 30 s with fewer connections.", withDelay.hint.load())
    assertEquals("Try again later with fewer connections.", withoutDelay.hint.load())
  }

  @Test
  fun toCopy_otherHttpCode_usesStatusMessage() = runTest {
    val copy = KetchError.Http(418, "I'm a teapot").toCopy(request, retryCount = 0, device = local)

    assertEquals("I'm a teapot", copy.hint.load())
    assertEquals(listOf(RowAction.CopyDetails), copy.secondary)
  }

  @Test
  fun toCopy_ftpErrors_nameTheServerAndCause() = runTest {
    val auth = KetchError.AuthenticationFailed("ftp").toCopy(request, 0, local)
    val source = KetchError.SourceError("ftp", Exception("FTP 550: No such file"))
      .toCopy(request, 0, local)

    assertEquals("The FTP server rejected the sign-in.", auth.hint.load())
    assertEquals("The FTP server reported an error", source.title.load())
    assertEquals("FTP 550: No such file", source.hint.load())
    assertEquals("FTP 550: No such file", source.shortHint.load())
  }

  @Test
  fun toCopy_sourceErrorWithoutCauseMessage_hasNoHint() {
    val wrapped = KetchError.SourceError("ftp", IllegalStateException(IllegalStateException()))

    assertNull(wrapped.toCopy(request, 0, local).hint)
  }

  @Test
  fun toCopy_unknown_putsMessageInDetails() {
    val copy = KetchError.Unknown(errorMessage = "boom").toCopy(request, 0, local)

    assertNull(copy.hint)
    assertEquals("boom", copy.details)
  }

  @Test
  fun toCopy_fileChanged_includesReason() = runTest {
    val copy = KetchError.FileChanged("ETag changed").toCopy(request, 0, local)

    assertEquals("Resuming would corrupt it (ETag changed).", copy.hint.load())
  }

  @Test
  fun shortForm_acronym_keepsCase() {
    assertEquals("FTP said no", shortForm("FTP said no. Try later."))
  }

  @Test
  fun shortHint_reasonPhraseOrPronoun_keepsCase() = runTest {
    val reason = KetchError.Http(400, "Bad Request").toCopy(request, 0, local)
    val teapot = KetchError.Http(418, "I'm a teapot").toCopy(request, 0, local)

    assertEquals("Bad Request", reason.shortHint.load())
    assertEquals("I'm a teapot", teapot.shortHint.load())
    assertEquals("the FTP server rejected it", shortForm("The FTP server rejected it."))
  }

  @Test
  fun throwableToCopy_wrappedKetchError_usesCatalog() = runTest {
    val error = IllegalStateException("wrapper", KetchError.Http(503))

    assertEquals("Server error (503)", error.toCopy().title.load())
  }

  @Test
  fun throwableToCopy_ioFailure_readsAsConnectionLost() = runTest {
    val copy = RuntimeException("request failed", IOException("Connection refused")).toCopy()

    assertEquals("Connection lost", copy.title.load())
    assertEquals(RowAction.Retry, copy.primary)
  }

  @Test
  fun throwableToCopy_unsupported_explainsIt() = runTest {
    val copy = UnsupportedOperationException("Rescheduling is not supported").toCopy()

    assertEquals("Not supported on this device", copy.title.load())
    assertEquals("Rescheduling is not supported", copy.hint.load())
  }

  @Test
  fun throwableToCopy_unexpected_putsCausesInDetails() = runTest {
    val copy = IllegalArgumentException("bad input").toCopy()

    assertEquals("Something went wrong", copy.title.load())
    assertEquals("IllegalArgumentException: bad input", copy.details)
  }

  @Test
  fun errorDetails_urlWithCredentials_masksThemAndNamesVersion() {
    val ftp = DownloadRequest("ftp://me:secret@ftp.example.com/a.iso")

    val details = errorDetails(KetchError.Http(403), ftp, taskId = "task-1")

    assertFalse("secret" in details)
    assertTrue("HTTP status: 403" in details)
    assertTrue("Task: task-1" in details)
    assertTrue("Ketch ${KetchApi.VERSION}" in details)
  }
}
