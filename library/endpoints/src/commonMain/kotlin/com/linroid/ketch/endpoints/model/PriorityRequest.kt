package com.linroid.ketch.endpoints.model

import com.linroid.ketch.api.DownloadPriority
import kotlinx.serialization.Serializable

/**
 * Request body for setting task priority.
 *
 * @property priority one of LOW, NORMAL, HIGH, URGENT
 */
@Serializable
data class PriorityRequest(
  val priority: DownloadPriority,
)
