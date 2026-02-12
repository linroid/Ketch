package com.linroid.kdown.app

import com.linroid.kdown.api.DownloadRequest
import com.linroid.kdown.api.DownloadTask
import com.linroid.kdown.api.KDownApi
import com.linroid.kdown.api.KDownVersion
import com.linroid.kdown.api.ResolvedSource
import com.linroid.kdown.api.SpeedLimit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * A fake [KDownApi] for testing backend switching logic.
 * Tracks lifecycle calls and exposes configurable state.
 */
class FakeKDownApi(
  override val backendLabel: String = "Fake",
) : KDownApi {

  private val _tasks = MutableStateFlow<List<DownloadTask>>(emptyList())
  override val tasks: StateFlow<List<DownloadTask>> = _tasks.asStateFlow()

  private val _version = MutableStateFlow(
    KDownVersion(KDownVersion.DEFAULT, KDownVersion.DEFAULT)
  )
  override val version: StateFlow<KDownVersion> = _version.asStateFlow()

  var closed = false
    private set
  var closeCallCount = 0
    private set
  var downloadCallCount = 0
    private set
  var lastSpeedLimit: SpeedLimit? = null
    private set

  override suspend fun download(
    request: DownloadRequest,
  ): DownloadTask {
    downloadCallCount++
    throw UnsupportedOperationException(
      "FakeKDownApi does not support downloads"
    )
  }

  override suspend fun resolve(
    url: String,
    headers: Map<String, String>,
  ): ResolvedSource {
    throw UnsupportedOperationException(
      "FakeKDownApi does not support resolve"
    )
  }

  override suspend fun setGlobalSpeedLimit(limit: SpeedLimit) {
    lastSpeedLimit = limit
  }

  override suspend fun start() {}

  override fun close() {
    closed = true
    closeCallCount++
  }
}
