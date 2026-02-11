package com.linroid.kdown.api.model

import kotlinx.serialization.Serializable

/**
 * Generic error response body.
 */
@Serializable
data class ErrorResponse(
  val error: String,
  val message: String
)
