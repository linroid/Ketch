package com.linroid.ketch.endpoints.model

import kotlinx.serialization.Serializable

/**
 * Request body for resolving a URL without downloading.
 *
 * @property url the URL to resolve
 * @property properties source-specific key-value pairs (e.g., HTTP headers)
 */
@Serializable
data class ResolveUrlRequest(
  val url: String,
  val properties: Map<String, String> = emptyMap(),
)
