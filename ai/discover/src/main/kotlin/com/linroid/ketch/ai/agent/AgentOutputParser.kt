package com.linroid.ketch.ai.agent

import com.linroid.ketch.ai.RankedCandidate
import com.linroid.ketch.ai.fetch.UrlValidator
import com.linroid.ketch.ai.fetch.ValidationResult
import com.linroid.ketch.api.log.KetchLogger
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Parses the agent's final text output into validated and
 * safety-filtered [RankedCandidate] instances.
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
   */
  fun parse(agentOutput: String): List<RankedCandidate> {
    val jsonStr = extractJsonArray(agentOutput)
    if (jsonStr == null) {
      log.w { "No JSON array found in agent output" }
      return emptyList()
    }

    val candidates = try {
      json.decodeFromString<List<AgentCandidate>>(jsonStr)
    } catch (e: Exception) {
      log.w(e) { "Failed to parse agent JSON output" }
      return emptyList()
    }

    return candidates
      .mapNotNull { c -> validateAndFilter(c) }
      .distinctBy { it.url }
      .sortedByDescending { it.confidence }
  }

  private fun validateAndFilter(
    c: AgentCandidate,
  ): RankedCandidate? {
    when (val v = urlValidator.validate(c.url)) {
      is ValidationResult.Blocked -> {
        log.d { "Blocked URL: ${c.url} (${v.reason})" }
        return null
      }
      is ValidationResult.Valid -> { /* ok */ }
    }

    val evaluation = safetyFilter.evaluate(
      url = c.url,
      sourcePageUrl = c.sourcePageUrl,
      extension = c.fileType,
      context = c.deviceSafetyNotes,
    )
    if (evaluation.blocked) {
      log.d { "Safety-blocked: ${c.url} (${evaluation.reason})" }
      return null
    }

    val adjustedConfidence =
      (c.confidence * evaluation.score).coerceIn(0f, 1f)

    return RankedCandidate(
      url = c.url,
      title = c.name,
      fileName = fileNameFromUrl(c.url),
      fileSize = c.sizeBytes,
      mimeType = null,
      sourceUrl = c.sourcePageUrl,
      confidence = adjustedConfidence,
      description = c.description,
    )
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

  companion object {
    private val CODE_BLOCK_PATTERN = Regex(
      "```(?:json)?\\s*\\n?([\\s\\S]*?)\\n?```",
    )

    /**
     * Extracts a JSON array from the agent output. Tries markdown
     * code blocks first, then raw `[...]`.
     */
    internal fun extractJsonArray(text: String): String? {
      // Try markdown code block
      val block = CODE_BLOCK_PATTERN.find(text)
      if (block != null) {
        val inner = block.groupValues[1].trim()
        if (inner.startsWith("[")) return inner
      }

      // Try raw JSON array
      val start = text.indexOf('[')
      val end = text.lastIndexOf(']')
      if (start >= 0 && end > start) {
        return text.substring(start, end + 1)
      }

      return null
    }
  }
}

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
