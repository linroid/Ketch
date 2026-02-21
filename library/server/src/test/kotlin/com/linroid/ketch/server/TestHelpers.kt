package com.linroid.ketch.server

import com.linroid.ketch.api.KetchApi
import com.linroid.ketch.api.KetchError
import com.linroid.ketch.api.config.DownloadConfig
import com.linroid.ketch.api.config.ServerConfig
import com.linroid.ketch.core.Ketch
import com.linroid.ketch.core.engine.HttpEngine
import com.linroid.ketch.core.engine.ServerInfo

internal class NoOpHttpEngine : HttpEngine {
  override suspend fun head(
    url: String,
    headers: Map<String, String>,
  ): ServerInfo {
    throw KetchError.Network(
      RuntimeException(
        "NoOpHttpEngine does not support requests"
      )
    )
  }

  override suspend fun download(
    url: String,
    range: LongRange?,
    headers: Map<String, String>,
    onData: suspend (ByteArray) -> Unit,
  ) {
    throw KetchError.Network(
      RuntimeException(
        "NoOpHttpEngine does not support requests"
      )
    )
  }

  override fun close() {}
}

internal fun createTestKetch(
  config: DownloadConfig = DownloadConfig.Default,
): KetchApi {
  return Ketch(httpEngine = NoOpHttpEngine(), config = config)
}

internal fun createTestServer(
  config: ServerConfig = ServerConfig(),
  ketch: KetchApi = createTestKetch(),
): KetchServer {
  return KetchServer(ketch, config)
}
