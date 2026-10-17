package com.linroid.ketch.torrent

import com.linroid.ketch.api.resolve
import com.linroid.ketch.core.Ketch
import com.linroid.ketch.core.engine.HttpEngine
import com.linroid.ketch.core.engine.ServerInfo
import com.linroid.ketch.core.engine.resolve
import com.linroid.ketch.torrent.engine.TorrentEngine
import com.linroid.ketch.torrent.metainfo.InfoHash
import com.linroid.ketch.torrent.metainfo.TorrentMetadata
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class TorrentSourceStopTest {
  private val hash = "aabbccddee11223344556677889900aabbccddee"
  private val magnet = "magnet:?xt=urn:btih:$hash"

  @Test
  fun stop_returnsOnceTheEngineStopped_andNoEngineStartsAfter() = runTest {
    val stopped = CompletableDeferred<Unit>()
    val engine = StoppingEngine(onStop = { stopped.await() })
    val source = TorrentDownloadSource().also { it.engineFactory = { engine } }
    source.resolve(magnet)

    val stop = async { source.stop() }
    runCurrent()
    assertTrue(engine.stopCalled)
    assertFalse(stop.isCompleted)

    stopped.complete(Unit)
    stop.await()
    source.close()
    // stop() took the engine, so close() leaves it alone.
    assertFalse(engine.closeCalled)
    assertFailsWith<IllegalStateException> { source.resolve(magnet) }
  }

  @Test
  fun stop_whileTheEngineStarts_returnsOnceItStopped() = runTest {
    val started = CompletableDeferred<Unit>()
    val stopped = CompletableDeferred<Unit>()
    val engine = StoppingEngine(onStop = { stopped.await() }, onStart = { started.await() })
    val source = TorrentDownloadSource().also { it.engineFactory = { engine } }
    // Starts the engine, which is still binding its listener.
    val resolve = async { runCatching { source.resolve(magnet) } }
    runCurrent()

    val stop = async { source.stop() }
    runCurrent()
    started.complete(Unit)
    runCurrent()
    assertTrue(engine.stopCalled)
    assertFalse(stop.isCompleted)

    stopped.complete(Unit)
    stop.await()
    assertTrue(resolve.await().isFailure)
    source.close()
  }

  @Test
  fun ketchShutdown_stopsTheEngineWhileTheHttpEngineStillRuns() = runTest {
    val http = RecordingHttpEngine()
    val engine = StoppingEngine(onStop = { http.get(STOPPED_ANNOUNCE) })
    val source = TorrentDownloadSource(httpEngine = http).also { it.engineFactory = { engine } }
    val ketch = Ketch(httpEngine = http, additionalSources = listOf(source))
    ketch.resolve(magnet)

    ketch.shutdown(10.seconds)

    // Its sessions' `stopped` announces reached the trackers.
    assertEquals(listOf(STOPPED_ANNOUNCE), http.requests)
    assertTrue(http.closed)
  }

  private fun metadata() = TorrentMetadata(
    infoHash = InfoHash.fromHex(hash),
    name = "test.txt",
    pieceLength = 262144L,
    totalBytes = 1024L,
    files = listOf(TorrentMetadata.TorrentFile(index = 0, path = "test.txt", size = 1024L)),
    trackers = listOf("http://tracker.example.com/announce"),
  )

  /**
   * Finds [metadata] for any magnet, runs [onStart] as it starts, as binding its listener takes
   * a while, and [onStop] as it stops, as sessions announce.
   */
  private inner class StoppingEngine(
    private val onStop: suspend () -> Unit,
    private val onStart: suspend () -> Unit = {},
    private val fake: FakeTorrentEngine = FakeTorrentEngine(),
  ) : TorrentEngine by fake {
    var stopCalled = false
      private set
    var closeCalled = false
      private set

    init {
      fake.fetchMetadataResult = metadata()
    }

    override suspend fun start() {
      onStart()
      fake.start()
    }

    override suspend fun stop() {
      stopCalled = true
      onStop()
      fake.stop()
    }

    override fun close() {
      closeCalled = true
    }
  }

  /** Records what is fetched, and refuses to once closed, as Ktor's engine does. */
  private class RecordingHttpEngine : HttpEngine {
    val requests = mutableListOf<String>()
    var closed = false
      private set

    suspend fun get(url: String) = download(url, null, emptyMap()) {}

    override suspend fun head(url: String, headers: Map<String, String>): ServerInfo =
      error("Unexpected HEAD")

    override suspend fun download(
      url: String,
      range: LongRange?,
      headers: Map<String, String>,
      onData: suspend (ByteArray) -> Unit,
    ) {
      check(!closed) { "HTTP engine is closed" }
      requests += url
    }

    override fun close() {
      closed = true
    }
  }

  private companion object {
    const val STOPPED_ANNOUNCE = "http://tracker.example.com/announce?event=stopped"
  }
}
