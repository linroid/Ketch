package com.linroid.ketch.ai.agent

import com.linroid.ketch.ai.RankedCandidate
import com.linroid.ketch.ai.fetch.UrlValidator
import com.linroid.ketch.ai.fetch.ValidationResult
import com.linroid.ketch.api.log.KetchLogger
import com.linroid.ketch.api.log.redactUrl
import com.linroid.ketch.config.SiteNames
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Parses the agent's final text output into its summary, its title for the search and
 * validated, safety-filtered [RankedCandidate] instances.
 */
internal class AgentOutputParser(
  private val urlValidator: UrlValidator,
  private val safetyFilter: DeviceSafetyFilter,
  private val json: Json,
) {

  private val log = KetchLogger("AgentOutputParser")

  /**
   * Parses the agent output text, validates URLs, applies the
   * device safety filter, and deduplicates by URL.
   *
   * The agent answers with `{"title": ..., "summary": ..., "candidates": [...]}`, where the
   * title is optional and an answer without candidates may leave them out; a bare candidate
   * array is read too, without a title or summary. A candidate that does not decode is skipped
   * on its own. Output that holds no such JSON is taken as a plain-text answer and becomes the
   * summary, unless it starts as JSON, such as an answer cut off half-way: that gives neither a
   * summary nor candidates.
   *
   * Candidates whose URL lies outside [allowlist] are dropped; the page
   * they were found on is not checked. So are [excludedUrls], compared by
   * [SiteNames.canonicalUrl].
   *
   * Every candidate URL is validated, its host looked up and dropped when
   * it has a private or local address, so a page cannot have the agent
   * offer a link into the user's network. The lookup reaches only DNS,
   * never the site.
   *
   * With [contentFilter], the [DeviceSafetyFilter] then drops candidates it
   * blocks, counted in [ParsedOutput.filtered] once per link none of whose
   * copies pass, and scales the confidence of the rest by its score.
   * Without it, candidates keep the agent's confidence.
   *
   * The answer's title, and a candidate's title, description and file
   * name, are reduced to one line of plain text: the model wrote them, and
   * fetched pages may have shaped them.
   */
  suspend fun parse(
    agentOutput: String,
    allowlist: SiteAllowlist = SiteAllowlist.Unrestricted,
    excludedUrls: Set<String> = emptySet(),
    contentFilter: Boolean = true,
  ): ParsedOutput {
    val decoded = decode(agentOutput) ?: return textAnswer(agentOutput)
    val excluded = excludedUrls.mapTo(HashSet(), SiteNames::canonicalUrl)
    // Every copy of a link is checked, so one the filter passes wins whatever comes first.
    val checked = decoded.candidates
      .filterNot { SiteNames.canonicalUrl(it.url) in excluded }
      .map { c -> SiteNames.canonicalUrl(c.url) to check(c, allowlist, contentFilter) }
    val candidates = checked
      .mapNotNull { (_, result) -> (result as? Checked.Kept)?.candidate }
      .distinctBy { SiteNames.canonicalUrl(it.url) }
      .sortedByDescending { it.confidence }
    val shown = candidates.mapTo(HashSet()) { SiteNames.canonicalUrl(it.url) }
    val filtered = checked
      .filter { (url, result) -> result == Checked.Filtered && url !in shown }
      .distinctBy { (url, _) -> url }
      .size
    return ParsedOutput(
      summary = sanitizeAgentText(decoded.summary, MAX_SUMMARY_LENGTH),
      candidates = candidates,
      title = searchTitle(decoded.title),
      filtered = filtered,
    )
  }

  /**
   * The agent's [title] for the search as one line of plain text of at most
   * [MAX_SEARCH_TITLE_LENGTH] characters, without the quotes around it or a period at its end.
   */
  private fun searchTitle(title: String): String {
    val line = sanitizeAgentText(title, title.length.coerceAtLeast(1))
    val unquoted = if (
      line.length >= 2 && line.first() in OPENING_QUOTES && line.last() in CLOSING_QUOTES
    ) {
      line.substring(1, line.length - 1).trim()
    } else {
      line
    }
    val bare = if (unquoted.endsWith("...")) unquoted else unquoted.removeSuffix(".")
    return sanitizeAgentText(bare.removeSuffix("。").trimEnd(), MAX_SEARCH_TITLE_LENGTH)
  }

  /**
   * Reads the answer in [output], from a code fence when one holds JSON, or else from the
   * output itself: the first balanced JSON value that reads as an answer. Brackets in prose
   * before or after it, such as `[3]` or a Markdown link, are skipped, and brackets inside the
   * answer's strings are only text. An empty answer, such as a bare `[]`, which prose can hold
   * too, counts only when no later value says more.
   *
   * @return the summary and the candidates that decode, or `null` when there is no such JSON
   */
  private fun decode(output: String): AgentAnswer? {
    val text = CODE_BLOCK_PATTERN.find(output)?.groupValues?.get(1)?.trim()
      ?.takeIf { it.startsWith('[') || it.startsWith('{') }
      ?: output
    var empty: AgentAnswer? = null
    var from = 0
    while (true) {
      val start = text.indexOfAny(OPENING_BRACKETS, from).takeIf { it >= 0 } ?: return empty
      val answer = matchingClose(text, start)?.let { decodeAnswer(text.substring(start, it + 1)) }
      when {
        answer == null -> Unit
        answer.summary.isEmpty() && answer.candidates.isEmpty() -> empty = empty ?: answer
        else -> return answer
      }
      from = start + 1
    }
  }

  /**
   * The index of the bracket that closes the one at [start], or `null` when it is never closed.
   * Brackets inside JSON strings do not count.
   */
  private fun matchingClose(text: String, start: Int): Int? {
    var depth = 0
    var inString = false
    var escaped = false
    for (i in start until text.length) {
      val c = text[i]
      if (inString) {
        when {
          escaped -> escaped = false
          c == '\\' -> escaped = true
          c == '"' -> inString = false
        }
        continue
      }
      when (c) {
        '"' -> inString = true
        '[', '{' -> depth++
        ']', '}' -> if (--depth == 0) return i
      }
    }
    return null
  }

  /** The answer [slice] holds, or `null` when it is not JSON or not shaped as an answer. */
  private fun decodeAnswer(slice: String): AgentAnswer? {
    val element = try {
      json.parseToJsonElement(slice)
    } catch (e: SerializationException) {
      log.d { "Not a JSON answer: ${e.message?.lineSequence()?.firstOrNull()}" }
      return null
    }
    return when (element) {
      // Prose such as "[3]" reads as an array too; only an array of objects lists candidates.
      is JsonArray -> if (element.all { it is JsonObject }) {
        AgentAnswer(title = "", summary = "", candidates = decodeCandidates(element))
      } else {
        null
      }
      is JsonObject -> {
        val summary = (element["summary"] as? JsonPrimitive)?.contentOrNull
        val candidates = element["candidates"] as? JsonArray
        if (summary == null && candidates == null) return null
        // Only a string names the search; a title is no answer on its own.
        val title = (element["title"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        AgentAnswer(
          title = title.orEmpty(),
          summary = summary.orEmpty(),
          candidates = candidates?.let(::decodeCandidates).orEmpty(),
        )
      }
      else -> null
    }
  }

  /**
   * The answer in [output], which holds no JSON answer: its text becomes the summary, as when
   * the agent explains in prose why it cannot help. Output that starts as JSON is not shown.
   */
  private fun textAnswer(output: String): ParsedOutput {
    val opening = output.trim().removePrefix("```json").removePrefix("```").trimStart()
    if (opening.startsWith('{') || opening.startsWith('[')) {
      log.w { "Agent answered with JSON that cannot be read; returning no results" }
      return ParsedOutput(summary = "", candidates = emptyList())
    }
    log.w { "No JSON answer in agent output; taking it as text" }
    return ParsedOutput(sanitizeAgentText(output, MAX_SUMMARY_LENGTH), emptyList())
  }

  private fun decodeCandidates(array: JsonArray): List<AgentCandidate> =
    array.mapNotNull(::decodeCandidate)

  private fun decodeCandidate(element: JsonElement): AgentCandidate? = try {
    json.decodeFromJsonElement(AgentCandidate.serializer(), element)
  } catch (e: IllegalArgumentException) {
    // Covers SerializationException and NumberFormatException, both IllegalArgumentExceptions.
    log.d { "Skipping a malformed candidate: ${e.message?.lineSequence()?.firstOrNull()}" }
    null
  }

  /**
   * Checks [c] against [allowlist] and the URL validator, then, with [contentFilter], the
   * device safety filter.
   */
  private suspend fun check(
    c: AgentCandidate,
    allowlist: SiteAllowlist,
    contentFilter: Boolean,
  ): Checked {
    if (!allowlist.allows(c.url)) {
      log.d { "Outside allowed sites: ${redactUrl(c.url)}" }
      return Checked.Dropped
    }
    val validation = urlValidator.validate(c.url)
    if (validation is ValidationResult.Blocked) {
      log.d { "Blocked URL: ${redactUrl(c.url)} (${validation.reason})" }
      return Checked.Dropped
    }

    val score = if (contentFilter) {
      val evaluation = safetyFilter.evaluate(
        url = c.url,
        sourcePageUrl = c.sourcePageUrl,
        extension = c.fileType,
        context = c.deviceSafetyNotes,
      )
      if (evaluation.blocked) {
        log.d { "Safety-blocked: ${redactUrl(c.url)} (${evaluation.reason})" }
        return Checked.Filtered
      }
      evaluation.score
    } else {
      1f
    }

    val adjustedConfidence = (c.confidence * score).coerceIn(0f, 1f)

    val candidate = RankedCandidate(
      url = c.url,
      title = sanitizeAgentText(c.name, MAX_TITLE_LENGTH),
      fileName = fileNameFromUrl(c.url)
        ?.let { sanitizeAgentText(it, MAX_FILE_NAME_LENGTH) }
        ?.ifBlank { null },
      fileSize = c.sizeBytes,
      mimeType = null,
      sourceUrl = c.sourcePageUrl,
      confidence = adjustedConfidence,
      description = sanitizeAgentText(c.description, MAX_DESCRIPTION_LENGTH),
    )
    return Checked.Kept(candidate)
  }

  /** What [check] made of a candidate. */
  private sealed interface Checked {
    /** It passed every check. */
    class Kept(val candidate: RankedCandidate) : Checked

    /** The content filter hid it. */
    data object Filtered : Checked

    /** It is not allowed whatever the content filter says, such as a private address. */
    data object Dropped : Checked
  }

  private fun fileNameFromUrl(url: String): String? {
    return try {
      val path = java.net.URI(url).path ?: return null
      val last = path.substringAfterLast('/')
      last.ifBlank { null }
    } catch (_: Exception) {
      null
    }
  }

  /** The agent's answer before its candidates are checked and its text is made safe. */
  private class AgentAnswer(
    val title: String,
    val summary: String,
    val candidates: List<AgentCandidate>,
  )

  companion object {
    /** Longest summary kept, in characters. */
    internal const val MAX_SUMMARY_LENGTH = 600

    /** Longest title for the search kept, in characters. */
    internal const val MAX_SEARCH_TITLE_LENGTH = 60
    private const val MAX_TITLE_LENGTH = 200
    private const val MAX_DESCRIPTION_LENGTH = 600
    private const val MAX_FILE_NAME_LENGTH = 255

    private val OPENING_BRACKETS = charArrayOf('[', '{')

    // Quotes the model may wrap a title in, despite being told not to.
    private const val OPENING_QUOTES = "\"'`\u201C\u2018\u00AB\u300C"
    private const val CLOSING_QUOTES = "\"'`\u201D\u2019\u00BB\u300D"

    private val CODE_BLOCK_PATTERN = Regex(
      "```(?:json)?\\s*\\n?([\\s\\S]*?)\\n?```",
    )
  }
}

/**
 * What the agent answered.
 *
 * @param summary its short reply to the user, sanitized to one line of plain text; blank when
 *   it gave none
 * @param candidates its candidates that passed every check, most confident first
 * @param title its short name for the search, sanitized to one line of plain text; blank when
 *   it gave none, as a follow-up may, or answered with a bare array or text alone
 * @param filtered how many of its candidates the content filter hid
 */
internal data class ParsedOutput(
  val summary: String,
  val candidates: List<RankedCandidate>,
  val title: String = "",
  val filtered: Int = 0,
)

/**
 * DTO matching the agent's JSON output schema.
 */
@Serializable
internal data class AgentCandidate(
  val name: String,
  val url: String,
  val fileType: String = "",
  val sourcePageUrl: String = "",
  val sizeBytes: Long? = null,
  val lastModified: String? = null,
  val description: String = "",
  val confidence: Float = 0.5f,
  val deviceSafetyNotes: String = "",
)
