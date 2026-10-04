package com.linroid.ketch.ai

import kotlinx.serialization.Serializable

/**
 * A download URL extracted and ranked by the LLM.
 *
 * [title], [description] and [fileName] are one line of plain text each, with control,
 * bidirectional and zero-width characters removed: the model wrote them, or the URL they come
 * from, and fetched pages may have shaped them, so show them as text only.
 *
 * @param fileName the last segment of [url]'s path, decoded; `null` when it has none
 */
@Serializable
data class RankedCandidate(
  val url: String,
  val title: String,
  val fileName: String?,
  val fileSize: Long?,
  val mimeType: String?,
  val sourceUrl: String,
  val confidence: Float,
  val description: String,
)
