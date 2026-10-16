package com.linroid.ketch.app

import com.linroid.ketch.api.DownloadConfig
import com.linroid.ketch.api.DownloadRequest
import com.linroid.ketch.api.DownloadTask
import com.linroid.ketch.api.Interest
import com.linroid.ketch.api.KetchApi
import com.linroid.ketch.api.KetchStatus
import com.linroid.ketch.api.ResolveInput
import com.linroid.ketch.api.ResolvedSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlin.time.Duration

/** A [KetchApi] without tasks that resolves content to [resolveContentResult]. */
class FakeKetchApi(override val backendLabel: String = "Fake") : KetchApi {
  override fun <T> subscribe(interest: Interest<T>, interval: Duration): Flow<T> = emptyFlow()

  private val _tasks = MutableStateFlow<List<DownloadTask>>(emptyList())
  override val tasks: StateFlow<List<DownloadTask>> = _tasks.asStateFlow()

  override suspend fun download(request: DownloadRequest): DownloadTask =
    throw UnsupportedOperationException("FakeKetchApi does not support downloads")

  /** Result of resolving content, such as a dropped file's; unsupported when null. */
  var resolveContentResult: ResolvedSource? = null
  var lastResolvedContent: ByteArray? = null
    private set
  var lastResolvedFileName: String? = null
    private set

  override suspend fun resolve(input: ResolveInput): ResolvedSource {
    if (input !is ResolveInput.Content) {
      throw UnsupportedOperationException("FakeKetchApi only resolves content")
    }
    lastResolvedContent = input.bytes
    lastResolvedFileName = input.fileName
    return resolveContentResult
      ?: throw UnsupportedOperationException("FakeKetchApi does not resolve content")
  }

  override suspend fun status(): KetchStatus =
    throw UnsupportedOperationException("FakeKetchApi does not support status")

  override suspend fun updateConfig(config: DownloadConfig) {}

  override suspend fun start() {}

  override fun close() {}
}
