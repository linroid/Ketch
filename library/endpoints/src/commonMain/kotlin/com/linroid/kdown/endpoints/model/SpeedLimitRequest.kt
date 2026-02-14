package com.linroid.kdown.endpoints.model

import kotlinx.serialization.Serializable

/**
 * Request body for updating speed limits.
 *
 * @property bytesPerSecond the speed limit. 0 means unlimited.
 */
@Serializable
data class SpeedLimitRequest(
  val bytesPerSecond: Long,
)
