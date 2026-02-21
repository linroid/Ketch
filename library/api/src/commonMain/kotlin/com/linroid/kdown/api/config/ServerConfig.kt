package com.linroid.kdown.api.config

import kotlinx.serialization.Serializable

/**
 * Server-mode configuration.
 *
 * @property host bind address for the daemon server.
 * @property port listen port.
 * @property apiToken optional bearer token for authentication.
 * @property corsAllowedHosts allowed CORS origins.
 * @property mdnsEnabled whether to register via mDNS/DNS-SD.
 */
@Serializable
data class ServerConfig(
  val host: String = "0.0.0.0",
  val port: Int = 8642,
  val apiToken: String? = null,
  val corsAllowedHosts: List<String> = emptyList(),
  val mdnsEnabled: Boolean = true,
) {
  init {
    require(port in 1..65535) {
      "port must be between 1 and 65535"
    }
  }

  companion object {
    /** DNS-SD service type for KDown server discovery. */
    const val MDNS_SERVICE_TYPE = "_kdown._tcp"
  }
}
