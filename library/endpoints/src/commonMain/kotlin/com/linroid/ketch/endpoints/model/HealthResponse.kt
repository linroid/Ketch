package com.linroid.ketch.endpoints.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Body of `GET /api/health`, which answers without the access token. */
@Serializable
data class HealthResponse(val status: HealthStatus)

/** Whether a server serves its saved tasks yet. */
@Serializable
enum class HealthStatus {
  /** Listening, but still restoring the tasks saved by earlier runs; answered with 503. */
  @SerialName("starting")
  Starting,

  /** Serving every saved task; answered with 200. */
  @SerialName("ready")
  Ready,
}
