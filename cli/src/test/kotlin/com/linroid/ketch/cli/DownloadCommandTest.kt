package com.linroid.ketch.cli

import com.linroid.ketch.api.Destination
import com.linroid.ketch.api.DownloadConfig
import com.linroid.ketch.api.DownloadRequest
import com.linroid.ketch.api.KetchError
import com.linroid.ketch.api.log.KetchLogger
import com.linroid.ketch.api.log.LogLevel
import com.linroid.ketch.api.log.Logger
import com.linroid.ketch.core.Ketch
import com.linroid.ketch.core.engine.HttpEngine
import com.linroid.ketch.core.engine.ServerInfo
import com.linroid.ketch.torrent.api.TorrentStall
import com.linroid.ketch.torrent.api.TorrentStallReason
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import java.net.UnknownHostException
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class DownloadCommandTest {
  private val folder: File = Files.createTempDirectory("ketch-cli-download").toFile()
  private val out = ByteArrayOutputStream()
  private val err = ByteArrayOutputStream()
  private val outStream = PrintStream(out, true, Charsets.UTF_8)
  private val errStream = PrintStream(err, true, Charsets.UTF_8)

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
    assertTrue("Download failed: Http: HTTP error 404: Not Found" in message, message)
  }

  @Test
  fun downloadUntilDone_hostNotFound_saysWhyWithoutAStackTrace() {
    // Logged as `ketch <url>` logs. The error's own message, "Network error occurred", said
    // nothing of why, and Ketch's info records crowded the progress line.
    val engine = FailingEngine(KetchError.Network(UnknownHostException("host.invalid")))
    val status = withConsole {
      download(
        "https://host.invalid/a.iso",
        engine,
        logger = Logger.console(quietLogLevel(LogLevel.INFO)),
      )
    }

    assertEquals(DownloadExit.FAILED, status)
    val message = err.toString(Charsets.UTF_8)
    assertTrue(
      "Download failed: Network: Network error occurred <- UnknownHostException: host.invalid"
        in message,
      message,
    )
    // The console logger writes warnings, and any stack trace they carry, to stdout.
    val stdout = out.toString(Charsets.UTF_8)
    assertFalse("\tat " in message, message)
    assertFalse("\tat " in stdout, stdout)
    assertFalse("[INFO]" in stdout, stdout)
    assertEquals(1, Regex("""\[WARN] .*Download failed for taskId=""").findAll(stdout).count(),
      stdout)
  }

  @Test
  fun downloadUntilDone_sourceLacksSomething_saysWhat() {
    val engine = FailingEngine(
      KetchError.Unsupported(IllegalStateException("The server sends no file size")),
    )
    val status = download("https://example.com/a.iso", engine)

    assertEquals(DownloadExit.FAILED, status)
    val message = err.toString(Charsets.UTF_8)
    assertTrue("Download failed: Not supported: The server sends no file size" in message, message)
  }

  @Test
  fun downloadUntilDone_sourceExplainsWhy_saysItOnce() {
    // As a torrent's source reports a refusal: its detail is the cause's message.
    val reason = "The torrent's pieces are 64 MiB, larger than the 16 MiB Ketch supports"
    val engine = FailingEngine(
      KetchError.SourceError("torrent", IllegalArgumentException(reason), detail = reason),
    )
    val status = download("https://example.com/a.torrent", engine)

    assertEquals(DownloadExit.FAILED, status)
    assertEquals("Download failed: $reason", err.toString(Charsets.UTF_8).trim())
  }

  @Test
  fun downloadUntilDone_torrentStalls_saysWhyOnTheProgressLine() {
    val engine = ServingEngine(byteArrayOf(1, 2, 3), release = CompletableDeferred())
    val status = download("https://example.com/a.bin", engine, stalls = {
      flow {
        emit(TorrentStall(TorrentStallReason.NO_PEERS, seconds = 150))
        awaitPrinted { "stalled" in it }
        // Progressing again: the line loses the reason, then the download finishes.
        emit(null)
        awaitPrinted { "\r" in it.substringAfterLast("stalled") }
        engine.release?.complete(Unit)
      }
    })

    assertEquals(DownloadExit.OK, status)
    val lines = out.toString(Charsets.UTF_8).split('\r')
    val stalled = lines.indexOfLast { "stalled" in it }
    assertTrue(lines[stalled].startsWith("[Downloading] "), lines[stalled])
    assertTrue("  stalled 2m 30s: no peers, still looking    " in lines[stalled], lines[stalled])
    // The next line covers it, so none of the reason is left on the terminal.
    val next = lines[stalled + 1].substringBefore('\n')
    assertFalse("stalled" in next, next)
    assertEquals(lines[stalled].length, next.length, next)
  }

  @Test
  fun stallLabel_missingPieces_countsThem() {
    fun missing(seconds: Long, pieces: Int) =
      TorrentStall(TorrentStallReason.MISSING_PIECES, seconds, missingPieces = pieces)

    assertEquals(
      "stalled 45s: no peer or web seed has 1 piece it needs",
      stallLabel(missing(seconds = 45, pieces = 1)),
    )
    assertEquals(
      "stalled 1h 2m 5s: no peer or web seed has 3 pieces it needs",
      stallLabel(missing(seconds = 3725, pieces = 3)),
    )
  }

  @Test
  fun quietLogLevel_verboseFlags_keepTheirLevel() {
    assertEquals(LogLevel.WARN, quietLogLevel(LogLevel.INFO))
    assertEquals(LogLevel.DEBUG, quietLogLevel(LogLevel.DEBUG))
    assertEquals(LogLevel.VERBOSE, quietLogLevel(LogLevel.VERBOSE))
  }

  @Test
  fun downloadUntilDone_downloadCompletes_exitsWithSuccess() {
    val status = download("https://example.com/a.bin", ServingEngine(byteArrayOf(1, 2, 3)))

    assertEquals(DownloadExit.OK, status)
    assertTrue("Download completed: " in out.toString(Charsets.UTF_8))
    assertEquals(3L, File(folder, "a.bin").length())
  }

  @Test
  fun listFiles_linkNoSourceHandles_saysSo() {
    val status = listFiles("gopher://example.com/a.iso", ServingEngine(ByteArray(0)))

    assertEquals(DownloadExit.FAILED, status)
    assertEquals(
      "Couldn't list the files: No download source handles this link",
      err.toString(Charsets.UTF_8).trim(),
    )
  }

  @Test
  fun listFiles_hostNotFound_saysWhy() {
    // The error's own message, "Network error occurred", said nothing of why.
    val engine = FailingEngine(KetchError.Network(UnknownHostException("host.invalid")))
    val status = listFiles("https://host.invalid/a.iso", engine)

    assertEquals(DownloadExit.FAILED, status)
    assertEquals(
      "Couldn't list the files: Network: Network error occurred <- " +
        "UnknownHostException: host.invalid",
      err.toString(Charsets.UTF_8).trim(),
    )
  }

  private fun listFiles(url: String, engine: HttpEngine): Int {
    val args = assertIs<DownloadArgs.Download>(parseDownloadArgs(listOf("--list-files", url)))
    return runBlocking {
      val ketch = Ketch(httpEngine = engine)
      try {
        listFiles(ketch, args, outStream, errStream)
      } finally {
        ketch.close()
        // Ketch installs its logger for the whole process.
        KetchLogger.setLogger(Logger.None)
      }
    }
  }

  private fun download(
    url: String,
    engine: HttpEngine,
    logger: Logger = Logger.None,
    stalls: (taskId: String) -> Flow<TorrentStall?> = { emptyFlow() },
  ): Int = runBlocking {
    val ketch = Ketch(httpEngine = engine, config = DownloadConfig(retryCount = 0), logger = logger)
    try {
      withTimeout(10.seconds) {
        downloadUntilDone(
          ketch = ketch,
          request = DownloadRequest(url = url, destination = Destination(folder.path + "/")),
          out = outStream,
          err = errStream,
          stalls = stalls,
        )
      }
    } finally {
      ketch.shutdown()
      // Ketch installs its logger for the whole process.
      KetchLogger.setLogger(Logger.None)
    }
  }

  /** Waits until what was printed to [out] matches [printed]. */
  private suspend fun awaitPrinted(printed: (String) -> Boolean) {
    while (!printed(out.toString(Charsets.UTF_8))) delay(10)
  }

  /** Runs [block] with the console's stdout and stderr going to [out] and [err]. */
  private fun <T> withConsole(block: () -> T): T {
    val stdout = System.out
    val stderr = System.err
    System.setOut(outStream)
    System.setErr(errStream)
    try {
      return block()
    } finally {
      System.setOut(stdout)
      System.setErr(stderr)
    }
  }

  /** Fails every request with [error], 404 by default. */
  private class FailingEngine(
    private val error: KetchError = KetchError.Http(404, "Not Found"),
  ) : HttpEngine {
    override suspend fun head(url: String, headers: Map<String, String>): ServerInfo = throw error

    override suspend fun download(
      url: String,
      range: LongRange?,
      headers: Map<String, String>,
      onData: suspend (ByteArray) -> Unit,
    ) = throw error

    override fun close() {}
  }

  /** Serves [content] without byte ranges; with [release], its first byte until it completes. */
  private class ServingEngine(
    private val content: ByteArray,
    val release: CompletableDeferred<Unit>? = null,
  ) : HttpEngine {
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
    ) {
      if (release == null) return onData(content)
      onData(content.copyOfRange(0, 1))
      release.await()
      onData(content.copyOfRange(1, content.size))
    }

    override fun close() {}
  }
}
