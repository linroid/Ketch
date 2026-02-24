package com.linroid.ketch.ftp

/**
 * Parsed FTP URL components.
 *
 * Supports the format: `ftp://[user:pass@]host[:port]/path`
 * and `ftps://[user:pass@]host[:port]/path`.
 *
 * @property isTls true for ftps:// URLs
 * @property username FTP username, defaults to "anonymous"
 * @property password FTP password, defaults to "" for anonymous
 * @property host server hostname or IP address
 * @property port server port, defaults to 21 (FTP) or 990 (FTPS)
 * @property path remote file path (without leading slash)
 */
internal data class FtpUrl(
  val isTls: Boolean,
  val username: String,
  val password: String,
  val host: String,
  val port: Int,
  val path: String,
) {
  companion object {
    private const val DEFAULT_FTP_PORT = 21
    private const val DEFAULT_FTPS_PORT = 990

    /**
     * Parses an FTP/FTPS URL string.
     *
     * @throws IllegalArgumentException if the URL is not a valid
     *   ftp:// or ftps:// URL
     */
    fun parse(url: String): FtpUrl {
      val lower = url.lowercase()
      val isTls = when {
        lower.startsWith("ftps://") -> true
        lower.startsWith("ftp://") -> false
        else -> throw IllegalArgumentException(
          "Not an FTP URL: $url"
        )
      }

      val schemeEnd = url.indexOf("://") + 3
      val afterScheme = url.substring(schemeEnd)

      // Split authority from path at first '/'
      val slashIndex = afterScheme.indexOf('/')
      val authority: String
      val path: String
      if (slashIndex < 0) {
        authority = afterScheme
        path = ""
      } else {
        authority = afterScheme.substring(0, slashIndex)
        path = afterScheme.substring(slashIndex + 1)
      }

      // Split credentials from host:port at last '@'
      val atIndex = authority.lastIndexOf('@')
      val username: String
      val password: String
      val hostPort: String
      if (atIndex >= 0) {
        val credentials = authority.substring(0, atIndex)
        hostPort = authority.substring(atIndex + 1)
        val colonIndex = credentials.indexOf(':')
        if (colonIndex >= 0) {
          username = percentDecode(credentials.substring(0, colonIndex))
          password = percentDecode(credentials.substring(colonIndex + 1))
        } else {
          username = percentDecode(credentials)
          password = ""
        }
      } else {
        hostPort = authority
        username = "anonymous"
        password = ""
      }

      // Parse host and port
      val host: String
      val port: Int
      if (hostPort.startsWith('[')) {
        // IPv6: [host]:port
        val closeBracket = hostPort.indexOf(']')
        require(closeBracket > 0) {
          "Invalid IPv6 address in URL: $url"
        }
        host = hostPort.substring(1, closeBracket)
        val afterBracket = hostPort.substring(closeBracket + 1)
        port = if (afterBracket.startsWith(':')) {
          afterBracket.substring(1).toIntOrNull()
            ?: throw IllegalArgumentException(
              "Invalid port in URL: $url"
            )
        } else {
          if (isTls) DEFAULT_FTPS_PORT else DEFAULT_FTP_PORT
        }
      } else {
        val colonIndex = hostPort.lastIndexOf(':')
        if (colonIndex >= 0) {
          host = hostPort.substring(0, colonIndex)
          port = hostPort.substring(colonIndex + 1).toIntOrNull()
            ?: throw IllegalArgumentException(
              "Invalid port in URL: $url"
            )
        } else {
          host = hostPort
          port = if (isTls) DEFAULT_FTPS_PORT else DEFAULT_FTP_PORT
        }
      }

      require(host.isNotEmpty()) {
        "Missing host in URL: $url"
      }

      return FtpUrl(
        isTls = isTls,
        username = username,
        password = password,
        host = host,
        port = port,
        path = percentDecode(path),
      )
    }

    private fun percentDecode(encoded: String): String {
      val sb = StringBuilder()
      var i = 0
      while (i < encoded.length) {
        if (encoded[i] == '%' && i + 2 < encoded.length) {
          val hex = encoded.substring(i + 1, i + 3)
          val code = hex.toIntOrNull(16)
          if (code != null) {
            sb.append(code.toChar())
            i += 3
            continue
          }
        }
        sb.append(encoded[i])
        i++
      }
      return sb.toString()
    }
  }
}
