package com.linroid.kdown.cli

import com.akuleshov7.ktoml.Toml
import com.linroid.kdown.api.SpeedLimit
import com.linroid.kdown.api.config.DownloadConfig
import com.linroid.kdown.api.config.QueueConfig
import com.linroid.kdown.server.KDownServerConfig
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.io.File

@Serializable
data class CliConfig(
  val server: ServerSection = ServerSection(),
  val download: DownloadSection = DownloadSection(),
)

@Serializable
data class ServerSection(
  val host: String = "0.0.0.0",
  val port: Int = 8642,
  @SerialName("api-token")
  val apiToken: String? = null,
  @SerialName("cors-allowed-hosts")
  val corsAllowedHosts: List<String> = emptyList(),
)

@Serializable
data class DownloadSection(
  val directory: String? = null,
  @SerialName("max-connections")
  val maxConnections: Int = 4,
  @SerialName("retry-count")
  val retryCount: Int = 3,
  @SerialName("retry-delay-ms")
  val retryDelayMs: Long = 1000,
  @SerialName("progress-update-interval-ms")
  val progressUpdateIntervalMs: Long = 200,
  @SerialName("segment-save-interval-ms")
  val segmentSaveIntervalMs: Long = 5000,
  @SerialName("buffer-size")
  val bufferSize: Int = 8192,
  @SerialName("speed-limit")
  val speedLimit: String? = null,
  val queue: QueueSection = QueueSection(),
)

@Serializable
data class QueueSection(
  @SerialName("max-concurrent-downloads")
  val maxConcurrentDownloads: Int = 3,
  @SerialName("max-connections-per-host")
  val maxConnectionsPerHost: Int = 4,
  @SerialName("auto-start")
  val autoStart: Boolean = true,
)

fun CliConfig.toDownloadConfig(fallbackDir: String): DownloadConfig {
  val speedLimit = download.speedLimit?.let { parseSpeedLimit(it) }
    ?: SpeedLimit.Unlimited
  return DownloadConfig(
    defaultDirectory = download.directory ?: fallbackDir,
    maxConnections = download.maxConnections,
    retryCount = download.retryCount,
    retryDelayMs = download.retryDelayMs,
    progressUpdateIntervalMs = download.progressUpdateIntervalMs,
    segmentSaveIntervalMs = download.segmentSaveIntervalMs,
    bufferSize = download.bufferSize,
    speedLimit = speedLimit,
    queueConfig = QueueConfig(
      maxConcurrentDownloads = download.queue.maxConcurrentDownloads,
      maxConnectionsPerHost = download.queue.maxConnectionsPerHost,
      autoStart = download.queue.autoStart,
    ),
  )
}

fun CliConfig.toServerConfig(): KDownServerConfig {
  return KDownServerConfig(
    host = server.host,
    port = server.port,
    apiToken = server.apiToken,
    corsAllowedHosts = server.corsAllowedHosts,
  )
}

fun loadConfig(path: String): CliConfig {
  val file = File(path)
  if (!file.exists()) {
    throw IllegalArgumentException("Config file not found: $path")
  }
  val content = file.readText()
  return Toml.decodeFromString(CliConfig.serializer(), content)
}

fun defaultConfigPath(): String {
  val os = System.getProperty("os.name", "").lowercase()
  val home = System.getProperty("user.home")
  val configDir = when {
    os.contains("mac") ->
      "$home${File.separator}Library${File.separator}" +
        "Application Support${File.separator}kdown"
    os.contains("win") -> {
      val appData = System.getenv("APPDATA")
        ?: "$home${File.separator}AppData${File.separator}Roaming"
      "$appData${File.separator}kdown"
    }
    else -> {
      val xdg = System.getenv("XDG_CONFIG_HOME")
        ?: "$home${File.separator}.config"
      "$xdg${File.separator}kdown"
    }
  }
  return File(configDir, "config.toml").absolutePath
}

private const val DEFAULT_CONFIG_CONTENT = """# KDown Server Configuration
# Lines starting with # are comments.

[server]
host = "0.0.0.0"
port = 8642
# api-token = "my-secret"
# cors-allowed-hosts = ["http://localhost:3000"]

[download]
# directory = "~/Downloads"
# speed-limit = "10m"  # Suffixes: k = KB/s, m = MB/s, or raw bytes
max-connections = 4
retry-count = 3
retry-delay-ms = 1000
progress-update-interval-ms = 200
segment-save-interval-ms = 5000
buffer-size = 8192

[download.queue]
max-concurrent-downloads = 3
max-connections-per-host = 4
auto-start = true
"""

fun generateConfig(path: String) {
  val file = File(path)
  file.parentFile?.mkdirs()
  file.writeText(DEFAULT_CONFIG_CONTENT)
}
