package com.linroid.ketch.config

import kotlinx.serialization.Serializable

/**
 * Server-mode configuration.
 *
 * @property host bind address for the daemon server.
 * @property port listen port.
 * @property apiToken optional bearer token for authentication.
 * @property corsAllowedHosts allowed CORS origins.
 * @property mdnsEnabled whether to register via mDNS/DNS-SD.
 * @property autoStart whether the apps start the server when they
 *   launch. The CLI's `server` command always starts it.
 */
@Serializable
data class ServerConfig(
  val host: String = ANY_HOST,
  val port: Int = 8642,
  val apiToken: String? = null,
  val corsAllowedHosts: List<String> = emptyList(),
  val mdnsEnabled: Boolean = true,
  val autoStart: Boolean = false,
) {
  init {
    require(port in 1..65535) {
      "port must be between 1 and 65535"
    }
  }

  /** Whether the server only accepts connections from this machine. */
  val isLoopbackOnly: Boolean
    get() = host == LOOPBACK_HOST || host == "localhost" || host == "::1"

  companion object {
    /** Bind address that accepts connections on every interface. */
    const val ANY_HOST = "0.0.0.0"

    /** Bind address that only accepts connections from this machine. */
    const val LOOPBACK_HOST = "127.0.0.1"

    /** DNS-SD service type for Ketch server discovery. */
    const val MDNS_SERVICE_TYPE = "_ketch._tcp"
  }
}
