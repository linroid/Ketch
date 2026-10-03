package com.linroid.ketch.ai.agent

/**
 * [text] the model wrote, made safe to show the user as one line of plain text: every run of
 * whitespace, line breaks included, becomes one space; control, bidirectional and zero-width
 * characters are removed; and text over [maxLength] characters is cut, ending with an ellipsis.
 *
 * Fetched pages can shape what the model writes, so this keeps such text from reordering,
 * hiding or spoofing what is shown around it. Discovery applies it to the text it returns;
 * clients that print such text, or text from the pages themselves, apply it again.
 *
 * @throws IllegalArgumentException if [maxLength] is below 1
 */
fun sanitizeAgentText(text: String, maxLength: Int): String {
  require(maxLength >= 1) { "maxLength must be at least 1" }
  val line = buildString(text.length) {
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
  if (line.length <= maxLength) return line
  var end = maxLength - 1
  // Never keep half of a surrogate pair.
  if (end > 0 && line[end - 1].isHighSurrogate()) end--
  return line.substring(0, end).trimEnd() + '…'
}

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
