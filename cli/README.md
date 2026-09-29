# Ketch CLI

Command-line interface for Ketch. Supports single-file downloads, running the Ketch daemon server, and an MCP server for AI agents. Run `ketch --help` for every command and option, including `ai-discover`.

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
| `--help`, `-h` | Show help message |

The command downloads the URL, shows its progress and exits when the download finishes or fails.
Relative destinations are resolved against the current directory:

| Destination | Saved to |
|---|---|
| *(omitted)* | The current directory, using the file name from the server |
| An existing directory, or a path ending in `/` | That directory, using the file name from the server |
| A file name or path, such as `file.zip` or `out/file.zip` | That file, relative to the current directory |

**Examples:**

```bash
# Basic download into the current directory
ketch https://example.com/file.zip

# Save as file.zip in the current directory
ketch https://example.com/latest.zip file.zip

# Download to a specific path
ketch https://example.com/file.zip /tmp/file.zip

# With speed limit and priority
ketch --speed-limit 1m --priority high https://example.com/file.zip
```

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
| `--cors <hosts>` | Comma-separated CORS allowed hosts as `host[:port]` without a scheme, or `*` for any |
| `--dir <path>` | Download directory (default: `~/Downloads`) |
| `--speed-limit <value>` | Global speed limit (e.g., `10m`, `500k`) |
| `--help`, `-h` | Show help message |

**Examples:**

```bash
# Start with defaults
ketch server

# Custom port and download directory
ketch server --port 9000 --dir /tmp/downloads

# With authentication, allowing browser pages served from http://localhost:3000
ketch server --token my-secret --cors "localhost:3000"

# With a config file, overriding the port
ketch server --config /path/to/config.toml --port 9999

# Generate a default config file
ketch server --generate-config
```

The server restores the tasks saved in its database when it starts.

### MCP server

Run Ketch as an [MCP](https://modelcontextprotocol.io) server over stdio, so AI agents can
start and manage downloads.

```bash
ketch mcp [options]
```

| Option | Description |
|---|---|
| `--config <path>` | Path to a TOML config file |
| `--dir <path>` | Download directory (default: `~/Downloads`) |
| `--help`, `-h` | Show help message |

Stdout carries only the MCP protocol; the banner and all logs go to stderr. Example client
configuration (e.g. `claude_desktop_config.json`):

```json
{
  "mcpServers": {
    "ketch": {
      "command": "ketch",
      "args": ["mcp"]
    }
  }
}
```

## Configuration File

The `server` and `mcp` commands read a TOML configuration file. CLI flags always take precedence over config file values. The desktop app uses the same file.

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

Keys use the same camelCase names as the Kotlin properties. Unknown keys are ignored, so check the
spelling if a setting has no effect.

```toml
# Display name for this instance (optional).
# name = "My Ketch"

[server]
host = "0.0.0.0"
port = 8642
# apiToken = "my-secret"
# mdnsEnabled = true
# corsAllowedHosts = ["localhost:3000"]  # host[:port] without a scheme, or "*"

[download]
# defaultDirectory = "~/Downloads"  # ~ is your home folder
# speedLimit = "unlimited"  # "unlimited", "10m" (MB/s), "500k" (KB/s)
maxConnectionsPerDownload = 4
maxConcurrentDownloads = 2
maxConnectionsPerHost = 8

# [torrent]
# trackers = ["udp://tracker.opentrackr.org:1337/announce"]
```

### Config reference

#### Top level

| Key | Type | Default | Description |
|---|---|---|---|
| `name` | string | `"Ketch"` for the CLI | Display name for this instance |

#### `[server]`

| Key | Type | Default | Description |
|---|---|---|---|
| `host` | string | `"0.0.0.0"` | Network interface to bind to |
| `port` | int | `8642` | Port to listen on |
| `apiToken` | string | *(none)* | Bearer token for API authentication |
| `corsAllowedHosts` | string[] | `[]` | Allowed CORS hosts as `host[:port]` without a scheme, or `["*"]` for all |
| `mdnsEnabled` | bool | `true` | Advertise the server on the local network via mDNS |

#### `[download]`

| Key | Type | Default | Description |
|---|---|---|---|
| `defaultDirectory` | string | `~/Downloads` | Default save directory; a leading `~` is your home folder |
| `speedLimit` | string | `"unlimited"` | Global speed limit (`"500k"`, `"10m"`, or bytes per second) |
| `maxConnectionsPerDownload` | int | `4` | Concurrent connections (segments) per download |
| `maxConcurrentDownloads` | int | `2` | Max simultaneous downloads, `0` for unlimited |
| `maxConnectionsPerHost` | int | `8` | Max simultaneous downloads per host, `0` for unlimited |
| `retryCount` | int | `3` | Max retries after a retryable failure |
| `retryDelayMs` | int | `1000` | Base delay between retries (exponential backoff) |
| `progressIntervalMs` | int | `200` | Progress update throttle interval |
| `saveIntervalMs` | int | `5000` | Segment progress persistence interval |
| `bufferSize` | int | `8192` | Read buffer size in bytes for FTP transfers |

#### `[torrent]`

| Key | Type | Default | Description |
|---|---|---|---|
| `trackers` | string[] | `[]` | Extra trackers announced for public torrents |

## Speed Limit Format

Speed limits accept human-readable suffixes:

| Suffix | Unit | Example |
|---|---|---|
| `k` | KB/s | `500k` = 500 KB/s |
| `m` | MB/s | `10m` = 10 MB/s |
| *(none)* | bytes/s | `1048576` = 1 MB/s |

Use `unlimited` to remove a limit.

## Database

Task metadata is stored in a SQLite database at the platform config directory:

| Platform | Default path |
|---|---|
| macOS | `~/Library/Application Support/ketch/ketch.db` |
| Linux | `$XDG_CONFIG_HOME/ketch/ketch.db` |
| Windows | `%APPDATA%\ketch\ketch.db` |
