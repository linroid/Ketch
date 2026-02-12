# KDown CLI

Command-line interface for KDown. Supports single-file downloads, a queue management demo, and running the KDown daemon server.

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
kdown <url> [destination] [options]
```

| Option | Description |
|---|---|
| `--speed-limit <value>` | Limit download speed (e.g., `500k`, `1m`, `10m`) |
| `--priority <level>` | Set download priority: `low`, `normal`, `high`, `urgent` |
| `--max-concurrent <n>` | Max simultaneous downloads (default: 3) |

**Examples:**

```bash
# Basic download
kdown https://example.com/file.zip

# Download to a specific path
kdown https://example.com/file.zip /tmp/file.zip

# With speed limit and priority
kdown --speed-limit 1m --priority high https://example.com/file.zip
```

### Queue demo

Demonstrates queue management with multiple concurrent downloads and priority ordering.

```bash
kdown --queue-demo <url1> <url2> <url3> ...
```

Runs at most 2 downloads concurrently. Remaining URLs are queued by priority. After 3 seconds, the first download's priority is boosted to URGENT to demonstrate dynamic re-ordering.

### Server

Start the KDown daemon server with REST API and SSE event stream.

```bash
kdown server [options]
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
kdown server

# Custom port and download directory
kdown server --port 9000 --dir /tmp/downloads

# With authentication and CORS
kdown server --token my-secret --cors "http://localhost:3000"

# With a config file, overriding the port
kdown server --config /path/to/config.toml --port 9999

# Generate a default config file
kdown server --generate-config
```

## Configuration File

The server command supports TOML configuration files. CLI flags always take precedence over config file values.

### Config file locations

| Platform | Default path |
|---|---|
| macOS | `~/Library/Application Support/kdown/config.toml` |
| Linux | `$XDG_CONFIG_HOME/kdown/config.toml` (default: `~/.config/kdown/config.toml`) |
| Windows | `%APPDATA%\kdown\config.toml` |

If no `--config` flag is provided, the CLI automatically loads from the default path when the file exists.

### Generating a config file

```bash
kdown server --generate-config
```

This creates a commented config file at the default location. Edit it to customize your setup.

### Config file format

```toml
# KDown Server Configuration

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
```

### Config reference

#### `[server]`

| Key | Type | Default | Description |
|---|---|---|---|
| `host` | string | `"0.0.0.0"` | Network interface to bind to |
| `port` | int | `8642` | Port to listen on |
| `api-token` | string | *(none)* | Bearer token for API authentication |
| `cors-allowed-hosts` | string[] | `[]` | Allowed CORS origins (e.g., `["*"]` for all) |

#### `[download]`

| Key | Type | Default | Description |
|---|---|---|---|
| `directory` | string | `~/Downloads` | Default save directory |
| `speed-limit` | string | *(unlimited)* | Global speed limit (`"500k"`, `"10m"`, or bytes) |
| `max-connections` | int | `4` | Max concurrent segments per download |
| `retry-count` | int | `3` | Max retry attempts per segment |
| `retry-delay-ms` | int | `1000` | Base delay between retries (exponential backoff) |
| `progress-update-interval-ms` | int | `200` | Progress update throttle interval |
| `segment-save-interval-ms` | int | `5000` | Segment progress persistence interval |
| `buffer-size` | int | `8192` | Download buffer size in bytes |

#### `[download.queue]`

| Key | Type | Default | Description |
|---|---|---|---|
| `max-concurrent-downloads` | int | `3` | Max simultaneous downloads |
| `max-connections-per-host` | int | `4` | Max connections per host |
| `auto-start` | bool | `true` | Automatically start queued downloads |

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
| macOS | `~/Library/Application Support/kdown/kdown.db` |
| Linux | `$XDG_CONFIG_HOME/kdown/kdown.db` |
| Windows | `%APPDATA%\kdown\kdown.db` |
