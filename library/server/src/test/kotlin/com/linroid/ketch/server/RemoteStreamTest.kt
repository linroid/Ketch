package com.linroid.ketch.server

import com.linroid.ketch.api.ControlKey
import com.linroid.ketch.api.Destination
import com.linroid.ketch.api.DownloadRequest
import com.linroid.ketch.api.DownloadState
import com.linroid.ketch.api.Interest
import com.linroid.ketch.api.KetchApi
import com.linroid.ketch.core.Ketch
import com.linroid.ketch.core.engine.HttpEngine
import com.linroid.ketch.core.engine.ServerInfo
import com.linroid.ketch.remote.ConnectionState
import com.linroid.ketch.remote.RemoteKetch
import com.linroid.ketch.torrent.api.TorrentActivity
import com.linroid.ketch.torrent.api.TorrentCapabilities
import com.linroid.ketch.torrent.api.TorrentCommandContext
import com.linroid.ketch.torrent.api.TorrentCommandResult
import com.linroid.ketch.torrent.api.TorrentController
import com.linroid.ketch.torrent.api.TorrentFileOrder
import com.linroid.ketch.torrent.api.TorrentFilePage
import com.linroid.ketch.torrent.api.TorrentInterest
import com.linroid.ketch.torrent.api.TorrentPageRequest
import com.linroid.ketch.torrent.api.TorrentRevision
import com.linroid.ketch.torrent.api.TorrentSnapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/** A [RemoteKetch] reading a [KetchServer]'s event stream over a real socket. */
class RemoteStreamTest {

  /** Answers a ranged 1 MiB file and sends it a kilobyte every 20 ms. */
  private class TrickleEngine : HttpEngine {
    override suspend fun head(url: String, headers: Map<String, String>) = ServerInfo(
      contentLength = SIZE,
      acceptRanges = true,
      etag = "\"v1\"",
      lastModified = null,
    )

    override suspend fun download(
      url: String,
      range: LongRange?,
      headers: Map<String, String>,
      onData: suspend (ByteArray) -> Unit,
    ) {
      var left = range?.let { it.last - it.first + 1 } ?: SIZE
      while (left > 0) {
        delay(20)
        val chunk = minOf(1024L, left).toInt()
        onData(ByteArray(chunk))
        left -= chunk
      }
    }

    override fun close() {}

    companion object {
      const val SIZE = 1024L * 1024
    }
  }

  private suspend fun withServer(
    ketch: KetchApi,
    block: suspend CoroutineScope.(RemoteKetch, File) -> Unit,
  ) = withContext(Dispatchers.Default) {
    withTimeout(30_000) {
      val folder = Files.createTempDirectory("ketch-remote-stream").toFile()
      val server = KetchServer(
        ketch,
        port = 0,
        allowedDirectories = listOf(folder.path),
        mdnsEnabled = false,
      )
      var remote: RemoteKetch? = null
      try {
        ketch.start()
        server.start(wait = false)
        val client = RemoteKetch("127.0.0.1", server.port(), progressInterval = null)
          .also { remote = it }
        client.start()
        client.connectionState.first { it == ConnectionState.Connected }
        block(client, folder)
      } finally {
        remote?.close()
        server.stop()
        ketch.close()
        folder.deleteRecursively()
      }
    }
  }

