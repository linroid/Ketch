package com.linroid.ketch.endpoints.model

import com.linroid.ketch.api.SpeedLimit
import kotlinx.serialization.Serializable

/**
 * Request body for updating speed limits.
 */
@Serializable
data class SpeedLimitRequest(
  val limit: SpeedLimit,
)
