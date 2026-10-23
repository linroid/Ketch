package com.linroid.ketch.core.engine

import com.linroid.ketch.api.KetchError
import com.linroid.ketch.api.Proxy

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
   * @throws com.linroid.ketch.api.KetchError.Unsupported caused by a [RangeIgnoredException]
   *   when the server answers [range] with the whole resource
   */
  suspend fun download(
    url: String,
    range: LongRange?,
    headers: Map<String, String> = emptyMap(),
    onData: suspend (ByteArray) -> Unit,
  )

  /**
   * Streams a complete resource and returns its final URL for resolving relative references.
   * Engines following redirects must override this method with the effective response URL.
   * The default is suitable for engines which do not redirect.
   */
  suspend fun downloadResource(
    url: String,
    headers: Map<String, String>,
    onData: suspend (ByteArray) -> Unit,
  ): String {
    download(url, null, headers, onData)
    return url
  }

  /**
   * Sends a request that is not a download, such as WebDAV's `PROPFIND`, following redirects as
   * the other requests do, and returns the final response. Unlike [head] and [download], a status
   * that is not a success is returned rather than thrown, so callers can read its headers, such
   * as an authentication challenge; only a success's body is read, and never a `HEAD`'s.
   *
   * The default implementation throws [UnsupportedOperationException]. Engines that wrap another
   * must override it to pass it on.
   *
   * @param method the request method, such as `PROPFIND`
   * @param url the resource URL
   * @param headers additional request headers; [body]'s content type is set from [HttpBody]
   * @param body the request body, or `null` for none
   * @param maxBodyBytes the longest success body read; a longer one fails the request with
   *   [KetchError.Unsupported]
   * @throws com.linroid.ketch.api.KetchError.Network on connection failure
   */
  suspend fun request(
    method: String,
    url: String,
    headers: Map<String, String> = emptyMap(),
    body: HttpBody? = null,
    maxBodyBytes: Int = HttpReply.DEFAULT_MAX_BODY_BYTES,
  ): HttpReply = throw UnsupportedOperationException("This engine cannot send $method requests")

  /**
   * Whether [withProxy] accepts proxies other than [Proxy.System]. Ketch lists
   * [com.linroid.ketch.api.KetchFeatures.PROXY], and accepts such proxies, only when it does.
   */
  val supportsProxies: Boolean get() = false

  /**
   * Returns an engine whose requests reach their servers as [proxy] says, sharing this engine's
   * resources. It stays usable until this engine is closed; closing it does nothing.
   *
   * An engine follows the system's proxy settings ([Proxy.System]) by itself, so Ketch only
   * asks for other proxies. Engines that wrap another must override this method to keep wrapping
   * the engine it returns. Engines that override it must also report [supportsProxies]. The
   * default returns this engine for [Proxy.System] and refuses any other proxy with
   * [KetchError.Unsupported], so a download never silently skips the proxy it was given.
   */
  fun withProxy(proxy: Proxy): HttpEngine {
    if (proxy == Proxy.System) return this
    throw KetchError.Unsupported(
      cause = UnsupportedOperationException("This HTTP engine cannot choose a proxy"),
    )
  }

  /** Releases underlying resources (e.g., the HTTP client). */
  fun close()
}

/** This engine for [Proxy.System], which it follows by itself, else [HttpEngine.withProxy]. */
internal fun HttpEngine.through(proxy: Proxy): HttpEngine =
  if (proxy == Proxy.System) this else withProxy(proxy)

/**
 * The body of a request [HttpEngine.request] sends.
 *
 * @property bytes the body
 * @property contentType its media type, such as `application/xml; charset=utf-8`
 */
class HttpBody(val bytes: ByteArray, val contentType: String)

/**
 * The final response to an [HttpEngine.request].
 *
 * @property status the status code
 * @property headers the response headers by lowercase name, each with its values in order
 * @property body the body of a success, empty for other statuses
 * @property url the URL that answered, after the redirects the engine followed, or `null` when
 *   the engine does not say
 */
class HttpReply(
  val status: Int,
  val headers: Map<String, List<String>>,
  val body: ByteArray,
  val url: String? = null,
) {
  /** Whether [status] is a success (2xx). */
  val isSuccess: Boolean get() = status in 200..299

  /** The first value of the header named [name], whatever its case, or `null`. */
  fun header(name: String): String? = headers[name.lowercase()]?.firstOrNull()

  /** Every value of the header named [name], whatever its case. */
  fun headers(name: String): List<String> = headers[name.lowercase()].orEmpty()

  companion object {
    /** The longest body [HttpEngine.request] reads unless told otherwise: 1 MiB. */
    const val DEFAULT_MAX_BODY_BYTES: Int = 1 shl 20
  }
}

/**
 * Why an [HttpEngine.download] of a range failed with [KetchError.Unsupported]: the server sent
 * the whole resource (`200`) instead, as servers and caches that ignore `Range` do, whatever
 * their `Accept-Ranges` said. Ketch then downloads the file over one connection from byte zero.
 */
class RangeIgnoredException : IllegalStateException("Server ignored the range")