  @Test
  fun remote_getsProgressAtItsInterval_segmentsWhileFollowed_andStateChangesAtOnce() = runTest {
    val ketch = Ketch(httpEngine = TrickleEngine())
    withServer(ketch) { client, folder ->
      val task = ketch.download(
        DownloadRequest(
          url = "https://files.example/big.bin",
          destination = Destination(folder.path + "/"),
          connections = 1,
        ),
      )
      val remoteTask = client.tasks.first { tasks -> tasks.any { it.taskId == task.taskId } }
        .first { it.taskId == task.taskId }
      remoteTask.state.first { it is DownloadState.Downloading }
      // Settles the state changes that come with the start, such as the resolved source.
      delay(500)
      val before = assertIs<DownloadState.Downloading>(remoteTask.state.value).progress
      val segmentsBefore = remoteTask.segments.value

      // Without a progress interval, progress moves on the server only.
      task.state.first {
        (it as? DownloadState.Downloading)?.progress?.downloadedBytes?.let { bytes ->
          bytes > before.downloadedBytes + 20 * 1024
        } == true
      }
      delay(300)
      assertEquals(before, assertIs<DownloadState.Downloading>(remoteTask.state.value).progress)

      client.progressInterval = 200.milliseconds
      remoteTask.state.first {
        (it as? DownloadState.Downloading)?.progress?.downloadedBytes?.let { bytes ->
          bytes > before.downloadedBytes
        } == true
      }
      // Segments only come while followed.
      assertEquals(segmentsBefore, remoteTask.segments.value)
      val segments = client.subscribe(Interest.Segments(setOf(task.taskId)), 200.milliseconds)
        .first { bySegments ->
          bySegments[task.taskId].orEmpty().sumOf { it.downloadedBytes } >
            segmentsBefore.sumOf { it.downloadedBytes }
        }
      assertEquals(segments[task.taskId], remoteTask.segments.value)

      val stats = client.subscribe(Interest.Stats, 500.milliseconds).first { it.downloading == 1 }
      assertEquals(TrickleEngine.SIZE, stats.totalBytes)

      val connections = client.subscribe(Interest.Connections(limit = 4)).first { it.total >= 1 }
      assertTrue(connections.connections.all { it.taskId == task.taskId })

      task.pause()
      remoteTask.state.first { it is DownloadState.Paused }
    }
  }

  @Test
  fun remote_observesTorrentsOnTheStream() = runTest {
    val ketch = ControlledKetch(Ketch(httpEngine = TrickleEngine()), OneTorrent())
    withServer(ketch) { client, _ ->
      val snapshot = client.subscribe(TorrentInterest("t1")).first()

      assertEquals("t1", snapshot?.taskId)
    }
  }

  @Test
  fun remote_torrentThatIsGone_endsWithItsTombstone() = runTest {
    val ketch = ControlledKetch(Ketch(httpEngine = TrickleEngine()), OneTorrent())
    withServer(ketch) { client, _ ->
      assertEquals(listOf(null), client.subscribe(TorrentInterest("gone")).toList())
    }
  }

  private class ControlledKetch(
    private val delegate: KetchApi,
    private val torrents: OneTorrent,
  ) : KetchApi by delegate {
    @Suppress("UNCHECKED_CAST")
    override fun <T : Any> controller(key: ControlKey<T>): T? =
      if (key === TorrentController.Key) torrents as T else null

    @Suppress("UNCHECKED_CAST")
    override fun <T> subscribe(interest: Interest<T>, interval: Duration): Flow<T> =
      if (interest is TorrentInterest) {
        torrents.observe(interest.taskId) as Flow<T>
      } else {
        delegate.subscribe(interest, interval)
      }
  }

  /** One torrent, `t1`, whose snapshot never changes; any other is gone. */
  private class OneTorrent : TorrentController {
    override suspend fun capabilities() =
      TorrentCapabilities(names = setOf("inspect"), maxPageSize = 1000)

    override suspend fun snapshot(taskId: String): TorrentSnapshot? = null

    fun observe(taskId: String): Flow<TorrentSnapshot?> = flow {
      if (taskId != "t1") {
        emit(null)
        return@flow
      }
      emit(
        TorrentSnapshot(
          taskId = taskId,
          revision = TorrentRevision("epoch", 1),
          activity = TorrentActivity.DOWNLOADING,
          selectionGeneration = 1,
          selectionComplete = false,
        ),
      )
      awaitCancellation()
    }

    override suspend fun files(
      taskId: String,
      page: TorrentPageRequest,
      order: TorrentFileOrder,
      descending: Boolean,
    ): TorrentFilePage? = null

    override suspend fun select(
      taskId: String,
      fileIds: Set<String>,
      context: TorrentCommandContext,
    ): TorrentCommandResult = error("Unexpected call")

    override suspend fun setSeeding(
      taskId: String,
      seeding: Boolean,
      context: TorrentCommandContext,
    ): TorrentCommandResult = error("Unexpected call")
  }
}
