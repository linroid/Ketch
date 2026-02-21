package com.linroid.ketch.api.config

import kotlinx.serialization.Serializable

/**
 * Pre-configured remote server connection.
 *
 * @property host remote server hostname or IP.
 * @property port remote server port.
 * @property apiToken optional bearer token.
 * @property secure whether to use HTTPS (`true`) or HTTP (`false`).
 */
@Serializable
data class RemoteConfig(
  val host: String,
  val port: Int = 8642,
  val apiToken: String? = null,
  val secure: Boolean = false,
)
