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
# Origins whose web pages may call the API: host[:port] for http and https,
# scheme://host[:port], or "*" for any. Only used with apiToken: without one,
# pages on other origins are always refused.
# corsAllowedHosts = ["localhost:3000"]
# Without apiToken, requests must address this machine: localhost, one of its
# IP addresses, its host name or <host>.local. List any other name used to
# reach the server here.
# allowedHosts = ["nas.example.com"]

[download]
# defaultDirectory = "~/Downloads"  # ~ is your home folder
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

# Extra trackers announced alongside public torrents' own trackers, e.g. when
# a network blocks a torrent's own tracker. Private torrents ignore them. The
# apps edit this under Settings > BitTorrent.
# [torrent]
# trackers = ["udp://tracker.opentrackr.org:1337/announce"]

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
