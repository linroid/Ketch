package com.linroid.kdown.core.file

import com.linroid.kdown.api.DownloadRequest
import com.linroid.kdown.core.engine.ServerInfo

/**
 * Default strategy for resolving file names:
 * 1. Content-Disposition header (`filename*=UTF-8''...`, `filename="..."`,
 *    or `filename=...`)
 * 2. Last non-empty URL path segment (percent-decoded, query/fragment stripped)
 * 3. Fallback: `"download"`
 */
internal class DefaultFileNameResolver : FileNameResolver {

  override fun resolve(
    request: DownloadRequest,
    serverInfo: ServerInfo
  ): String {
    return request.fileName
      ?: fromContentDisposition(serverInfo.contentDisposition)
      ?: fromUrl(request.url)
      ?: FALLBACK
  }

  companion object {
    internal const val FALLBACK = "download"

    internal fun fromContentDisposition(header: String?): String? {
      if (header.isNullOrBlank()) return null

      // Try filename*=UTF-8''<encoded> (RFC 5987)
      val extRegex = Regex(
        """filename\*\s*=\s*UTF-8''(.+)""",
        RegexOption.IGNORE_CASE
      )
      extRegex.find(header)?.groupValues?.get(1)?.let { encoded ->
        val decoded = percentDecode(encoded).trim()
        if (decoded.isNotBlank()) return decoded
      }

      // Try filename="<value>"
      val quotedRegex = Regex(
        """filename\s*=\s*"([^"]+)"""",
        RegexOption.IGNORE_CASE
      )
      quotedRegex.find(header)?.groupValues?.get(1)?.let { value ->
        val trimmed = value.trim()
        if (trimmed.isNotBlank()) return trimmed
      }

      // Try filename=<value> (unquoted)
      val unquotedRegex = Regex(
        """filename\s*=\s*([^\s;]+)""",
        RegexOption.IGNORE_CASE
      )
      unquotedRegex.find(header)?.groupValues?.get(1)?.let { value ->
        val trimmed = value.trim()
        if (trimmed.isNotBlank()) return trimmed
      }

      return null
    }

    internal fun fromUrl(url: String): String? {
      // Strip scheme + authority: everything after "://" up to the first "/"
      val withoutQuery = url
        .substringBefore("?")
        .substringBefore("#")
      val pathPart = if (withoutQuery.contains("://")) {
        val afterScheme = withoutQuery.substringAfter("://")
        val slashIndex = afterScheme.indexOf('/')
        if (slashIndex < 0) "" else afterScheme.substring(slashIndex + 1)
      } else {
        withoutQuery
      }
      val segment = pathPart.trimEnd('/').substringAfterLast("/")
      if (segment.isBlank()) return null
      val decoded = percentDecode(segment).trim()
      return decoded.ifBlank { null }
    }

    internal fun percentDecode(encoded: String): String {
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
