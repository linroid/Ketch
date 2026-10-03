package com.linroid.ketch.core.engine

/**
 * Rules for the request headers callers hand to an [HttpEngine], such as
 * [com.linroid.ketch.api.DownloadRequest.headers].
 */
object RequestHeaders {
  /**
   * Lowercase names of the headers an engine sets itself or that describe a single connection:
   * `Host`, `Range`, `Content-Length` and the hop-by-hop headers. Callers cannot set them.
   */
  val ENGINE_MANAGED: Set<String> = setOf(
    "host",
    "range",
    "content-length",
    "connection",
    "keep-alive",
    "proxy-connection",
    "te",
    "trailer",
    "transfer-encoding",
    "upgrade",
  )

  // RFC 9110 token characters besides letters and digits.
  private const val TOKEN_SYMBOLS = "!#$%&'*+-.^_`|~"

  /**
   * Checks that every header can be sent: names are RFC 9110 tokens and values hold no control
   * characters other than tab, so no value can end its line and start another header. Messages
   * name the header but never quote its value, which may be a credential.
   *
   * @throws IllegalArgumentException for the first header that cannot be sent
   */
  fun requireValid(headers: Map<String, String>) {
    for ((name, value) in headers) {
      require(name.isNotEmpty() && name.all { it.isTokenChar() }) {
        // Only the valid start: a whole header line typed as the name would quote its value.
        "Invalid header name '${name.takeWhile { it.isTokenChar() }}…': names are tokens " +
          "without spaces or separators such as ':'"
      }
      require(value.none { it.isISOControl() && it != '\t' }) {
        "Header '$name' has a line break or another control character in its value"
      }
    }
  }

  /**
   * Returns [headers] without the [ENGINE_MANAGED] ones, after checking them with
   * [requireValid].
   *
   * @throws IllegalArgumentException for the first header that cannot be sent
   */
  fun sendable(headers: Map<String, String>): Map<String, String> {
    requireValid(headers)
    return headers.filterKeys { it.lowercase() !in ENGINE_MANAGED }
  }

  private fun Char.isTokenChar(): Boolean =
    this in 'a'..'z' || this in 'A'..'Z' || this in '0'..'9' || this in TOKEN_SYMBOLS
}
