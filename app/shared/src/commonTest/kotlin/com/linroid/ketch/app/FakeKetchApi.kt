package com.linroid.ketch.app

import com.linroid.ketch.api.DownloadRequest
import com.linroid.ketch.api.DownloadTask
import com.linroid.ketch.api.KetchApi
import com.linroid.ketch.api.KetchStatus
import com.linroid.ketch.api.ResolvedSource
import com.linroid.ketch.api.DownloadConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * A fake [KetchApi] for testing backend switching logic.
 * Tracks lifecycle calls and exposes configurable state.
 */
class FakeKetchApi(
  override val backendLabel: String = "Fake",
) : KetchApi {

  private val _tasks = MutableStateFlow<List<DownloadTask>>(emptyList())
  override val tasks: StateFlow<List<DownloadTask>> = _tasks.asStateFlow()

  var closed = false
    private set
  var closeCallCount = 0
    private set
  var downloadCallCount = 0
    private set
  var lastConfig: DownloadConfig? = null
    private set

  override suspend fun download(
    request: DownloadRequest,
  ): DownloadTask {
    downloadCallCount++
    throw UnsupportedOperationException(
      "FakeKetchApi does not support downloads"
    )
  }

  override suspend fun resolve(
    url: String,
    headers: Map<String, String>,
  ): ResolvedSource {
    throw UnsupportedOperationException(
      "FakeKetchApi does not support resolve"
    )
  }

  override suspend fun status(): KetchStatus {
    throw UnsupportedOperationException(
      "FakeKetchApi does not support status"
    )
  }

  override suspend fun updateConfig(config: DownloadConfig) {
    lastConfig = config
  }

  override suspend fun start() {}

  override fun close() {
    closed = true
    closeCallCount++
  }
}
