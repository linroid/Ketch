package com.linroid.ketch.config

import java.io.File

/** Default TOML config template for new installations. */
const val DEFAULT_CONFIG_CONTENT = """# Ketch Configuration

# Display name for this instance (optional).
# Defaults to device name or hostname.
# name = "My Ketch"

[server]
host = "0.0.0.0"
port = 8642
# apiToken = "my-secret"
# mdnsEnabled = true
# corsAllowedHosts = ["http://localhost:3000"]

[download]
# defaultDirectory = "~/Downloads"
# speedLimit = "unlimited"  # "unlimited", "10m" (MB/s), "500k" (KB/s)
maxConnectionsPerDownload = 4
maxConcurrentDownloads = 2
maxConnectionsPerHost = 8

# Advanced settings (defaults are usually fine):
# retryCount = 3
# retryDelayMs = 1000
# progressIntervalMs = 200
# saveIntervalMs = 5000
# bufferSize = 8192

# Pre-configured remote servers.
# [[remotes]]
# host = "192.168.1.100"
# port = 8642
# apiToken = "token"
# secure = false
"""

/** Writes a default config file to [path]. */
fun generateConfig(path: String) {
  val file = File(path)
  file.parentFile?.mkdirs()
  file.writeText(DEFAULT_CONFIG_CONTENT)
}
