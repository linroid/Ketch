package com.linroid.ketch.torrent

import com.linroid.ketch.api.Destination
import com.linroid.ketch.api.DownloadRequest
import com.linroid.ketch.api.DownloadState
import com.linroid.ketch.api.ResolvedSource
import com.linroid.ketch.api.SpeedLimit
import com.linroid.ketch.core.Ketch
import com.linroid.ketch.core.engine.DownloadContext
import com.linroid.ketch.core.file.FileAccessor
import com.linroid.ketch.engine.KtorHttpEngine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.test.assertIs

internal fun sourceContext(
  url: String,
  resolved: ResolvedSource,
  output: String,
  taskId: String = "interop",
): DownloadContext =
  DownloadContext(taskId = taskId, url = url, request = DownloadRequest(url),
    fileAccessor = object : FileAccessor {
      override suspend fun writeAt(offset: Long, data: ByteArray) = Unit
      override suspend fun flush() = Unit
      override fun close() = Unit
      override suspend fun delete() = Unit
      override suspend fun size(): Long = 0
      override suspend fun preallocate(size: Long) = Unit
    },
    segments = MutableStateFlow(emptyList()), onProgress = { _, _ -> }, throttle = {},
    headers = emptyMap(), preResolved = resolved, outputPath = output,
  )

/** Connections Ketch's engine opened when the selection changed, and by the end. */
internal class LiveExpansion(val connectsAtChange: Int, val connects: Int)

/**
 * Downloads [first] of the torrent [metainfo] through Ketch from the peer [magnet] names, held
 * by a task speed limit, then chooses [expanded] while it runs, lifts the limit and waits until
 * the download completes in [output]. The source must leave nothing behind.
 */
@OptIn(ExperimentalAtomicApi::class)
internal suspend fun downloadExpandingLive(
  magnet: String,
  metainfo: ByteArray,
  output: String,
  first: Set<String>,
  expanded: Set<String>,
): LiveExpansion {
  val network = CountingTorrentNetwork()
  var engine: KotlinTorrentEngine? = null
  val source = TorrentDownloadSource(TorrentConfig(dhtEnabled = false)).also { source ->
    source.engineFactory = { config ->
      KotlinTorrentEngine(config, network, listenHost = "127.0.0.1").also { engine = it }
    }
  }
  val ketch = Ketch(KtorHttpEngine(), additionalSources = listOf(source))
  try {
    ketch.start()
    // One byte per second once the limiter's burst is spent: the task runs but cannot finish.
    val task = ketch.download(DownloadRequest(magnet, destination = Destination(output),
      resolvedSource = source.resolveMetainfo(metainfo), selectedFileIds = first,
      speedLimit = SpeedLimit.of(1)))
    awaitStage("the first verified bytes", task, timeoutMs = 30_000) {
      task.segments.first { segments -> segments.sumOf { it.downloadedBytes } > 0 }
    }
    assertIs<DownloadState.Downloading>(task.state.value)
    val atChange = network.connects.load()
    task.selectFiles(expanded)
    task.setSpeedLimit(SpeedLimit.Unlimited)
    awaitStage("completion", task, timeoutMs = 30_000) { task.await().getOrThrow() }
    source.assertNoLeaks()
    return LiveExpansion(atChange, network.connects.load())
  } finally {
    ketch.close()
    engine?.stop()
    engine?.assertNoLeaks()
  }
}
