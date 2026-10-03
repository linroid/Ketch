package com.linroid.ketch.endpoints.model

import kotlinx.serialization.Serializable

/**
 * Request body for setting task connection count.
 *
 * @property connections number of concurrent segments, or 0 for Auto; must not be negative
 */
@Serializable
data class ConnectionsRequest(
  val connections: Int,
)
