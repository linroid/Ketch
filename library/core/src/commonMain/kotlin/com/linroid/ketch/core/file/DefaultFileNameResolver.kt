package com.linroid.ketch.core.file

import com.linroid.ketch.api.DownloadRequest
import com.linroid.ketch.api.ResolvedSource
import com.linroid.ketch.api.log.KetchLogger
import com.linroid.ketch.api.log.redactUrl

/**
 * Default strategy for resolving file names:
 * 1. Content-Disposition header (`filename*=UTF-8''...`, `filename="..."`,
 *    or `filename=...`) — extracted from `resolved.metadata["contentDisposition"]`
 * 2. Last non-empty URL path segment (percent-decoded, query/fragment stripped)
 * 3. Fallback: `"download"`
 *
 * Names from the header and the URL are passed through [sanitizeFileName]; one that leaves
 * nothing usable, such as `..`, falls through to the next.
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
    log.d { "Resolved filename: \"$name\" for url: ${redactUrl(request.url)}" }
    return name
  }

  companion object {
    internal const val FALLBACK = "download"
    internal const val META_CONTENT_DISPOSITION = "contentDisposition"

    internal fun fromContentDisposition(header: String?): String? {
      if (header.isNullOrBlank()) return null

      // Try filename*=<charset>'<language>'<encoded> (RFC 5987): UTF-8, or ISO-8859-1 as older
      // servers send it
      val extRegex = Regex(
        """filename\*\s*=\s*(UTF-8|ISO-8859-1)'[^']*'([^;]+)""",
        RegexOption.IGNORE_CASE
      )
      extRegex.find(header)?.groupValues?.let { (_, charset, encoded) ->
        val decoded = if (charset.equals("UTF-8", ignoreCase = true)) {
          percentDecode(encoded.trim())
        } else {
          percentDecodeLatin1(encoded.trim())
        }
        sanitizeFileName(decoded)?.let { return it }
      }

      // Try filename="<value>", a quoted string whose \" and \\ stand for " and \ (RFC 6266);
      // other backslashes are kept, as old browsers sent Windows paths unescaped
      val quotedRegex = Regex(
        """filename\s*=\s*"((?:[^"\\]|\\.)+)"""",
        RegexOption.IGNORE_CASE
      )
      quotedRegex.find(header)?.groupValues?.get(1)?.let { value ->
        sanitizeFileName(value.replace(Regex("""\\([\\"])"""), "$1"))?.let { return it }
      }

      // Try filename=<value> (unquoted; a quoted value the regex above rejected stays rejected)
      val unquotedRegex = Regex(
        """filename\s*=\s*([^\s;"][^\s;]*)""",
        RegexOption.IGNORE_CASE
      )
      unquotedRegex.find(header)?.groupValues?.get(1)?.let { value ->
        sanitizeFileName(value)?.let { return it }
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
      // Decoding may reveal separators (%2F, %5C), which the sanitizer strips with what precedes.
      return sanitizeFileName(percentDecode(segment))
    }

    /**
     * The name of the file a [requested] URL downloads, which redirected to [answered]: the
     * answering URL's name when it has an extension, as browsers name a download (a link such
     * as `/download?id=5` that redirects to `app-1.2.3.dmg`), else the requested URL's when it
     * has one (`asset.zip` served from an opaque storage key), else either.
     */
    internal fun fromUrls(requested: String, answered: String?): String? {
      val asked = fromUrl(requested)
      val final = answered?.let(::fromUrl)
      return when {
        final != null && final.hasExtension() -> final
        asked != null && asked.hasExtension() -> asked
        else -> asked ?: final
      }
    }

    /** Whether this name ends with letters and digits after a dot, as `.dmg` or `.safetensors`. */
    private fun String.hasExtension(): Boolean =
      substringAfterLast('.', "").let { it.isNotEmpty() && it.all(Char::isLetterOrDigit) } &&
        indexOf('.') > 0

    /** [encoded] with each `%XX` read as an ISO-8859-1 character. */
    internal fun percentDecodeLatin1(encoded: String): String = buildString {
      var index = 0
      while (index < encoded.length) {
        val high = encoded.getOrNull(index + 1)?.digitToIntOrNull(16)
        val low = encoded.getOrNull(index + 2)?.digitToIntOrNull(16)
        if (encoded[index] == '%' && high != null && low != null) {
          append((high * 16 + low).toChar())
          index += 3
        } else {
          append(encoded[index++])
        }
      }
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
