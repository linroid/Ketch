package com.linroid.kdown.endpoints.model

import kotlinx.serialization.Serializable

/**
 * Request body for setting task priority.
 *
 * @property priority one of LOW, NORMAL, HIGH, URGENT
 */
@Serializable
data class PriorityRequest(
  val priority: String,
)
