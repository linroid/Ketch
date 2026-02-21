package com.linroid.kdown.server

import com.linroid.kdown.api.KDownApi
import com.linroid.kdown.api.KDownError
import com.linroid.kdown.api.config.DownloadConfig
import com.linroid.kdown.api.config.ServerConfig
import com.linroid.kdown.core.KDown
import com.linroid.kdown.core.engine.HttpEngine
import com.linroid.kdown.core.engine.ServerInfo

internal class NoOpHttpEngine : HttpEngine {
  override suspend fun head(
    url: String,
    headers: Map<String, String>,
  ): ServerInfo {
    throw KDownError.Network(
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
    throw KDownError.Network(
      RuntimeException(
        "NoOpHttpEngine does not support requests"
      )
    )
  }

  override fun close() {}
}

internal fun createTestKDown(
  config: DownloadConfig = DownloadConfig.Default,
): KDownApi {
  return KDown(httpEngine = NoOpHttpEngine(), config = config)
}

internal fun createTestServer(
  config: ServerConfig = ServerConfig(),
  kdown: KDownApi = createTestKDown(),
): KDownServer {
  return KDownServer(kdown, config)
}
