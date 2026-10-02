package com.linroid.ketch.app

import com.linroid.ketch.api.DownloadConfig
import com.linroid.ketch.api.DownloadRequest
import com.linroid.ketch.api.DownloadTask
import com.linroid.ketch.api.KetchApi
import com.linroid.ketch.api.KetchStatus
import com.linroid.ketch.api.ResolvedSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** A [KetchApi] without tasks that resolves dropped content to [resolveContentResult]. */
class FakeKetchApi(override val backendLabel: String = "Fake") : KetchApi {
  private val _tasks = MutableStateFlow<List<DownloadTask>>(emptyList())
  override val tasks: StateFlow<List<DownloadTask>> = _tasks.asStateFlow()

  override suspend fun download(request: DownloadRequest): DownloadTask =
    throw UnsupportedOperationException("FakeKetchApi does not support downloads")

  override suspend fun resolve(url: String, properties: Map<String, String>): ResolvedSource =
    throw UnsupportedOperationException("FakeKetchApi does not support resolve")

  /** Result of [resolveContent]; unsupported when null. */
  var resolveContentResult: ResolvedSource? = null
  var lastResolvedContent: ByteArray? = null
    private set
  var lastResolvedFileName: String? = null
    private set

  override suspend fun resolveContent(
    content: ByteArray,
    fileName: String?,
  ): ResolvedSource {
    lastResolvedContent = content
    lastResolvedFileName = fileName
    return resolveContentResult ?: throw UnsupportedOperationException(
      "FakeKetchApi does not support resolveContent"
    )
  }

  override suspend fun status(): KetchStatus =
    throw UnsupportedOperationException("FakeKetchApi does not support status")

  override suspend fun updateConfig(config: DownloadConfig) {}

  override suspend fun start() {}

  override fun close() {}
}
