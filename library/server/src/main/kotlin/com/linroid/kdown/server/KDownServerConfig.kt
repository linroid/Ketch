package com.linroid.kdown.server

/**
 * Configuration for the KDown daemon server.
 *
 * @property host the network interface to bind to
 * @property port the port to listen on
 * @property apiToken optional bearer token for authentication.
 *   When non-null, all requests must include an
 *   `Authorization: Bearer <token>` header.
 * @property corsAllowedHosts allowed CORS origins. Empty list
 *   disables CORS. Use `["*"]` to allow all origins.
 */
data class KDownServerConfig(
  val host: String = "0.0.0.0",
  val port: Int = 8642,
  val apiToken: String? = null,
  val corsAllowedHosts: List<String> = emptyList(),
) {
  init {
    require(port in 1..65535) {
      "port must be between 1 and 65535"
    }
  }

  companion object {
    val Default = KDownServerConfig()
  }
}
