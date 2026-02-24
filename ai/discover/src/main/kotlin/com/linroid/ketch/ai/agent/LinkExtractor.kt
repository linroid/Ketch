package com.linroid.ketch.ai.agent

import java.net.URI

/**
 * Extracts download links from HTML content by matching `<a>` tags
 * whose `href` points to common downloadable file extensions or that
 * have a `download` attribute.
 */
internal class LinkExtractor {

  /**
   * Extracts download links from [html].
   *
   * @param html raw HTML content
   * @param baseUrl base URL for resolving relative hrefs
   * @return list of extracted download links
   */
  fun extract(html: String, baseUrl: String): List<DownloadLink> {
    val results = mutableListOf<DownloadLink>()
    for (match in ANCHOR_PATTERN.findAll(html)) {
      val tag = match.groupValues[1]
      val href = extractHref(tag) ?: continue
      val hasDownloadAttr = DOWNLOAD_ATTR_PATTERN.containsMatchIn(tag)
      val lowerHref = href.lowercase()

      if (!hasDownloadAttr && !hasDownloadExtension(lowerHref)) {
        continue
      }

      val resolvedUrl = resolveUrl(baseUrl, href) ?: continue
      val anchorText = match.groupValues[2]
        .replace(TAG_PATTERN, "").trim()
      val surroundingText = extractSurroundingText(
        html, match.range, SURROUNDING_CHARS,
      )

      results.add(
        DownloadLink(
          url = resolvedUrl,
          anchorText = anchorText,
          surroundingText = surroundingText,
        )
      )
    }
    return results
  }

  private fun extractHref(tag: String): String? {
    val m = HREF_PATTERN.find(tag) ?: return null
    return m.groupValues[1].ifBlank { m.groupValues[2] }
  }

  private fun hasDownloadExtension(lowerHref: String): Boolean {
    return DOWNLOAD_EXTENSIONS.any { ext ->
      lowerHref.endsWith(ext) ||
        lowerHref.contains("$ext?") ||
        lowerHref.contains("$ext#")
    }
  }

  private fun resolveUrl(base: String, href: String): String? {
    return try {
      URI(base).resolve(href).toString()
    } catch (_: Exception) {
      null
    }
  }

  private fun extractSurroundingText(
    html: String,
    range: IntRange,
    chars: Int,
  ): String {
    val start = (range.first - chars).coerceAtLeast(0)
    val end = (range.last + chars).coerceAtMost(html.length)
    return html.substring(start, end)
      .replace(TAG_PATTERN, " ")
      .replace(MULTI_SPACE_PATTERN, " ")
      .trim()
  }

  companion object {
    private const val SURROUNDING_CHARS = 50

    private val ANCHOR_PATTERN = Regex(
      "<a\\b([^>]*)>([\\s\\S]*?)</a>",
      RegexOption.IGNORE_CASE,
    )
    private val HREF_PATTERN = Regex(
      "href\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)')",
      RegexOption.IGNORE_CASE,
    )
    private val DOWNLOAD_ATTR_PATTERN = Regex(
      "\\bdownload\\b",
      RegexOption.IGNORE_CASE,
    )
    private val TAG_PATTERN = Regex("<[^>]+>")
    private val MULTI_SPACE_PATTERN = Regex("\\s+")

    private val DOWNLOAD_EXTENSIONS = setOf(
      ".zip", ".tar.gz", ".tgz", ".tar.bz2", ".tar.xz",
      ".iso", ".pdf", ".dmg", ".pkg", ".exe", ".msi",
      ".apk", ".deb", ".rpm", ".7z", ".rar", ".xz",
      ".bz2", ".appimage", ".aab",
    )
  }
}

/**
 * A download link extracted from HTML.
 */
internal data class DownloadLink(
  val url: String,
  val anchorText: String,
  val surroundingText: String,
)
