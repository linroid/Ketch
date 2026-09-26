package com.linroid.ketch.core.file

import com.linroid.ketch.api.DownloadRequest
import com.linroid.ketch.api.ResolvedSource
import com.linroid.ketch.api.log.KetchLogger

/**
 * Default strategy for resolving file names:
 * 1. Content-Disposition header (`filename*=UTF-8''...`, `filename="..."`,
 *    or `filename=...`) — extracted from `resolved.metadata["contentDisposition"]`
 * 2. Last non-empty URL path segment (percent-decoded, query/fragment stripped)
 * 3. Fallback: `"download"`
 *
 * Explicit names set via [DownloadRequest.destination] are handled by
 * the coordinator before this resolver is called.
 */
internal class DefaultFileNameResolver : FileNameResolver {

  private val log = KetchLogger("FileNameResolver")

  override fun resolve(
    request: DownloadRequest,
    resolved: ResolvedSource,
  ): String {
    val contentDisposition = resolved.metadata[META_CONTENT_DISPOSITION]
    val name = fromContentDisposition(contentDisposition)
      ?: fromUrl(request.url)
      ?: FALLBACK
    log.d { "Resolved filename: \"$name\" for url: ${request.url}" }
    return name
  }

  companion object {
    internal const val FALLBACK = "download"
    internal const val META_CONTENT_DISPOSITION = "contentDisposition"

    internal fun fromContentDisposition(header: String?): String? {
      if (header.isNullOrBlank()) return null

      // Try filename*=UTF-8''<encoded> (RFC 5987)
      val extRegex = Regex(
        """filename\*\s*=\s*UTF-8'[^']*'([^;]+)""",
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
      val input = encoded.encodeToByteArray()
      val output = ByteArray(input.size)
      var read = 0
      var written = 0
      while (read < input.size) {
        if (input[read] == '%'.code.toByte() && read + 2 < input.size) {
          val high = input[read + 1].toInt().toChar().digitToIntOrNull(16)
          val low = input[read + 2].toInt().toChar().digitToIntOrNull(16)
          if (high != null && low != null) {
            output[written++] = (high * 16 + low).toByte()
            read += 3
            continue
          }
        }
        output[written++] = input[read++]
      }
      return output.decodeToString(endIndex = written)
    }
  }
}
