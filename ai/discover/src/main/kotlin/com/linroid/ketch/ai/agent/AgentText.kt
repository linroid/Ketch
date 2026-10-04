package com.linroid.ketch.ai.agent

/**
 * [text] the model wrote, made safe to show the user as plain text: every run of whitespace,
 * line breaks included, becomes one space; control, bidirectional and zero-width characters
 * are removed; and text over [maxLength] characters is cut, ending with an ellipsis.
 *
 * With [keepLineBreaks], the text keeps its lines instead of becoming one: each line, split at
 * `\r\n`, `\n`, `\r`, U+2028 and U+2029, is cleaned as above, blank lines are dropped and the
 * first 12 are joined with `\n`, the last of them ending with an ellipsis when more followed.
 * The whole is then cut to [maxLength] as above.
 *
 * Fetched pages can shape what the model writes, so this keeps such text from reordering,
 * hiding or spoofing what is shown around it. Discovery applies it to the text it returns;
 * clients that print such text, or text from the pages themselves, apply it again.
 *
 * @throws IllegalArgumentException if [maxLength] is below 1
 */
fun sanitizeAgentText(text: String, maxLength: Int, keepLineBreaks: Boolean = false): String {
  require(maxLength >= 1) { "maxLength must be at least 1" }
  val clean = if (keepLineBreaks) {
    val lines = text.split(LineBreak).map(::oneLine).filter { it.isNotEmpty() }
    val kept = lines.take(MAX_LINES)
    val joined = kept.joinToString("\n")
    if (lines.size > kept.size) "$joined…" else joined
  } else {
    oneLine(text)
  }
  if (clean.length <= maxLength) return clean
  var end = maxLength - 1
  // Never keep half of a surrogate pair.
  if (end > 0 && clean[end - 1].isHighSurrogate()) end--
  return clean.substring(0, end).trimEnd() + '…'
}

/** Most lines [sanitizeAgentText] keeps when it keeps line breaks. */
private const val MAX_LINES = 12

/** [text] as one line: whitespace runs become one space and hidden characters go. */
private fun oneLine(text: String): String = buildString(text.length) {
  var space = false
  for (c in text) {
    when {
      c.isWhitespace() -> space = isNotEmpty()
      c.isHidden() -> {}
      else -> {
        if (space) append(' ')
        space = false
        append(c)
      }
    }
  }
}

/** Where a line ends: CR LF, LF, CR, or the Unicode line and paragraph separators. */
private val LineBreak = Regex("\r\n|[\n\r\u2028\u2029]")

/**
 * Whether this is a C0 or C1 control, a bidirectional control or mark, or a zero-width
 * character. Escaped here, since such characters are invisible in source code too.
 */
private fun Char.isHidden(): Boolean =
  isISOControl() ||
    this in '\u202A'..'\u202E' ||
    this in '\u2066'..'\u2069' ||
    this == '\u200E' ||
    this == '\u200F' ||
    this == '\u061C' ||
    this in '\u200B'..'\u200D' ||
    this == '\u2060' ||
    this == '\uFEFF'
