package com.linroid.ketch.cli

import com.linroid.ketch.api.ControlKey
import com.linroid.ketch.api.Destination
import com.linroid.ketch.api.DownloadConfig
import com.linroid.ketch.api.DownloadProgress
import com.linroid.ketch.api.DownloadRequest
import com.linroid.ketch.api.DownloadState
import com.linroid.ketch.api.DownloadTask
import com.linroid.ketch.api.Interest
import com.linroid.ketch.api.KetchApi
import com.linroid.ketch.api.KetchError
import com.linroid.ketch.api.KetchStatus
import com.linroid.ketch.api.ResolveInput
import com.linroid.ketch.api.ResolvedSource
import com.linroid.ketch.api.SpeedLimit
import com.linroid.ketch.api.log.KetchLogger
import com.linroid.ketch.api.log.LogLevel
import com.linroid.ketch.api.log.Logger
import com.linroid.ketch.core.Ketch
import com.linroid.ketch.core.engine.DownloadSource
import com.linroid.ketch.core.engine.HttpEngine
import com.linroid.ketch.core.engine.ServerInfo
import com.linroid.ketch.engine.KtorHttpEngine
import com.linroid.ketch.torrent.TorrentConfig
import com.linroid.ketch.torrent.TorrentDownloadSource
import com.linroid.ketch.torrent.api.TorrentActivity
import com.linroid.ketch.torrent.api.TorrentCapabilities
import com.linroid.ketch.torrent.api.TorrentCommandError
import com.linroid.ketch.torrent.api.TorrentCommandException
import com.linroid.ketch.torrent.api.TorrentController
import com.linroid.ketch.torrent.api.TorrentInterest
import com.linroid.ketch.torrent.api.TorrentRevision
import com.linroid.ketch.torrent.api.TorrentSnapshot
import com.linroid.ketch.torrent.api.TorrentStall
import com.linroid.ketch.torrent.api.TorrentStallReason
import com.linroid.ketch.webdav.WebDavDownloadSource
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.PrintStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.UnknownHostException
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration
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
  fun downloadUntilDone_connectionRefused_logsEachAttemptOnceWithoutErrors() {
    // As `ketch <url>` logs. The HTTP engine also logged each attempt's failure as an error,
    // beside the retries' warnings and the final failure.
    val port = ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { it.localPort }
    val status = withConsole {
      download(
        "http://127.0.0.1:$port/a.iso",
        KtorHttpEngine(),
        logger = Logger.console(quietLogLevel(LogLevel.INFO)),
        config = DownloadConfig(retryCount = 2, retryDelayMs = 1, maxRetryDelayMs = 1),
      )
    }

    assertEquals(DownloadExit.FAILED, status)
    val logged = out.toString(Charsets.UTF_8) + err.toString(Charsets.UTF_8)
    fun count(pattern: String) = Regex(pattern).findAll(logged).count()
    assertFalse("[ERROR]" in logged, logged)
    assertFalse("[KtorHttpEngine]" in logged, logged)
    assertEquals(2, count("""\[WARN] \[Execution] Retry \d/2 """), logged)
    // It still says where the download failed, which only the engine's records said.
    val url = Regex.escape("http://127.0.0.1:$port/a.iso")
    assertEquals(1, count("""\[WARN] \[Coordinator] Download failed .* [A-Z]+ $url """), logged)
    // Only the summary goes to stderr.
    val summary = err.toString(Charsets.UTF_8).trim()
    assertTrue(summary.startsWith("Download failed: Network: ") && '\n' !in summary, logged)
    assertTrue(Regex(""" [A-Z]+ $url """).containsMatchIn(summary), summary)
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
  fun downloadUntilDone_sourceSumsUpItsCause_saysBoth() {
    // WebDAV's detail is a summary; only its cause says what is wrong with the link.
    val engine = ServingEngine(ByteArray(0))
    val status = download(
      "webdav://:8080/a.iso",
      engine,
      sources = listOf(WebDavDownloadSource(engine)),
    )

    assertEquals(DownloadExit.FAILED, status)
    assertEquals(
      "Download failed: Invalid WebDAV link <- IllegalArgumentException: The link has no host",
      err.toString(Charsets.UTF_8).trim(),
    )
  }

  @Test
  fun failureReason_detailRepeatsTheFirstCause_namesTheCausesBelowIt() {
    val reason = "Couldn't save the piece"
    val error = KetchError.SourceError(
      "torrent",
      IllegalStateException(reason, IOException("No space left on device")),
      detail = reason,
    )

    assertEquals("$reason <- IOException: No space left on device", failureReason(error))
  }

  @Test
  fun failureReason_detailQuotesItsCause_saysItOnce() {
    // As a magnet's unusable metadata: the wrapper's message quotes its cause's.
    val inner = IllegalArgumentException("Piece length is not a power of two")
    val wrapper =
      IllegalStateException("The magnet's torrent cannot be opened: ${inner.message}", inner)
    val error = KetchError.SourceError("torrent", wrapper, detail = wrapper.message)

    assertEquals(wrapper.message, failureReason(error))
  }

  @Test
  fun downloadUntilDone_torrentStalls_saysWhyAboveTheProgressLine() {
    val engine = ServingEngine(byteArrayOf(1, 2, 3), release = CompletableDeferred())
    val status = download("https://example.com/a.bin", engine, stalls = {
      flow {
        emit(TorrentStall(TorrentStallReason.NO_PEERS, seconds = 150))
        awaitPrinted { "stalled 2m 30s" in it }
        // Progressing again: the line loses the stall, then the download finishes.
        emit(null)
        awaitPrinted { "\r" in it.substringAfterLast("stalled") }
        engine.release?.complete(Unit)
      }
    })

    assertEquals(DownloadExit.OK, status)
    val rows = out.toString(Charsets.UTF_8).split('\r', '\n')
    val reasons = rows.filter { "[Stalled]" in it }.map { it.trimEnd() }
    assertEquals(listOf("[Stalled] No peers, still looking"), reasons)
    val stalled = rows.indexOfLast { "stalled" in it }
    assertTrue(rows[stalled].startsWith("[Downloading] "), rows[stalled])
    assertTrue(rows[stalled].endsWith("  stalled 2m 30s"), rows[stalled])
    // The next line covers it, so nothing of the stall is left on the terminal.
    val next = rows[stalled + 1]
    assertFalse("stalled" in next, next)
    assertEquals(rows[stalled].length, next.length, next)
  }

  @Test
  fun downloadUntilDone_ketchWarnsWhileDownloading_printsTheWarningAboveTheProgressLine() {
    // As `ketch <url>` logs. A tracker's warning was written onto the end of the progress line.
    val engine = ServingEngine(byteArrayOf(1, 2, 3), release = CompletableDeferred())
    val log = KetchLogger("Tracker")
    val status = withConsole {
      download(
        "https://example.com/a.bin",
        engine,
        logger = Logger.console(quietLogLevel(LogLevel.INFO)),
        stalls = {
          flow {
            awaitPrinted { "[Downloading] " in it }
            log.w { "Tracker warns about the torrent" }
            awaitPrinted { "Tracker warns about the torrent" in it }
            engine.release?.complete(Unit)
          }
        },
      )
    }

    assertEquals(DownloadExit.OK, status)
    val rows = terminalRows(out.toString(Charsets.UTF_8))
    val screen = rows.joinToString("\n")
    val warning = rows.indexOfFirst { "Tracker warns" in it }
    assertTrue(warning >= 0, screen)
    assertTrue(
      Regex("""\d{4}-\d\d-\d\d [\d:.]+ \[WARN] \[Tracker] Tracker warns about the torrent""")
        .matches(rows[warning]),
      screen,
    )
    // The line it cleared is drawn again below it, where the download goes on.
    assertTrue(rows[warning + 1].startsWith("[Downloading] "), screen)
    assertEquals(1, rows.count { "[Downloading]" in it }, screen)
  }

  @Test
  fun downloadUntilDone_outputIsNotATerminal_printsTheWarningOnALineOfItsOwn() {
    // As `ketch <url> > out.log`: captured, the carriage returns that clear the line on a
    // terminal put the progress and a run of spaces in front of every record.
    val engine = ServingEngine(byteArrayOf(1, 2, 3), release = CompletableDeferred())
    val log = KetchLogger("Tracker")
    val status = withConsole {
      download(
        "https://example.com/a.bin",
        engine,
        logger = Logger.console(quietLogLevel(LogLevel.INFO)),
        interactive = false,
        stalls = {
          flow {
            awaitPrinted { "[Downloading] " in it }
            log.w { "Tracker warns about the torrent" }
            awaitPrinted { "Tracker warns about the torrent" in it }
            engine.release?.complete(Unit)
          }
        },
      )
    }

    assertEquals(DownloadExit.OK, status)
    val output = out.toString(Charsets.UTF_8)
    assertFalse('\r' in output, output)
    val lines = output.lines()
    val warning =
      Regex("""\d{4}-\d\d-\d\d [\d:.]+ \[WARN] \[Tracker] Tracker warns about the torrent""")
    assertEquals(1, lines.count { warning.matches(it) }, output)
    assertTrue(lines.any { it.startsWith("[Downloading] ") }, output)
  }

  @Test
  fun progressPrinter_torrentStalls_keepsEveryLineNarrowerThan80Columns() {
    // Printed again every second: a line that wraps leaves a row behind at each update. Some
    // consoles wrap once the 80th column is written, so a line must stop before it.
    val printer = ProgressPrinter(ProgressLine(outStream, interactive = true), SpeedLimit.kbps(500))
    val sizes = listOf(
      DownloadProgress(179_516_211, 1_503_238_554, bytesPerSecond = 1_258_291),
      DownloadProgress(872_309_637_120, 1_099_511_627_775, bytesPerSecond = 1_048_575),
    )
    for (progress in sizes) {
      printer.print(DownloadState.Downloading(progress), stall = null)
      for (reason in TorrentStallReason.entries) {
        for (seconds in listOf(150L, 3725L, 93_784L)) {
          val stall = TorrentStall(reason, seconds, missingPieces = 12_345)
          printer.print(DownloadState.Downloading(progress), stall)
        }
      }
    }

    val rows = out.toString(Charsets.UTF_8).split('\r', '\n')
    assertTrue(rows.any { "stalled 1d 2h 3m 4s" in it }, rows.joinToString("\n"))
    rows.forEach { assertTrue(it.length < 80, "${it.length} columns: $it") }
  }

  @Test
  fun progressPrinter_stallGoesOn_saysWhyOnceEachTimeTheReasonChanges() {
    val printer = ProgressPrinter(ProgressLine(outStream, interactive = true), SpeedLimit.Unlimited)
    val downloading = DownloadState.Downloading(DownloadProgress(1024, 65_536))
    fun stall(reason: TorrentStallReason, seconds: Long) = TorrentStall(reason, seconds)

    printer.print(downloading, stall(TorrentStallReason.NO_PEERS, seconds = 30))
    printer.print(downloading, stall(TorrentStallReason.NO_PEERS, seconds = 31))
    printer.print(downloading, stall(TorrentStallReason.CHOKED, seconds = 32))
    printer.print(downloading, stall(TorrentStallReason.CHOKED, seconds = 33))
    printer.print(downloading, stall = null)
    // A stall after progress is a new one.
    printer.print(downloading, stall(TorrentStallReason.CHOKED, seconds = 30))

    val reasons = out.toString(Charsets.UTF_8).split('\r', '\n')
      .filter { it.startsWith("[Stalled] ") }
      .map { it.trimEnd() }
    assertEquals(
      listOf(
        "[Stalled] No peers, still looking",
        "[Stalled] Peers connected, but none is sharing yet",
        "[Stalled] Peers connected, but none is sharing yet",
      ),
      reasons,
    )
  }

  @Test
  fun stallReason_missingPieces_countsThem() {
    fun missing(pieces: Int) =
      TorrentStall(TorrentStallReason.MISSING_PIECES, seconds = 45, missingPieces = pieces)

    assertEquals("No peer or web seed has 1 piece it needs", stallReason(missing(pieces = 1)))
    assertEquals("No peer or web seed has 3 pieces it needs", stallReason(missing(pieces = 3)))
  }

  @Test
  fun torrentStalls_torrentTask_followsItsSnapshotsUntilItIsGone() {
    val stall = TorrentStall(TorrentStallReason.CHOKED, seconds = 40, peers = 3)
    val ketch = TorrentKetch(flowOf(snapshot(stall), snapshot(stall = null), null))

    val stalls = runBlocking { withTimeout(10.seconds) { torrentStalls(ketch, "t1").toList() } }

    assertEquals(listOf(stall, null, null), stalls)
    assertEquals(listOf<Interest<*>>(TorrentInterest("t1")), ketch.interests)
  }

  @Test
  fun torrentStalls_subscriptionRefused_endsWithoutAStall() {
    val ketch = TorrentKetch(flow {
      throw TorrentCommandException(
        TorrentCommandError.RESOURCE_EXHAUSTED,
        "Too many torrent subscriptions",
      )
    })

    val stalls = runBlocking { withTimeout(10.seconds) { torrentStalls(ketch, "t1").toList() } }

    assertEquals(emptyList(), stalls)
  }

  @Test
  fun torrentStalls_httpTaskBesideTheTorrentSource_endsAtOnce() = runBlocking {
    val torrents = TorrentDownloadSource(
      config = TorrentConfig(
        dhtEnabled = false,
        portMapping = false,
        localDiscovery = false,
        stateDirectory = File(folder, "torrent-state").path,
      ),
      restoreSeeding = false,
    )
    val ketch = Ketch(
      httpEngine = ServingEngine(byteArrayOf(1, 2, 3)),
      additionalSources = listOf(torrents),
    )
    try {
      val task = ketch.download(
        DownloadRequest("https://example.com/a.bin", destination = Destination(folder.path + "/")),
      )

      val stalls = withTimeout(10.seconds) { torrentStalls(ketch, task.taskId).toList() }

      assertEquals(listOf(null), stalls)
    } finally {
      ketch.shutdown()
    }
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
    stalls: ((taskId: String) -> Flow<TorrentStall?>)? = null,
    sources: List<DownloadSource<*>> = emptyList(),
    config: DownloadConfig = DownloadConfig(retryCount = 0),
    interactive: Boolean = true,
  ): Int = runBlocking {
    // As `ketch <url>` sets them up.
    val progress = ProgressLine(outStream, interactive)
    val ketch = Ketch(
      httpEngine = engine,
      config = config,
      additionalSources = sources,
      logger = logger.above(progress),
    )
    try {
      val request = DownloadRequest(url = url, destination = Destination(folder.path + "/"))
      withTimeout(10.seconds) {
        if (stalls == null) {
          downloadUntilDone(ketch, request, progress, errStream)
        } else {
          downloadUntilDone(ketch, request, progress, errStream, stalls)
        }
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

  private fun snapshot(stall: TorrentStall?) = TorrentSnapshot(
    taskId = "t1",
    revision = TorrentRevision(epoch = "e", sequence = 1),
    activity = TorrentActivity.DOWNLOADING,
    selectionGeneration = 0,
    selectionComplete = false,
    stall = stall,
  )

  /** A backend whose torrent controller reports [snapshots] for every torrent it is asked about. */
  private class TorrentKetch(private val snapshots: Flow<TorrentSnapshot?>) : KetchApi {
    /** What [subscribe] was asked to follow. */
    val interests = mutableListOf<Interest<*>>()

    private val torrents = object : TorrentController {
      override suspend fun capabilities() = TorrentCapabilities()

      override suspend fun snapshot(taskId: String): TorrentSnapshot? = null
    }

    override val backendLabel = "Fake"
    override val tasks = MutableStateFlow(emptyList<DownloadTask>())

    @Suppress("UNCHECKED_CAST")
    override fun <T> subscribe(interest: Interest<T>, interval: Duration): Flow<T> {
      interests += interest
      return if (interest is TorrentInterest) snapshots as Flow<T> else emptyFlow()
    }

    @Suppress("UNCHECKED_CAST")
    override fun <T : Any> controller(key: ControlKey<T>): T? =
      if (key == TorrentController) torrents as T else null

    override suspend fun download(request: DownloadRequest): DownloadTask =
      throw UnsupportedOperationException()

    override suspend fun resolve(input: ResolveInput): ResolvedSource =
      throw UnsupportedOperationException()

    override suspend fun start() = Unit

    override suspend fun status(): KetchStatus = throw UnsupportedOperationException()

    override suspend fun updateConfig(config: DownloadConfig) = Unit

    override fun close() = Unit
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
