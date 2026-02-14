package com.linroid.kdown.endpoints.model

import kotlinx.serialization.Serializable

/**
 * Server status information returned by the health endpoint.
 */
@Serializable
data class ServerStatus(
  val version: String,
  val activeTasks: Int,
  val totalTasks: Int,
)
