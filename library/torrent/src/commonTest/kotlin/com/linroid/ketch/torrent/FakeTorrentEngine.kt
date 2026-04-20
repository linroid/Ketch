package com.linroid.ketch.torrent

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Fake [TorrentEngine] for testing [TorrentDownloadSource] without
 * a real libtorrent backend.
 */
internal class FakeTorrentEngine : TorrentEngine {

  var started = false
    private set
  var stopped = false
    private set

  override var isRunning: Boolean = false
    private set

  var fetchMetadataResult: TorrentMetadata? = null
  var fetchMetadataError: Exception? = null
  var addTorrentResult: FakeTorrentSession? = null
  var addTorrentError: Exception? = null
  var removedTorrents = mutableListOf<Pair<String, Boolean>>()
  var downloadRateLimit = 0L
    private set
  var uploadRateLimit = 0L
    private set

  override suspend fun start() {
    started = true
    isRunning = true
  }

  override suspend fun stop() {
    stopped = true
    isRunning = false
  }

  override suspend fun fetchMetadata(
    magnetUri: String,
  ): TorrentMetadata? {
    fetchMetadataError?.let { throw it }
    return fetchMetadataResult
  }

  override suspend fun addTorrent(
    infoHash: String,
    savePath: String,
    magnetUri: String?,
    torrentData: ByteArray?,
    selectedFileIndices: Set<Int>,
    resumeData: ByteArray?,
  ): TorrentSession {
    addTorrentError?.let { throw it }
    return addTorrentResult
      ?: throw IllegalStateException("addTorrentResult not set")
  }

  override suspend fun removeTorrent(
    infoHash: String,
    deleteFiles: Boolean,
  ) {
    removedTorrents.add(infoHash to deleteFiles)
  }

  override fun setDownloadRateLimit(bytesPerSecond: Long) {
    downloadRateLimit = bytesPerSecond
  }

  override fun setUploadRateLimit(bytesPerSecond: Long) {
    uploadRateLimit = bytesPerSecond
  }
}

/**
 * Fake [TorrentSession] for testing download/resume flows.
 */
internal class FakeTorrentSession(
  override val infoHash: String,
  override val totalBytes: Long = 0,
) : TorrentSession {

  private val _downloadedBytes = MutableStateFlow(0L)
  override val downloadedBytes: StateFlow<Long> = _downloadedBytes

  private val _state =
    MutableStateFlow(TorrentSessionState.DOWNLOADING)
  override val state: StateFlow<TorrentSessionState> = _state

  override var downloadSpeed: Long = 0L

  var paused = false
    private set
  var resumed = false
    private set
  var filePriorities = emptyMap<Int, Int>()
    private set
  var sessionDownloadRateLimit = 0L
    private set
  var savedResumeData: ByteArray? = null

  override suspend fun pause() {
    paused = true
    _state.value = TorrentSessionState.PAUSED
  }

  override suspend fun resume() {
    resumed = true
    _state.value = TorrentSessionState.DOWNLOADING
  }

  override fun setFilePriorities(priorities: Map<Int, Int>) {
    filePriorities = priorities
  }

  override fun setDownloadRateLimit(bytesPerSecond: Long) {
    sessionDownloadRateLimit = bytesPerSecond
  }

  override suspend fun saveResumeData(): ByteArray? {
    return savedResumeData
  }

  // Test helpers

  fun setDownloaded(bytes: Long) {
    _downloadedBytes.value = bytes
  }

  fun finish() {
    _state.value = TorrentSessionState.FINISHED
  }

  fun stopUnexpectedly() {
    _state.value = TorrentSessionState.STOPPED
  }
}
