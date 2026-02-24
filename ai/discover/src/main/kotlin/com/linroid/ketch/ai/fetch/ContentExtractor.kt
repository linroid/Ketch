package com.linroid.ketch.ai.fetch

/**
 * Extracts meaningful text content from HTML by stripping tags,
 * scripts, styles, and excess whitespace.
 *
 * This is intentionally a lightweight implementation without
 * external HTML parsing dependencies.
 */
internal class ContentExtractor {

  /**
   * Extracts text from [html], removing scripts, styles, and tags.
   *
   * @param maxLength maximum characters to return (default 50000)
   * @return extracted text content
   */
  fun extract(html: String, maxLength: Int = DEFAULT_MAX_LENGTH): String {
    var text = html
    // Remove script and style blocks
    text = SCRIPT_PATTERN.replace(text, " ")
    text = STYLE_PATTERN.replace(text, " ")
    // Remove HTML comments
    text = COMMENT_PATTERN.replace(text, " ")
    // Replace block-level tags with newlines
    text = BLOCK_TAG_PATTERN.replace(text, "\n")
    // Remove remaining tags
    text = TAG_PATTERN.replace(text, " ")
    // Decode common HTML entities
    text = decodeEntities(text)
    // Normalize whitespace
    text = MULTI_SPACE_PATTERN.replace(text, " ")
    text = MULTI_NEWLINE_PATTERN.replace(text, "\n")
    text = text.trim()

    return if (text.length > maxLength) {
      text.substring(0, maxLength)
    } else {
      text
    }
  }

  /**
   * Extracts the page title from HTML.
   */
  fun extractTitle(html: String): String {
    val match = TITLE_PATTERN.find(html) ?: return ""
    return decodeEntities(match.groupValues[1].trim())
  }

  private fun decodeEntities(text: String): String {
    return text
      .replace("&amp;", "&")
      .replace("&lt;", "<")
      .replace("&gt;", ">")
      .replace("&quot;", "\"")
      .replace("&#39;", "'")
      .replace("&apos;", "'")
      .replace("&nbsp;", " ")
  }

  companion object {
    private const val DEFAULT_MAX_LENGTH = 50_000

    private val SCRIPT_PATTERN = Regex(
      "<script[^>]*>[\\s\\S]*?</script>",
      RegexOption.IGNORE_CASE,
    )
    private val STYLE_PATTERN = Regex(
      "<style[^>]*>[\\s\\S]*?</style>",
      RegexOption.IGNORE_CASE,
    )
    private val COMMENT_PATTERN = Regex("<!--[\\s\\S]*?-->")
    private val BLOCK_TAG_PATTERN = Regex(
      "</?(?:div|p|br|hr|h[1-6]|li|tr|td|th|" +
        "blockquote|pre|section|article|header|" +
        "footer|nav|main)[^>]*>",
      RegexOption.IGNORE_CASE,
    )
    private val TAG_PATTERN = Regex("<[^>]+>")
    private val MULTI_SPACE_PATTERN = Regex("[ \\t]+")
    private val MULTI_NEWLINE_PATTERN = Regex("\\n{3,}")
    private val TITLE_PATTERN = Regex(
      "<title[^>]*>(.*?)</title>",
      RegexOption.IGNORE_CASE,
    )
  }
}
