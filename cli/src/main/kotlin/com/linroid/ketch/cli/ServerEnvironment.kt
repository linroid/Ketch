package com.linroid.ketch.cli

import com.linroid.ketch.api.SpeedLimit
import com.linroid.ketch.config.KetchConfig

/**
 * Environment variables `ketch server` reads, as containers such as the Docker image configure
 * it. They take precedence over the config file, and its flags over them. The access token is
 * [TOKEN_ENV], and the config directory `KETCH_CONFIG_DIR`, which the config module reads.
 */
internal object ServerEnv {
  /** Instance name shown to clients and announced over mDNS (`name`). */
  const val NAME = "KETCH_NAME"

  /** Bind address (`--host`, `[server] host`). */
  const val HOST = "KETCH_HOST"

  /** Listen port (`--port`, `[server] port`). */
  const val PORT = "KETCH_PORT"

  /** Comma-separated CORS origins (`--cors`, `[server] corsAllowedHosts`). */
  const val CORS = "KETCH_CORS"

  /** Comma-separated extra `Host` names (`--allowed-hosts`, `[server] allowedHosts`). */
  const val ALLOWED_HOSTS = "KETCH_ALLOWED_HOSTS"

  /** Comma-separated save folders (`--allowed-dirs`, `[server] allowedDirectories`). */
  const val ALLOWED_DIRS = "KETCH_ALLOWED_DIRS"

  /** Whether to announce the server over mDNS (`[server] mdnsEnabled`). */
  const val MDNS = "KETCH_MDNS"

  /** Download directory (`--dir`, `[download] defaultDirectory`). */
  const val DOWNLOAD_DIR = "KETCH_DOWNLOAD_DIR"

  /** Global speed limit (`--speed-limit`, `[download] speedLimit`). */
  const val SPEED_LIMIT = "KETCH_SPEED_LIMIT"

  /** Simultaneous downloads, 0 for no limit (`[download] maxConcurrentDownloads`). */
  const val MAX_CONCURRENT_DOWNLOADS = "KETCH_MAX_CONCURRENT_DOWNLOADS"

  /** Connections per download (`[download] maxConnectionsPerDownload`). */
  const val MAX_CONNECTIONS_PER_DOWNLOAD = "KETCH_MAX_CONNECTIONS_PER_DOWNLOAD"

  /** Downloads per host, 0 for no limit (`[download] maxConnectionsPerHost`). */
  const val MAX_CONNECTIONS_PER_HOST = "KETCH_MAX_CONNECTIONS_PER_HOST"

  /** BitTorrent listen port, 0 for any (`--torrent-port`, `[torrent] listenPort`). */
  const val TORRENT_PORT = "KETCH_TORRENT_PORT"

  /** Every variable [applyServerEnvironment] reads. */
  val names: List<String> = listOf(
    NAME, HOST, PORT, CORS, ALLOWED_HOSTS, ALLOWED_DIRS, MDNS, DOWNLOAD_DIR, SPEED_LIMIT,
    MAX_CONCURRENT_DOWNLOADS, MAX_CONNECTIONS_PER_DOWNLOAD, MAX_CONNECTIONS_PER_HOST,
    TORRENT_PORT,
  )
}

/**
 * Returns [config] with the [ServerEnv] variables set in [environment]. Blank values count as
 * unset, so a container template can leave a variable empty.
 *
 * @throws IllegalArgumentException naming the variable whose value cannot be used
 */
internal fun applyServerEnvironment(
  config: KetchConfig,
  environment: Map<String, String>,
): KetchConfig {
  fun value(name: String): String? = environment[name]?.trim()?.takeIf { it.isNotEmpty() }

  fun list(name: String): List<String>? =
    value(name)?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }

  fun int(name: String, range: IntRange, description: String): Int? = value(name)?.let { text ->
    text.toIntOrNull()?.takeIf { it in range }
      ?: throw IllegalArgumentException("$name: '$text' is not $description")
  }

  fun boolean(name: String): Boolean? = value(name)?.let { text ->
    when (text.lowercase()) {
      "true", "yes", "on", "1" -> true
      "false", "no", "off", "0" -> false
      else -> throw IllegalArgumentException("$name: '$text' is not true or false")
    }
  }

  val server = config.server
  val download = config.download
  return config.copy(
    name = value(ServerEnv.NAME) ?: config.name,
    server = server.copy(
      host = value(ServerEnv.HOST) ?: server.host,
      port = int(ServerEnv.PORT, 1..65535, "a port (1-65535)") ?: server.port,
      corsAllowedHosts = list(ServerEnv.CORS) ?: server.corsAllowedHosts,
      allowedHosts = list(ServerEnv.ALLOWED_HOSTS) ?: server.allowedHosts,
      allowedDirectories = list(ServerEnv.ALLOWED_DIRS) ?: server.allowedDirectories,
      mdnsEnabled = boolean(ServerEnv.MDNS) ?: server.mdnsEnabled,
    ),
    download = download.copy(
      defaultDirectory = value(ServerEnv.DOWNLOAD_DIR) ?: download.defaultDirectory,
      speedLimit = value(ServerEnv.SPEED_LIMIT)?.let { text ->
        SpeedLimit.parse(text) ?: throw IllegalArgumentException(
          "${ServerEnv.SPEED_LIMIT}: '$text' is not a speed limit, such as 10m or 500k"
        )
      } ?: download.speedLimit,
      maxConcurrentDownloads = int(ServerEnv.MAX_CONCURRENT_DOWNLOADS, 0..Int.MAX_VALUE, NO_LIMIT)
        ?: download.maxConcurrentDownloads,
      maxConnectionsPerDownload =
        int(ServerEnv.MAX_CONNECTIONS_PER_DOWNLOAD, 1..Int.MAX_VALUE, "a count of at least 1")
          ?: download.maxConnectionsPerDownload,
      maxConnectionsPerHost = int(ServerEnv.MAX_CONNECTIONS_PER_HOST, 0..Int.MAX_VALUE, NO_LIMIT)
        ?: download.maxConnectionsPerHost,
    ),
    torrent = config.torrent.copy(
      listenPort = int(ServerEnv.TORRENT_PORT, 0..65535, "a port (0-65535)")
        ?: config.torrent.listenPort,
    ),
  )
}

private const val NO_LIMIT = "a count (0 for no limit)"

/** The [ServerEnv] variables set in [environment], for the server to list where it starts. */
internal fun serverEnvironmentNames(environment: Map<String, String>): List<String> =
  ServerEnv.names.filter { !environment[it].isNullOrBlank() }
