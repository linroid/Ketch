package com.linroid.ketch.config

import kotlinx.serialization.Serializable

/**
 * Pre-configured remote server connection.
 *
 * @property host remote server hostname or IP.
 * @property port remote server port.
 * @property apiToken optional bearer token.
 * @property secure whether to use HTTPS (`true`) or HTTP (`false`).
 * @property name name the apps show for the device, such as "NAS-Basement"; `null` until the
 *   user names it or the apps learn the name it announces, and `host:port` is shown meanwhile.
 * @property watch whether the apps stay connected to the device while another one is shown, so
 *   switching to it is instant and its speed and failures stay current.
 */
@Serializable
data class RemoteConfig(
  val host: String,
  val port: Int = 8642,
  val apiToken: String? = null,
  val secure: Boolean = false,
  val name: String? = null,
  val watch: Boolean = true,
)
