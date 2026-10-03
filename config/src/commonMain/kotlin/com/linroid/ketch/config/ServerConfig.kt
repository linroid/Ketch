package com.linroid.ketch.config

import kotlinx.serialization.Serializable

/**
 * Server-mode configuration.
 *
 * @property host bind address for the daemon server.
 * @property port listen port.
 * @property apiToken bearer token every API request must carry. Without one, the
 *   CLI's `ketch server` creates and keeps a token when it listens beyond this
 *   machine, unless it is started with `--no-token`.
 * @property corsAllowedHosts origins whose web pages may call the API, such
 *   as `"http://localhost:3000"`, or `"*"` for any. Only used with an
 *   [apiToken]: without one, the server refuses pages on other origins. With
 *   a token and no list, the apps and `ketch server` allow any origin so the
 *   web app can connect.
 * @property allowedHosts extra `Host` header names or IP addresses the server
 *   accepts when there is no [apiToken]. Loopback names, this machine's IP
 *   addresses, host name and `<host>.local` are always accepted; any other
 *   name, such as a DNS alias, must be listed here or the server answers 403.
 * @property allowedDirectories folders, besides the download directory, that
 *   API callers may save downloads to and delete files from. Without an
 *   [apiToken], callers are always kept to the download directory and these
 *   folders; with one, only when this list is not empty. A leading `~` in a
 *   config file is the home folder.
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
  val allowedHosts: List<String> = emptyList(),
  val allowedDirectories: List<String> = emptyList(),
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
