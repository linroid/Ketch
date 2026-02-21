package com.linroid.kdown.cli

import com.akuleshov7.ktoml.Toml
import com.linroid.kdown.api.config.KDownConfig
import java.io.File

fun loadConfig(path: String): KDownConfig {
  val file = File(path)
  if (!file.exists()) {
    throw IllegalArgumentException("Config file not found: $path")
  }
  return Toml.decodeFromString(KDownConfig.serializer(), file.readText())
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

# name = "My KDown"

[server]
host = "0.0.0.0"
port = 8642
# apiToken = "my-secret"
# corsAllowedHosts = ["http://localhost:3000"]

[download]
# defaultDirectory = "~/Downloads"
# speedLimit = "10m"  # "unlimited", "10m" (MB/s), "500k" (KB/s), or raw bytes
maxConnections = 4
retryCount = 3
retryDelayMs = 1000
progressUpdateIntervalMs = 200
segmentSaveIntervalMs = 5000
bufferSize = 8192

[download.queueConfig]
maxConcurrentDownloads = 3
maxConnectionsPerHost = 4
autoStart = true
"""

fun generateConfig(path: String) {
  val file = File(path)
  file.parentFile?.mkdirs()
  file.writeText(DEFAULT_CONFIG_CONTENT)
}
