package com.linroid.ketch.cli

import com.linroid.ketch.api.KetchError
import com.linroid.ketch.api.log.KetchLogger
import com.linroid.ketch.api.log.LogLevel
import com.linroid.ketch.api.log.Logger
import com.linroid.ketch.core.engine.HttpEngine
import com.linroid.ketch.core.engine.ServerInfo
import com.linroid.ketch.updater.ReleaseAsset
import com.linroid.ketch.updater.UpdateException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.PrintStream
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class SelfUpdateTest {
  private val dir: File = Files.createTempDirectory("ketch-cli-update").toFile()
  private val content = Random(7).nextBytes(64 * 1024)

  // One terminal, which stdout and stderr both write to, each through a buffer of its own.
  private val terminal = ByteArrayOutputStream()
  private val out = PrintStream(BufferedOutputStream(terminal), false, Charsets.UTF_8)
  private val err = PrintStream(BufferedOutputStream(terminal), false, Charsets.UTF_8)

  @AfterTest
  fun cleanUp() {
    dir.deleteRecursively()
  }

  @Test
  fun downloadWithProgress_retryWarnsWhileTheLineIsShown_printsTheWarningOnARowOfItsOwn() {
    // A dropped connection, which Ketch warns it retries from its own thread as the line shows.
    val engine = HalvesEngine(content, dropOnce = true)

    download(engine, sha256 = sha256Of(content))

    val rows = screen()
    val screen = rows.joinToString("\n")
    val retry = Regex(
      """\d{4}-\d\d-\d\d [\d:.]+ \[WARN] \[Execution] Retry 1/3 for taskId=\S+ in \d+ms: """ +
        """.*IOException: Connection reset""",
    )
    assertEquals(1, rows.count { retry.matches(it) }, screen)
    // Every record, the retry's and the restart's after it, takes a row of its own, and the
    // line they cleared is drawn again below them, where the download goes on.
    val record = Regex("""\d{4}-\d\d-\d\d [\d:.]+ \[WARN] \[\w+] .+""")
    assertEquals(emptyList(), rows.dropLast(1).filterNot { record.matches(it) }, screen)
    assertTrue(PROGRESS.matches(rows.last()), screen)
  }

  @Test
  fun downloadWithProgress_downloadFails_endsTheLineBeforeTheErrorIsPrinted() {
    // `ketch update` says why it failed once the download returns; on the progress line, the
    // reason read as part of it.
    val engine = HalvesEngine(content, dropOnce = false)

    val error = assertFailsWith<UpdateException> { download(engine, sha256 = "0".repeat(64)) }
    err.println("Error: ${error.message}")

    val rows = screen()
    val screen = rows.joinToString("\n")
    assertEquals("Error: ketch.tar.gz doesn't match the checksum GitHub published", rows.last())
    assertTrue(PROGRESS.matches(rows[rows.size - 2]), screen)
  }

  /**
   * Downloads [content] over [engine] as `ketch update` does, with the console logger and the
   * progress line on [terminal], expecting [sha256].
   */
  private fun download(engine: HalvesEngine, sha256: String) {
    val stdout = System.out
    val stderr = System.err
    System.setOut(out)
    System.setErr(err)
    try {
      runBlocking {
        withTimeout(10.seconds) {
          launch {
            while (!PROGRESS_SHOWN.containsMatchIn(terminal.toString(Charsets.UTF_8))) delay(10)
            engine.release.complete(Unit)
          }
          val asset = ReleaseAsset("ketch.tar.gz", URL, content.size.toLong(), sha256)
          downloadWithProgress(
            asset,
            File(dir, asset.name),
            ProgressLine(out, interactive = true),
            Logger.console(LogLevel.WARN),
          ) { engine }
        }
      }
    } finally {
      System.setOut(stdout)
      System.setErr(stderr)
      // Ketch installs its logger for the whole process.
      KetchLogger.setLogger(Logger.None)
    }
  }

  /** The rows [terminal] shows, empty last one left out. */
  private fun screen(): List<String> {
    out.flush()
    err.flush()
    return terminalRows(terminal.toString(Charsets.UTF_8)).dropLastWhile { it.isEmpty() }
  }

  private fun sha256Of(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

  /**
   * Serves [content] without byte ranges: its first half, then the rest once [release] completes;
   * with [dropOnce], the first request drops its connection there instead.
   */
  private class HalvesEngine(
    private val content: ByteArray,
    dropOnce: Boolean,
  ) : HttpEngine {
    val release = CompletableDeferred<Unit>()
    private val dropped = AtomicBoolean(!dropOnce)

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
      val half = content.size / 2
      onData(content.copyOfRange(0, half))
      release.await()
      if (dropped.compareAndSet(false, true)) {
        throw KetchError.Network(IOException("Connection reset"))
      }
      onData(content.copyOfRange(half, content.size))
    }

    override fun close() {}
  }

  private companion object {
    const val URL = "https://example.com/ketch.tar.gz"

    /** A whole progress line, as `ketch update` shows it. */
    val PROGRESS = Regex("""  \d+%  \S+ \S+ / 64\.0 KB""")

    val PROGRESS_SHOWN = Regex("""  \d+%  """)
  }
}
