package com.linroid.kdown.server

import com.linroid.kdown.KDown
import com.linroid.kdown.engine.HttpEngine
import com.linroid.kdown.engine.ServerInfo
import com.linroid.kdown.error.KDownError

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

internal fun createTestKDown(): KDown {
  return KDown(httpEngine = NoOpHttpEngine())
}

internal fun createTestServer(
  config: KDownServerConfig = KDownServerConfig.Default,
  kdown: KDown = createTestKDown()
): KDownServer {
  return KDownServer(kdown, config)
}
