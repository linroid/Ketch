package com.linroid.ketch.engine

import com.linroid.ketch.api.Destination
import com.linroid.ketch.api.DownloadConfig
import com.linroid.ketch.api.DownloadRequest
import com.linroid.ketch.api.DownloadState
import com.linroid.ketch.api.DownloadTask
import com.linroid.ketch.api.KetchError
import com.linroid.ketch.core.Ketch
import com.linroid.ketch.core.engine.HttpEngine
import com.linroid.ketch.sqlite.DriverFactory
import com.linroid.ketch.sqlite.SqliteTaskStore
import com.sun.net.httpserver.HttpServer
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.Collections
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class HttpDownloadIntegrationTest {
  @Test
  fun download_edgeSizes_preservesEveryByte() = runTest(timeout = 30.seconds) {
    for (size in listOf(0, 1, 17, 65539)) {
      withFixture(size) { fixture ->
        val state = fixture.download(connections = 4)
        val file = File(assertIs<DownloadState.Completed>(state).outputPath)
        assertContentEquals(fixture.content, file.readBytes(), "size=$size")
      }
    }
  }

  @Test
  fun download_noRangeSupport_usesSingleCompleteTransfer() = runTest(timeout = 20.seconds) {
    withFixture(65539, Mode.NO_RANGES) { fixture ->
      val state = fixture.download(connections = 4)
      val file = File(assertIs<DownloadState.Completed>(state).outputPath)
      assertContentEquals(fixture.content, file.readBytes())
      assertEquals(1, fixture.requests.size)
    }
  }

  @Test
  fun download_ignoredRange_failsInsteadOfCorruptingFile() = runTest(timeout = 20.seconds) {
    withFixture(65539, Mode.IGNORE_RANGES) { fixture ->
      val state = assertIs<DownloadState.Failed>(fixture.download(connections = 4))
      assertIs<KetchError.Unsupported>(state.error)
    }
  }

  @Test
  fun download_wrongRange_failsInsteadOfCorruptingFile() = runTest(timeout = 20.seconds) {
    withFixture(65539, Mode.WRONG_RANGE) { fixture ->
      val state = assertIs<DownloadState.Failed>(fixture.download(connections = 4))
      assertIs<KetchError.Unsupported>(state.error)
    }
  }

  @Test
  fun download_transientServerError_retriesSuccessfully() = runTest(timeout = 20.seconds) {
    withFixture(65539, Mode.FAIL_FIRST) { fixture ->
      val state = fixture.download(connections = 1)
      val file = File(assertIs<DownloadState.Completed>(state).outputPath)
      assertContentEquals(fixture.content, file.readBytes())
      assertEquals(2, fixture.requests.size)
    }
  }

  @Test
  fun download_interruptedBody_retriesFromWrittenOffset() = runTest(timeout = 20.seconds) {
    withFixture(65539, Mode.TRUNCATE_FIRST) { fixture ->
      val state = fixture.download(connections = 1)
      val file = File(assertIs<DownloadState.Completed>(state).outputPath)
      assertContentEquals(fixture.content, file.readBytes())
      assertEquals(listOf("bytes=0-65538", "bytes=16384-65538"), fixture.requests.toList())
    }
  }

  @Test
  fun pause_capturesWrittenBytesBeforeProgressInterval() = runTest(timeout = 20.seconds) {
    withFixture(65539, Mode.PAUSE_FIRST) { fixture ->
      val task = fixture.start(connections = 1)
      fixture.firstChunk.await()
      task.pause()
      val paused = assertIs<DownloadState.Paused>(task.state.value)
      val written = task.segments.value.sumOf { it.downloadedBytes }
      assertTrue(written > 0)
      assertEquals(written, paused.progress.downloadedBytes)
      task.resume()
      val state = task.state.first { it.isTerminal }
      val file = File(assertIs<DownloadState.Completed>(state).outputPath)
      assertContentEquals(fixture.content, file.readBytes())
    }
  }

  @Test
  fun setConnections_restartsActiveTransferWithoutLosingBytes() = runTest(timeout = 20.seconds) {
    withFixture(65539, Mode.PAUSE_FIRST) { fixture ->
      val task = fixture.start(connections = 1)
      fixture.firstChunk.await()
      task.setConnections(2)
      val state = task.state.first { it.isTerminal }
      val file = File(assertIs<DownloadState.Completed>(state).outputPath)
      assertContentEquals(fixture.content, file.readBytes())
      assertTrue(task.segments.value.all { it.isComplete })
      assertEquals(3, fixture.requests.size)
    }
  }

  @Test
  fun cancel_activeTransfer_cleansUpPartialFile() = runTest(timeout = 20.seconds) {
    withFixture(65539, Mode.PAUSE_FIRST) { fixture ->
      val task = fixture.start(connections = 1)
      fixture.firstChunk.await()
      task.cancel()
      assertEquals(DownloadState.Canceled, task.state.value)
      assertTrue(!fixture.output.exists())
    }
  }

  @Test
  fun resume_changedServerIdentity_failsBeforeDownloading() = runTest(timeout = 20.seconds) {
    withFixture(65539, Mode.PAUSE_FIRST) { fixture ->
      val task = fixture.start(connections = 1)
      fixture.firstChunk.await()
      task.pause()
      fixture.etag.set("fixture-v2")
      task.resume()
      val state = assertIs<DownloadState.Failed>(task.state.first { it.isTerminal })
      assertIs<KetchError.FileChanged>(state.error)
      assertEquals(1, fixture.requests.size)
    }
  }

  @Test
  fun download_transientHeadFailure_retriesProbe() = runTest(timeout = 20.seconds) {
    withFixture(17, Mode.HEAD_FAIL_FIRST) { fixture ->
      val state = fixture.download(connections = 1)
      assertContentEquals(fixture.content, File(assertIs<DownloadState.Completed>(state).outputPath)
        .readBytes())
      assertEquals(2, fixture.headAttempts.get())
    }
  }

  @Test
  fun download_missingResource_failsWithoutRetryOrOutputFile() = runTest(timeout = 20.seconds) {
    withFixture(17, Mode.NOT_FOUND) { fixture ->
      val state = assertIs<DownloadState.Failed>(fixture.download(connections = 1))
      assertEquals(404, assertIs<KetchError.Http>(state.error).code)
      assertEquals(1, fixture.headAttempts.get())
      assertTrue(!fixture.output.exists())
    }
  }

  @Test
  fun restart_pausedSqliteTask_resumesFromSavedOffset() = runTest(timeout = 20.seconds) {
    withFixture(65539, Mode.PAUSE_FIRST) { fixture ->
      val original = fixture.start(connections = 1)
      fixture.firstChunk.await()
      original.pause()
      val saved = original.segments.value.sumOf { it.downloadedBytes }
      val restored = fixture.restart()
      assertEquals(original.taskId, restored.taskId)
      val paused = assertIs<DownloadState.Paused>(restored.state.value)
      assertEquals(saved, paused.progress.downloadedBytes)
      restored.resume()
      val state = restored.state.first { it.isTerminal }
      assertContentEquals(fixture.content, File(assertIs<DownloadState.Completed>(state).outputPath)
        .readBytes())
      assertEquals("bytes=$saved-65538", fixture.requests.last())
    }
  }

  @Test
  fun resume_truncatedLocalFile_redownloadsMissingData() = runTest(timeout = 20.seconds) {
    withFixture(65539, Mode.PAUSE_FIRST) { fixture ->
      val task = fixture.start(connections = 1)
      fixture.firstChunk.await()
      task.pause()
      fixture.output.writeBytes(byteArrayOf(0))
      task.resume()
      val state = task.state.first { it.isTerminal }
      assertContentEquals(fixture.content, File(assertIs<DownloadState.Completed>(state).outputPath)
        .readBytes())
      assertEquals("bytes=0-65538", fixture.requests.last())
    }
  }

  @Test
  fun download_extendedServerFilename_usesDecodedName() = runTest(timeout = 20.seconds) {
    withFixture(17, Mode.UNICODE_NAME) { fixture ->
      val task = fixture.start(connections = 1, serverFileName = true)
      val state = task.state.first { it.isTerminal }
      val file = File(assertIs<DownloadState.Completed>(state).outputPath)
      assertEquals("中文.txt", file.name)
      assertContentEquals(fixture.content, file.readBytes())
    }
  }

  @Test
  fun download_emptyFile_replacesExistingExplicitDestination() = runTest(timeout = 20.seconds) {
    withFixture(0) { fixture ->
      fixture.output.writeBytes(byteArrayOf(1, 2, 3))
      val state = fixture.download(connections = 1)
      val file = File(assertIs<DownloadState.Completed>(state).outputPath)
      assertTrue(file.exists())
      assertEquals(0L, file.length())
    }
  }

  private suspend fun withFixture(
    size: Int,
    mode: Mode = Mode.NORMAL,
    block: suspend (Fixture) -> Unit,
  ) = withContext(Dispatchers.IO) {
    val fixture = Fixture(size, mode)
    try {
      block(fixture)
    } finally {
      fixture.close()
    }
  }

  private enum class Mode {
    NORMAL, NO_RANGES, IGNORE_RANGES, WRONG_RANGE, FAIL_FIRST, TRUNCATE_FIRST, PAUSE_FIRST,
    HEAD_FAIL_FIRST, NOT_FOUND, UNICODE_NAME
  }

  private class Fixture(size: Int, private val mode: Mode) {
    val content = ByteArray(size) { ((it * 31 + it / 251) % 256).toByte() }
    val requests: MutableList<String> = Collections.synchronizedList(mutableListOf())
    val firstChunk = CompletableDeferred<Unit>()
    val etag = AtomicReference("fixture-v1")
    val headAttempts = AtomicInteger()
    private val attempts = AtomicInteger()
    private val directory = Files.createTempDirectory("ketch-http-integration-").toFile()
    val output = File(directory, "download.bin")
    private val executor = Executors.newCachedThreadPool()
    private val server = HttpServer.create(
      InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0
    )
    private val driverFactory = DriverFactory(File(directory, "tasks.db").absolutePath)
    private var driver = driverFactory.createDriver()
    private var ketch = newKetch()

    private fun newKetch(): Ketch {
      val transport = KtorHttpEngine()
      val engine = object : HttpEngine by transport {
        override suspend fun download(
          url: String,
          range: LongRange?,
          headers: Map<String, String>,
          onData: suspend (ByteArray) -> Unit,
        ) {
          transport.download(url, range, headers) { bytes ->
            onData(bytes)
            if (mode == Mode.PAUSE_FIRST && firstChunk.complete(Unit)) awaitCancellation()
          }
        }
      }
      return Ketch(
        httpEngine = engine,
        taskStore = SqliteTaskStore(driver),
        config = DownloadConfig(retryCount = 1, retryDelayMs = 0, progressIntervalMs = 200),
      )
    }

    suspend fun restart(): DownloadTask {
      ketch.close()
      driver.close()
      driver = driverFactory.createDriver()
      ketch = newKetch()
      ketch.start()
      return ketch.tasks.value.single()
    }

    init {
      server.executor = executor
      server.createContext("/file") { exchange ->
        try {
          exchange.use {
            if (mode != Mode.NO_RANGES) it.responseHeaders.add("Accept-Ranges", "bytes")
            it.responseHeaders.add("ETag", etag.get())
            if (mode == Mode.UNICODE_NAME) {
              it.responseHeaders.add("Content-Disposition",
                "attachment; filename*=UTF-8''%E4%B8%AD%E6%96%87.txt; size=17")
            }
            if (it.requestMethod == "HEAD") {
              val headAttempt = headAttempts.incrementAndGet()
              if (mode == Mode.NOT_FOUND || mode == Mode.HEAD_FAIL_FIRST && headAttempt == 1) {
                it.sendResponseHeaders(if (mode == Mode.NOT_FOUND) 404 else 503, -1)
                return@use
              }
              it.responseHeaders.add("Content-Length", content.size.toString())
              it.sendResponseHeaders(200, -1)
              return@use
            }
            val range = it.requestHeaders.getFirst("Range")
            requests.add(range ?: "none")
            val attempt = attempts.incrementAndGet()
            if (mode == Mode.FAIL_FIRST && attempt == 1) {
              it.sendResponseHeaders(503, -1)
              return@use
            }
            val ignore = mode == Mode.NO_RANGES || mode == Mode.IGNORE_RANGES
            val (start, end) = if (range == null || ignore) {
              listOf(0, content.lastIndex)
            } else {
              range.removePrefix("bytes=").split('-').map(String::toInt)
            }
            if (range != null && !ignore) {
              val offset = if (mode == Mode.WRONG_RANGE) 1 else 0
              it.responseHeaders.add(
                "Content-Range", "bytes ${start + offset}-${end + offset}/${content.size}"
              )
            }
            val status = if (range == null || ignore) 200 else 206
            it.sendResponseHeaders(status, (end - start + 1).toLong())
            if (mode == Mode.TRUNCATE_FIRST && attempt == 1) {
              it.responseBody.write(content, start, 16384)
              it.responseBody.flush()
            } else {
              it.responseBody.write(content, start, end - start + 1)
            }
          }
        } catch (_: IOException) {
          // Expected when intentionally truncating a response or the downloader cancels siblings.
          exchange.close()
        }
      }
      server.start()
    }

    suspend fun download(connections: Int): DownloadState {
      val task = start(connections)
      val state = task.state.first { it.isTerminal }
      if (state is DownloadState.Completed) {
        assertTrue(task.segments.value.all { it.isComplete })
      }
      return state
    }

    suspend fun start(
      connections: Int,
      serverFileName: Boolean = false,
    ): DownloadTask = ketch.download(
      DownloadRequest(
        url = "http://127.0.0.1:${server.address.port}/file",
        destination = Destination(if (serverFileName) "${directory.absolutePath}/" else
          output.absolutePath),
        connections = connections,
      )
    )

    fun close() {
      ketch.close()
      server.stop(0)
      executor.shutdownNow()
      driver.close()
      directory.deleteRecursively()
    }
  }
}
