package com.linroid.kdown.api.config

import kotlinx.serialization.Serializable

/**
 * Server-mode configuration.
 *
 * @property host bind address for the daemon server.
 * @property port listen port.
 * @property apiToken optional bearer token for authentication.
 * @property corsAllowedHosts allowed CORS origins.
 */
@Serializable
data class ServerConfig(
  val host: String = "0.0.0.0",
  val port: Int = 8642,
  val apiToken: String? = null,
  val corsAllowedHosts: List<String> = emptyList(),
)
