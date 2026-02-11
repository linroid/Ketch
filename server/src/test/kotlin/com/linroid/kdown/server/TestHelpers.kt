package com.linroid.kdown.server

import com.linroid.kdown.api.KDownApi
import com.linroid.kdown.api.KDownError
import com.linroid.kdown.core.KDown
import com.linroid.kdown.core.engine.HttpEngine
import com.linroid.kdown.core.engine.ServerInfo

internal class NoOpHttpEngine : HttpEngine {
  override suspend fun head(
    url: String,
    headers: Map<String, String>
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
    onData: suspend (ByteArray) -> Unit
  ) {
    throw KDownError.Network(
      RuntimeException(
        "NoOpHttpEngine does not support requests"
      )
    )
  }

  override fun close() {}
}

internal fun createTestKDown(): KDownApi {
  return KDown(httpEngine = NoOpHttpEngine())
}

internal fun createTestServer(
  config: KDownServerConfig = KDownServerConfig.Default,
  kdown: KDownApi = createTestKDown()
): KDownServer {
  return KDownServer(kdown, config)
}
