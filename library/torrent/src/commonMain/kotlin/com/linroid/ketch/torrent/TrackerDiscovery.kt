package com.linroid.ketch.torrent

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.TimeSource

/** Serialized per-session tracker lifecycle; callers supply whole-torrent verified state. */
internal class TrackerDiscovery private constructor(
  private val topic: TrackerTopic,
  private val privateTorrent: Boolean,
  private val remaining: (BooleanArray) -> Long,
  private val peerId: ByteArray,
  private val port: Int,
  private val trackers: TrackerTiers,
  onPrivateTrackerChanged: suspend () -> Unit = {},
  private val nowMs: () -> Long = monotonicClock(),
  private val announceCompletion: Boolean = true,
) {
  constructor(
    metadata: TorrentMetadata,
    peerId: ByteArray,
    port: Int,
    trackers: TrackerTiers,
    onPrivateTrackerChanged: suspend () -> Unit = {},
    nowMs: () -> Long = monotonicClock(),
    announceCompletion: Boolean = true,
  ) : this(TrackerTopic.V1(metadata.infoHash), metadata.isPrivate, { verified ->
    require(verified.size == metadata.pieceHashes.size / 20)
    verified.indices.sumOf { index ->
      if (verified[index]) 0L else minOf(metadata.pieceLength,
        metadata.totalBytes - index.toLong() * metadata.pieceLength)
    }
  }, peerId, port, trackers, onPrivateTrackerChanged, nowMs, announceCompletion)

  constructor(
    document: TorrentV2Document,
    layout: TorrentContentLayout,
    peerId: ByteArray,
    port: Int,
    trackers: TrackerTiers,
    onPrivateTrackerChanged: suspend () -> Unit = {},
    nowMs: () -> Long = monotonicClock(),
    announceCompletion: Boolean = true,
  ) : this(TrackerTopic.V2(document.info.hash), document.info.privateTorrent,
    layout::unverifiedPayloadBytes, peerId, port, trackers, onPrivateTrackerChanged,
    nowMs, announceCompletion) {
    require(layout.infoHash == document.info.hash) { "Tracker layout belongs to another torrent" }
  }

  init { if (privateTorrent) trackers.preferCurrentTracker(onPrivateTrackerChanged) }

  private var nextAnnounce = 0L
  private var nextManualAnnounce = 0L
  private var retryAt = 0L
  private var retryDelayMs = 15_000L
  private var started = false
  private var completed = false
  private val key = okio.Buffer().write(torrentRandomBytes(4)).readInt()

  /** Replace on the session owner; endpoint authorization is the caller's responsibility. */
  suspend fun replaceTrackers(
    tiers: List<List<String>>,
    verified: BooleanArray,
    downloaded: Long,
    uploaded: Long,
    stopTimeoutMs: Long = 2000,
  ) {
    require(stopTimeoutMs in 1..30_000 && downloaded >= 0 && uploaded >= 0)
    val replacement = TrackerConfiguration.prepare(tiers)
    remaining(verified) // Validate state before contacting the old configuration.
    if (started) withTimeoutOrNull(stopTimeoutMs) {
      try {
        poll(verified, downloaded, uploaded, stopped = true)
      } catch (error: CancellationException) {
        throw error
      } catch (_: Exception) {
        currentCoroutineContext().ensureActive()
      }
    }
    currentCoroutineContext().ensureActive()
    trackers.replace(replacement)
    started = false
    completed = false
    nextAnnounce = 0L
    nextManualAnnounce = 0L
    retryAt = 0L
    retryDelayMs = 15_000L
  }

  suspend fun poll(
    verified: BooleanArray,
    downloaded: Long,
    uploaded: Long,
    stopped: Boolean = false,
    manual: Boolean = false,
  ): TrackerResponse? {
    val left = remaining(verified)
    if (!stopped && nowMs() < retryAt) return null
    val event = when {
      stopped && started -> TrackerEvent.STOPPED
      stopped -> return null
      !started -> TrackerEvent.STARTED
      left == 0L && !completed && announceCompletion -> TrackerEvent.COMPLETED
      else -> TrackerEvent.NONE
    }
    if (event == TrackerEvent.NONE) {
      val deadline = if (manual) nextManualAnnounce else nextAnnounce
      if (nowMs() < deadline) return null
    }
    val result = try {
      trackers.announce(TrackerAnnounce(topic, peerId, port, downloaded,
        left, uploaded, event, key))
    } catch (error: CancellationException) {
      throw error
    } catch (error: Exception) {
      currentCoroutineContext().ensureActive()
      retryAt = nowMs() + retryDelayMs
      retryDelayMs = minOf(900_000L, retryDelayMs * 2)
      throw error
    }
    started = event != TrackerEvent.STOPPED
    if (event == TrackerEvent.COMPLETED || (event == TrackerEvent.STARTED && left == 0L)) {
      completed = true
    }
    retryAt = 0L
    retryDelayMs = 15_000L
    val now = nowMs()
    nextAnnounce = now + result.intervalSeconds * 1000
    // Without an advertised minimum (UDP), conservatively use the regular interval.
    nextManualAnnounce = now + maxOf(60L,
      result.minimumIntervalSeconds ?: result.intervalSeconds) * 1000
    return result
  }
}

internal fun monotonicClock(): () -> Long {
  val origin = TimeSource.Monotonic.markNow()
  return { origin.elapsedNow().inWholeMilliseconds }
}
