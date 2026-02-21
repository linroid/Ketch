package com.linroid.kdown.endpoints.model

import kotlinx.serialization.Serializable

/**
 * Request body for setting task connection count.
 *
 * @property connections number of concurrent segments, must be > 0
 */
@Serializable
data class ConnectionsRequest(
  val connections: Int,
)
