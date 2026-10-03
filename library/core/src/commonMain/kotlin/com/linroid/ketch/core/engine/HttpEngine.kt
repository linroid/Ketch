package com.linroid.ketch.core.engine

/**
 * Abstraction over the HTTP layer used by Ketch.
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
   * @throws com.linroid.ketch.api.KetchError.Network on connection failure
   * @throws com.linroid.ketch.api.KetchError.Http on non-success status
   */
  suspend fun head(url: String, headers: Map<String, String> = emptyMap()): ServerInfo

  /**
   * Retrieves the metadata [head] returns with a `GET` request for the first byte
   * (`Range: bytes=0-0`) instead, for servers that refuse `HEAD`, such as URLs presigned for
   * `GET` only. The body is not read. A server that ignores the range answers with the whole
   * resource: its length is the content length, and it does not count as range support.
   *
   * The default implementation throws [UnsupportedOperationException], which leaves callers
   * with the error of the refused `HEAD` request.
   *
   * @param url the resource URL
   * @param headers additional request headers
   * @return server metadata including content length and range support
   * @throws com.linroid.ketch.api.KetchError.Network on connection failure
   * @throws com.linroid.ketch.api.KetchError.Http on non-success status
   */
  suspend fun probe(url: String, headers: Map<String, String> = emptyMap()): ServerInfo =
    throw UnsupportedOperationException("This engine cannot probe with GET")

  /**
   * Downloads data from [url] and delivers chunks via [onData].
   *
   * @param url the resource URL
   * @param range byte range to request, or `null` for the entire resource
   * @param headers additional request headers
   * @param onData callback invoked for each chunk of received bytes
   * @throws com.linroid.ketch.api.KetchError.Network on connection failure
   * @throws com.linroid.ketch.api.KetchError.Http on non-success status
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
