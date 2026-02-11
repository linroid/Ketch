package com.linroid.kdown.core.engine


interface HttpEngine {
  suspend fun head(url: String, headers: Map<String, String> = emptyMap()): ServerInfo

  suspend fun download(
    url: String,
    range: LongRange?,
    headers: Map<String, String> = emptyMap(),
    onData: suspend (ByteArray) -> Unit
  )

  fun close()
}
