package com.linroid.ketch.cli

import com.linroid.ketch.ai.agent.sanitizeAgentText
import java.net.URI
import java.net.URISyntaxException

/** Longest model or page text `ketch ai-discover` prints on one line, in characters. */
private const val MAX_TEXT_LENGTH = 600

/** Longest URL printed; longer ones are cut, which only text that is no URL ever needs. */
private const val MAX_URL_LENGTH = 4096

/**
 * This text, which a model, a web page or a link wrote, as one line of plain text: a page the
 * agent read could otherwise have it start a line that looks like another result or a page
 * access question, move the cursor, or reverse what is shown around it. A candidate's file name
 * is decoded from its URL, so `%0A` would be a line break and `%1B` an escape.
 */
internal fun String.printable(): String = sanitizeAgentText(this, MAX_TEXT_LENGTH)

/**
 * An agent's step as one line, `[title] details`: details may keep line breaks, such as a
 * numbered plan's, which are joined into one line here like any [printable] text.
 */
internal fun printableStep(title: String, details: String): String =
  "[${title.printable()}] ${details.printable()}"

/**
 * [url] in its ASCII form, every character that is not ASCII percent-encoded, so it can be
 * copied as it is and hides nothing: a right-to-left override in its path would otherwise show
 * the end of the URL reversed. Text that is no URL is printed as [printable] text.
 */
internal fun printableUrl(url: String): String = try {
  URI(url).toASCIIString()
} catch (_: URISyntaxException) {
  sanitizeAgentText(url, MAX_URL_LENGTH)
}
