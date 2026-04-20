package com.linroid.ketch.torrent

import com.linroid.ketch.api.log.KetchLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import org.libtorrent4j.TorrentHandle
import org.libtorrent4j.TorrentStatus

/**
 * [TorrentSession] implementation wrapping a libtorrent4j
 * [TorrentHandle].
 */
internal class Libtorrent4jSession(
  private val handle: TorrentHandle,
  override val infoHash: String,
  override val totalBytes: Long,
) : TorrentSession {

  private val log = KetchLogger("TorrentSession")

  private val _downloadedBytes = MutableStateFlow(0L)
  override val downloadedBytes: StateFlow<Long> = _downloadedBytes

  private val _state =
    MutableStateFlow(TorrentSessionState.CHECKING_METADATA)
  override val state: StateFlow<TorrentSessionState> = _state

  override val downloadSpeed: Long
    get() {
      updateStatus()
      return handle.status().downloadRate().toLong()
    }

  override suspend fun pause() = withContext(Dispatchers.IO) {
    handle.pause()
    _state.value = TorrentSessionState.PAUSED
    log.d { "Paused torrent: $infoHash" }
  }

  override suspend fun resume() = withContext(Dispatchers.IO) {
    handle.resume()
    _state.value = TorrentSessionState.DOWNLOADING
    log.d { "Resumed torrent: $infoHash" }
  }

  override fun setFilePriorities(priorities: Map<Int, Int>) {
    for ((index, priority) in priorities) {
      handle.filePriority(index, PRIORITY_MAP[priority] ?: continue)
    }
  }

  override fun setDownloadRateLimit(bytesPerSecond: Long) {
    handle.setDownloadLimit(bytesPerSecond.toInt())
    log.d { "Download rate limit for $infoHash: $bytesPerSecond B/s" }
  }

  override suspend fun saveResumeData(): ByteArray? =
    withContext(Dispatchers.IO) {
      try {
        handle.saveResumeData()
        // libtorrent4j delivers resume data asynchronously via
        // alert; for simplicity we return null here and let the
        // engine handle resume data persistence via alerts
        null
      } catch (e: Exception) {
        log.w(e) { "Failed to save resume data for $infoHash" }
        null
      }
    }

  internal fun updateStatus() {
    val status = handle.status()
    _downloadedBytes.value = status.totalDone()
    _state.value = mapState(status.state())
  }

  private fun mapState(
    state: TorrentStatus.State,
  ): TorrentSessionState = when (state) {
    TorrentStatus.State.CHECKING_FILES ->
      TorrentSessionState.CHECKING_FILES
    TorrentStatus.State.DOWNLOADING_METADATA ->
      TorrentSessionState.CHECKING_METADATA
    TorrentStatus.State.DOWNLOADING ->
      TorrentSessionState.DOWNLOADING
    TorrentStatus.State.FINISHED ->
      TorrentSessionState.FINISHED
    TorrentStatus.State.SEEDING ->
      TorrentSessionState.SEEDING
    TorrentStatus.State.CHECKING_RESUME_DATA ->
      TorrentSessionState.CHECKING_FILES
    else -> TorrentSessionState.STOPPED
  }

  companion object {
    private val PRIORITY_MAP = mapOf(
      0 to org.libtorrent4j.Priority.IGNORE,
      4 to org.libtorrent4j.Priority.DEFAULT,
      7 to org.libtorrent4j.Priority.TOP_PRIORITY,
    )
  }
}
