# Ketch CLI

Command-line interface for Ketch. Supports single-file downloads, a queue management demo, and running the Ketch daemon server.

## Build & Run

```bash
# Build the CLI
./gradlew :cli:build

# Run directly via Gradle
./gradlew :cli:run --args="<arguments>"

# Or build the distribution and run the script
./gradlew :cli:installDist
./cli/build/install/cli/bin/cli <arguments>
```

## Commands

### Download a file

```bash
ketch <url> [destination] [options]
```

| Option | Description |
|---|---|
| `--speed-limit <value>` | Limit download speed (e.g., `500k`, `1m`, `10m`) |
| `--priority <level>` | Set download priority: `low`, `normal`, `high`, `urgent` |
| `--max-concurrent <n>` | Max simultaneous downloads (default: 3) |

**Examples:**

```bash
# Basic download
ketch https://example.com/file.zip

# Download to a specific path
ketch https://example.com/file.zip /tmp/file.zip

# With speed limit and priority
ketch --speed-limit 1m --priority high https://example.com/file.zip
```

### Queue demo

Demonstrates queue management with multiple concurrent downloads and priority ordering.

```bash
ketch --queue-demo <url1> <url2> <url3> ...
```

Runs at most 2 downloads concurrently. Remaining URLs are queued by priority. After 3 seconds, the first download's priority is boosted to URGENT to demonstrate dynamic re-ordering.

### Server

Start the Ketch daemon server with REST API and SSE event stream.

```bash
ketch server [options]
```

| Option | Description |
|---|---|
| `--config <path>` | Path to a TOML config file |
| `--generate-config` | Generate a default config file and exit |
| `--host <address>` | Bind address (default: `0.0.0.0`) |
| `--port <number>` | Port number, 1-65535 (default: `8642`) |
| `--token <string>` | API bearer token for authentication |
| `--cors <origins>` | Comma-separated CORS allowed origins |
| `--dir <path>` | Download directory (default: `~/Downloads`) |
| `--speed-limit <value>` | Global speed limit (e.g., `10m`, `500k`) |
| `--help`, `-h` | Show help message |

**Examples:**

```bash
# Start with defaults
ketch server

# Custom port and download directory
ketch server --port 9000 --dir /tmp/downloads

# With authentication and CORS
ketch server --token my-secret --cors "http://localhost:3000"

# With a config file, overriding the port
ketch server --config /path/to/config.toml --port 9999

# Generate a default config file
ketch server --generate-config
```

## Configuration File

The server command supports TOML configuration files. CLI flags always take precedence over config file values.

### Config file locations

| Platform | Default path |
|---|---|
| macOS | `~/Library/Application Support/ketch/config.toml` |
| Linux | `$XDG_CONFIG_HOME/ketch/config.toml` (default: `~/.config/ketch/config.toml`) |
| Windows | `%APPDATA%\ketch\config.toml` |

If no `--config` flag is provided, the CLI automatically loads from the default path when the file exists.

### Generating a config file

```bash
ketch server --generate-config
```

This creates a commented config file at the default location. Edit it to customize your setup.

### Config file format

Keys are camelCase and match the Kotlin property names. Unknown keys are ignored without a
warning, so a misspelled key silently keeps its default. `--generate-config` writes this
template:

```toml
# Ketch Configuration

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
```

`~` is not expanded: set `defaultDirectory` to an absolute path such as
`"/home/me/Downloads"`.

### Config reference

#### Top level

`name` must appear before the first `[table]` header.

| Key | Type | Default | Description |
|---|---|---|---|
| `name` | string | *(none)* | Name shown to clients; unset: `"Ketch"` (CLI), device name (apps) |

#### `[server]`

| Key | Type | Default | Description |
|---|---|---|---|
| `host` | string | `"0.0.0.0"` | Network interface to bind to |
| `port` | int | `8642` | Port to listen on (1-65535) |
| `apiToken` | string | *(none)* | Bearer token for API authentication |
| `corsAllowedHosts` | string[] | `[]` | Allowed CORS origins (e.g., `["*"]` for all) |
| `mdnsEnabled` | bool | `true` | Advertise the server on the local network via mDNS/DNS-SD |
| `autoStart` | bool | `false` | Start the server when the apps launch; `ketch server` ignores it |

#### `[download]`

| Key | Type | Default | Description |
|---|---|---|---|
| `defaultDirectory` | string | *(none)* | Save directory (absolute); unset: `Downloads` in home |
| `speedLimit` | string | `"unlimited"` | Global speed limit (`"500k"`, `"10m"`, or bytes) |
| `maxConnectionsPerDownload` | int | `4` | Connections (segments) per download, > 0 |
| `maxConcurrentDownloads` | int | `2` | Max simultaneous downloads; `0` = unlimited |
| `maxConnectionsPerHost` | int | `8` | Max simultaneous downloads per host; `0` = unlimited |
| `retryCount` | int | `3` | Max automatic retries after a retryable failure |
| `retryDelayMs` | long | `1000` | Base delay between retries (exponential backoff) |
| `progressIntervalMs` | long | `200` | Minimum interval between progress updates, > 0 |
| `saveIntervalMs` | long | `5000` | Segment progress persistence interval, > 0 |
| `bufferSize` | int | `8192` | Read buffer size in bytes for FTP data transfers, > 0 |

#### `[torrent]`

| Key | Type | Default | Description |
|---|---|---|---|
| `trackers` | string[] | `[]` | Extra `http`, `https` or `udp` trackers for public torrents |

#### `[[remotes]]`

Remote servers the apps list for connection; the CLI does not read them. Repeat the table once
per server.

| Key | Type | Default | Description |
|---|---|---|---|
| `host` | string | *(required)* | Remote server hostname or IP |
| `port` | int | `8642` | Remote server port |
| `apiToken` | string | *(none)* | Bearer token for the remote server |
| `secure` | bool | `false` | Connect over HTTPS instead of HTTP |

#### Other sections

- `[ai]`, `[ai.llm]`, `[ai.search]`: AI resource discovery, used by `ketch ai-discover`; see
  [AI discovery](../docs/ai-discovery.md#configtoml).
- `[appearance]`: `accent` and `theme` for the apps; the CLI ignores it.

## Speed Limit Format

Speed limits accept human-readable suffixes:

| Suffix | Unit | Example |
|---|---|---|
| `k` | KB/s | `500k` = 500 KB/s |
| `m` | MB/s | `10m` = 10 MB/s |
| *(none)* | bytes/s | `1048576` = 1 MB/s |

## Database

Task metadata is stored in a SQLite database at the platform config directory:

| Platform | Default path |
|---|---|
| macOS | `~/Library/Application Support/ketch/ketch.db` |
| Linux | `$XDG_CONFIG_HOME/ketch/ketch.db` |
| Windows | `%APPDATA%\ketch\ketch.db` |
