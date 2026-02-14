package com.linroid.kdown.endpoints.model

import kotlinx.serialization.Serializable

/**
 * Request body for resolving a URL without downloading.
 *
 * @property url the URL to resolve
 * @property headers optional HTTP headers to include in the probe
 */
@Serializable
data class ResolveUrlRequest(
  val url: String,
  val headers: Map<String, String> = emptyMap(),
)
