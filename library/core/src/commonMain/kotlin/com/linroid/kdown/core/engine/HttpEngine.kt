package com.linroid.kdown.core.engine

/**
 * Abstraction over the HTTP layer used by KDown.
 *
 * The default implementation is backed by Ktor (`library:ktor` module).
 * Implement this interface to plug in a different HTTP client.
 */
interface HttpEngine {
  /**
   * Performs an HTTP HEAD request to retrieve server metadata.
   *
   * @param url the resource URL
   * @param headers additional request headers
   * @return server metadata including content length and range support
   * @throws com.linroid.kdown.api.KDownError.Network on connection failure
   * @throws com.linroid.kdown.api.KDownError.Http on non-success status
   */
  suspend fun head(url: String, headers: Map<String, String> = emptyMap()): ServerInfo

  /**
   * Downloads data from [url] and delivers chunks via [onData].
   *
   * @param url the resource URL
   * @param range byte range to request, or `null` for the entire resource
   * @param headers additional request headers
   * @param onData callback invoked for each chunk of received bytes
   * @throws com.linroid.kdown.api.KDownError.Network on connection failure
   * @throws com.linroid.kdown.api.KDownError.Http on non-success status
   */
  suspend fun download(
    url: String,
    range: LongRange?,
    headers: Map<String, String> = emptyMap(),
    onData: suspend (ByteArray) -> Unit,
  )

  /** Releases underlying resources (e.g., the HTTP client). */
  fun close()
}
