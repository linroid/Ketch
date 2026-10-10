package com.linroid.ketch.config

import java.io.File

/** Default TOML config template for new installations. */
const val DEFAULT_CONFIG_CONTENT = """# Ketch Configuration

# Instance name shown to clients and announced over mDNS
# name = "My Ketch"

[server]
host = "0.0.0.0"
port = 8642
# Bearer token every API request must carry. Without one, `ketch server`
# listening beyond this machine (any host but 127.0.0.1, localhost or ::1)
# takes it from the KETCH_API_TOKEN environment variable, or creates one and
# keeps it in the api-token file beside this one; --no-token goes without.
# apiToken = "my-secret"
# mdnsEnabled = true
# Origins whose web pages may call the API: host[:port] for http and https,
# scheme://host[:port], or "*" for any. Only used with apiToken: without one,
# pages on other origins are always refused. With a token and no list, any
# origin may call, so the web app can connect; pages still need the token.
# corsAllowedHosts = ["localhost:3000"]
# Without apiToken, requests must address this machine: localhost, one of its
# IP addresses, its host name or <host>.local. List any other name used to
# reach the server here.
# allowedHosts = ["nas.example.com"]
# Folders, besides the download directory, that API callers may save to and
# delete files from. Without apiToken, callers are always kept to the download
# directory and these folders; with apiToken, only once this list is set.
# allowedDirectories = ["~/Media"]

[download]
# defaultDirectory = "~/Downloads"  # ~ is your home folder
# speedLimit = "unlimited"  # "unlimited", "10m" (MB/s), "500k" (KB/s)
maxConnectionsPerDownload = 4
# Queue limits count downloads, not individual connections; 0 means unlimited.
maxConcurrentDownloads = 4
maxConnectionsPerHost = 16

# Advanced settings (defaults are usually fine):
# retryCount = 3
# retryDelayMs = 1000
# progressIntervalMs = 200
# saveIntervalMs = 5000
# bufferSize = 8192

# How HTTP(S) downloads reach servers; FTP and BitTorrent always connect
# directly. "system" (the default) follows the https_proxy, http_proxy,
# all_proxy and no_proxy environment variables, then the system's settings;
# "direct" uses no proxy; "manual" uses url, an http:// or socks5:// proxy.
# Hosts in bypass (names, which match their subdomains too, IP addresses or
# CIDR ranges) and this machine are always reached directly. The apps edit
# this under Settings > Network.
# [download.proxy]
# mode = "manual"
# url = "socks5://127.0.0.1:1080"
# username = "me"
# password = "secret"
# bypass = ["*.lan", "10.0.0.0/8"]

# Category folders: a download that does not choose a folder is saved in the
# folder of the first category it matches, inside defaultDirectory. A download
# matches by file extension or media type (either is enough) and, when hosts
# are listed, by site too; example.com covers its subdomains. The apps edit
# these under Settings > Downloads.
# [[download.categories]]
# folder = "Video"
# extensions = ["mp4", "mkv", "webm"]
# mimeTypes = ["video/*"]
# [[download.categories]]
# folder = "Software/GitHub"
# hosts = ["github.com"]

# Extra trackers announced alongside public torrents' own trackers, e.g. when
# a network blocks a torrent's own tracker. Private torrents ignore them. The
# apps edit this under Settings > BitTorrent.
# [torrent]
# trackers = ["udp://tracker.opentrackr.org:1337/announce"]
# Lists of public trackers (one announce URL per line), downloaded daily and
# used after the trackers above: ngosang's and XIU2's best lists by default.
# trackerList = true
# trackerListUrls = ["https://lists.example.org/trackers.txt"]
# Port `ketch server` accepts peers on (TCP) and runs DHT on (UDP), for port
# forwarding; 0 picks a free one at every launch. The apps always pick one.
# listenPort = 6881
# Share verified pieces with peers: "off" (default), "while-downloading", or
# "seed" to keep sharing finished torrents while Ketch runs. Peers you upload
# to see this device's IP address. The apps edit this under Settings >
# BitTorrent.
# upload = "off"
# uploadLimit = "unlimited"  # "unlimited", "1m" (MB/s), "500k" (KB/s)

# Pre-configured remote servers. The apps show `name` (or the name the server
# announces) and stay connected to a device while another is shown unless
# `watch` is false.
# [[remotes]]
# host = "192.168.1.100"
# port = 8642
# apiToken = "token"
# secure = false
# name = "NAS"
# watch = true
"""

/** Writes a default config file to [path]. */
fun generateConfig(path: String) {
  val file = File(path)
  file.parentFile?.mkdirs()
  file.writeText(DEFAULT_CONFIG_CONTENT)
}
