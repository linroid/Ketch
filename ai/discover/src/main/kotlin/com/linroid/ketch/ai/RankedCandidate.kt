package com.linroid.ketch.ai

import kotlinx.serialization.Serializable

/**
 * A download URL extracted and ranked by the LLM.
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
