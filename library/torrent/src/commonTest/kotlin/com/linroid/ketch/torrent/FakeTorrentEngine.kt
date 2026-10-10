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

  /** Results of the next lookups, in order, before [fetchMetadataResult] applies. */
  val fetchMetadataResults = ArrayDeque<TorrentMetadata?>()
  var fetchMetadataCalls = 0
    private set
  var addTorrentResult: FakeTorrentSession? = null
  var addTorrentError: Exception? = null
  var removedTorrents = mutableListOf<Pair<String, Boolean>>()
  var downloadRateLimit = 0L
    private set
  var uploadRateLimit = 0L
    private set
  var uploadPolicy: TorrentUploadPolicy? = null
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
    fetchMetadataCalls++
    fetchMetadataError?.let { throw it }
    if (fetchMetadataResults.isNotEmpty()) return fetchMetadataResults.removeFirst()
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

  override suspend fun setUploadPolicy(policy: TorrentUploadPolicy) {
    uploadPolicy = policy
  }
}

/**
 * Fake [TorrentSession] for testing download/resume flows.
 */
internal class FakeTorrentSession(
  override val infoHash: String,
  override var totalBytes: Long = 0,
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
  override var selectedFileIds: Set<String> = emptySet()

  /** Every [changeSelection] call, in order. */
  val selectionChanges = mutableListOf<Set<String>>()

  /** What [changeSelection] returns: whether the change was taken live. */
  var changeSelectionResult = true
  var changeSelectionError: Exception? = null
  var counters = TorrentPayloadCounters(received = 0, uploaded = 0, uploadSpeed = null)
  var sessionDownloadRateLimit = 0L
    private set
  var sessionUploadRateLimit = 0L
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

  override suspend fun changeSelection(fileIds: Set<String>): Boolean {
    selectionChanges += fileIds
    changeSelectionError?.let { throw it }
    selectedFileIds = fileIds
    return changeSelectionResult
  }

  override suspend fun payloadCounters(): TorrentPayloadCounters = counters

  override fun setDownloadRateLimit(bytesPerSecond: Long) {
    sessionDownloadRateLimit = bytesPerSecond
  }

  override fun setUploadRateLimit(bytesPerSecond: Long) {
    sessionUploadRateLimit = bytesPerSecond
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

  fun seed() {
    _state.value = TorrentSessionState.SEEDING
  }

  fun stopUnexpectedly() {
    _state.value = TorrentSessionState.STOPPED
  }
}
