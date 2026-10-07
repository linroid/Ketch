# Ketch CLI

Command-line interface for Ketch. Downloads a single file, runs the Ketch daemon server, serves
Ketch to AI agents over MCP, and finds downloads with AI discovery.

## Install

Install the native binary (no JVM required) with the install script:

```bash
curl -fsSL https://raw.githubusercontent.com/linroid/Ketch/main/install.sh | bash
```

The script installs `ketch` to `/usr/local/bin`, using `sudo` when that directory is not
writable, and puts the license notices in `ketch-licenses` next to it. Set `KETCH_VERSION` to
install a specific release instead of the latest, or `KETCH_INSTALL` to choose another directory:

```bash
curl -fsSL https://raw.githubusercontent.com/linroid/Ketch/main/install.sh \
  | KETCH_INSTALL="$HOME/.local/bin" bash
```

You can also download an archive from
[GitHub Releases](https://github.com/linroid/Ketch/releases/latest). Each one is named
`ketch-cli-<version>-<os>-<arch>.tar.gz` (`.zip` on Windows) and contains the `ketch` binary and a
`licenses` folder.

| OS (`<os>`) | Architectures (`<arch>`) |
|---|---|
| `macos` | `x64`, `arm64` |
| `linux` | `x64`, `arm64` |
| `windows` | `x64` |

Once installed, `ketch update` keeps the binary current; see [Update](#update).

## Build & Run

```bash
# Build the CLI (also builds the web UI that `ketch server` serves)
./gradlew :cli:build

# Run directly via Gradle
./gradlew :cli:run --args="<arguments>"

# Or build the distribution and run the script
./gradlew :cli:installDist
./cli/build/install/cli/bin/cli <arguments>

# Or build the native binary (requires an Oracle GraalVM for JDK 21 toolchain)
./gradlew :cli:nativeCompile
./cli/build/native/nativeCompile/ketch <arguments>
```

## Commands

Running `ketch` without arguments, or with `--help` or `-h`, prints the usage.

### Global options

These flags can appear anywhere on the command line and apply to every command. Logs are written to
the console at info level by default; see [logging](../docs/logging.md) for what each level shows.

| Option | Description |
|---|---|
| `-v`, `--verbose` | Debug logging |
| `--debug` | Verbose logging, including speed limiter waits and per-peer detail |

### Download a file

```bash
ketch [options] <url> [destination]
```

Downloads HTTP(S) and FTP/FTPS URLs (`ftp://[user:password@]host[:port]/path`), magnet links, and
`.torrent` URLs or files. Without a destination the file is saved in the current directory. An
existing directory, or a path ending in a separator, keeps the file name from the source; any other
path, including a bare file name such as `file.zip`, is the file path, relative to the current
directory. Torrents follow the
[torrent destination rules](../docs/torrent.md), and public ones also announce to the `[torrent]`
trackers, and the tracker list it subscribes to, of the default
[config file](#config-file-locations).

HTTP(S) `.m3u8` and `.mpd` URLs download finite, unencrypted HLS/DASH streams as one media file:
`ketch 'https://example.com/video/index.m3u8'`. HLS master playlists choose the highest-bandwidth
variant. Live streams and separate audio/video tracks are unsupported; see
[media support and limits](../docs/media.md).

The download is kept in memory only and is not recorded in the [task database](#database). The
command exits when the download completes or fails.

| Option | Description |
|---|---|
| `--speed-limit <value>` | Limit download speed (e.g., `500k`, `1m`, `10m`) |
| `--priority <level>` | Set download priority: `low`, `normal`, `high`, `urgent` |
| `--max-concurrent <n>` | Max simultaneous downloads (default: 4) |
| `-H`, `--header <header>` | Send a request header, as `'Name: value'`; repeatable |
| `--user-agent <value>` | Send this `User-Agent` instead of `Ketch/<version>` |
| `--referer <url>` | Send this `Referer` |
| `--help`, `-h` | Show help message |

Headers go with every request of the download. A redirect to another scheme, host or port keeps
only `User-Agent`, `Accept`, `Accept-Encoding`, `Accept-Language` and the origin of `Referer`;
cookies, `Authorization` and other headers stay with the site they were given for.

**Examples:**

```bash
# Basic download
ketch https://example.com/file.zip

# Save as file.zip in the current directory
ketch https://example.com/latest.zip file.zip

# Download to a specific path
ketch https://example.com/file.zip /tmp/file.zip

# With speed limit and priority
ketch --speed-limit 1m --priority high https://example.com/file.zip

# A file behind a sign-in, with the cookie and referring page the site expects
ketch -H 'Cookie: session=abc123' --referer https://example.com/downloads \
  https://example.com/files/report.pdf

# FTP with credentials, and a magnet link into a directory, with debug logs
ketch ftp://user:secret@ftp.example.com/pub/file.iso
ketch -v "magnet:?xt=urn:btih:<info-hash>" ~/Downloads/
```

### Server

Start the Ketch daemon server with REST API, SSE event stream, and the bundled web UI. It
downloads HTTP(S), FTP/FTPS and BitTorrent sources, stores tasks in the
[task database](#database) and restores them when it starts, and announces itself on the local
network over mDNS unless `mdnsEnabled` is `false`. It listens on every interface by default and
then requires an [access token](#access-token).

```bash
ketch server [options]
```

| Option | Description |
|---|---|
| `--config <path>` | Path to a TOML config file |
| `--generate-config` | Generate a default config file at the [default path](#config-file-locations) and exit |
| `--host <address>` | Bind address (default: `0.0.0.0`) |
| `--port <number>` | Port number, 1-65535 (default: `8642`) |
| `--token <string>` | API bearer token; also read from `KETCH_API_TOKEN`. Without one, a server listening beyond loopback creates one (see [Access token](#access-token)) |
| `--no-token` | Require no token, even when listening beyond loopback. Anyone who can reach the server can then use it |
| `--cors <origins>` | Comma-separated origins (`http://localhost:3000`), hosts for both `http` and `https` (`localhost:3000`), or `*`, whose web pages may call the API; needs a token, and is `*` with a token unless set (see [Web pages](#web-pages)) |
| `--allowed-hosts <names>` | Comma-separated extra `Host` names accepted without a token |
| `--allowed-dirs <paths>` | Comma-separated folders, besides the download directory, that clients may save to and delete files from (see [Save folders](#save-folders)) |
| `--dir <path>` | Download directory (default: `~/Downloads`) |
| `--speed-limit <value>` | Global speed limit (e.g., `10m`, `500k`) |
| `--help`, `-h` | Show help message |

**Examples:**

```bash
# Start with defaults: every interface, with a token it creates on the first run
ketch server

# Custom port and download directory
ketch server --port 9000 --dir /tmp/downloads

# With your own token, and only pages on localhost:3000 may call it
ketch server --token my-secret --cors "localhost:3000"

# The same token, from the environment
KETCH_API_TOKEN=my-secret ketch server

# This machine only, without a token
ketch server --host 127.0.0.1 --no-token

# Let clients also save to two more folders
ketch server --allowed-dirs /srv/media,/srv/iso

# With a config file, overriding the port
ketch server --config /path/to/config.toml --port 9999

# Generate a default config file
ketch server --generate-config

# Without a token, also accept requests addressed to a DNS alias
ketch server --allowed-hosts nas.example.com
```

#### Access token

A server that listens beyond this machine, on any `--host` but `127.0.0.1`, `localhost` or
`::1`, requires a bearer token (`Authorization: Bearer <token>`) on every API request. It uses
the first of:

1. `--token`
2. the `KETCH_API_TOKEN` environment variable
3. `apiToken` in the [config file](#configuration-file)
4. the token in the `api-token` file in the [config directory](#config-file-locations), which
   the first run creates, readable by your user only, and prints:

   ```text
     Auth:          new token, saved to /home/me/.config/ketch/api-token
     Token:         3f2a9c1e…
   ```

   Later runs print where they read it from; `cat` the file to see it again, or delete it for a
   new one.

Enter the token where the Ketch apps or the browser extension ask for an access code, or open
the web UI as `http://<address>:8642/#token=<token>`. An address that sends ten wrong tokens
within a minute gets `429 Too Many Requests` for any token until that minute ends.

A server on loopback takes no token unless one is given, since only this machine can reach it.
`--no-token` runs without one even beyond loopback, and prints a warning: anyone who can reach
the server can then add downloads, list, pause and remove tasks, and delete their files in the
[save folders](#save-folders).

#### Save folders

Clients choose where downloads are saved, and removing a task with `deleteFiles=true` deletes
its files. Without a token, the server keeps clients to the download directory (`--dir` or
`defaultDirectory`) and the folders in `--allowed-dirs` or `allowedDirectories`:

- A new task's destination must be inside them. A relative path is taken from the download
  directory, and a file that exists is never overwritten: the download gets a free name such as
  `file (1).zip`.
- A resumed task's `destination` must be an absolute file path inside them.
- `PUT /api/config` may only move the download directory to a folder inside them.
- `deleteFiles=true` only deletes files inside them; remove the task without it otherwise.

Anything else gets `403 Forbidden` with a `path_rejected` error. Paths are compared after `..`
is resolved and symbolic links are followed, so a link inside a folder that points elsewhere
counts as elsewhere.

With a token, clients may save anywhere, as the Ketch apps let you type any folder on another
device; set `--allowed-dirs` or `allowedDirectories` to keep them to these folders too.

File names a client gives are cleaned like the names sites suggest: only the part after the
last `/` or `\` is kept, and characters Windows forbids become `_`.

#### Request limits

JSON request bodies must be `application/json` and at most 1 MiB, or 32 MiB for a new task,
which can carry a resolved torrent; uploaded `.torrent` content may be 16 MiB. Longer bodies get
`413 Payload Too Large`.

#### Accepted hosts

Without an API token (on loopback, or with `--no-token`), the server only answers requests
whose `Host` header (ignoring the port) names this machine:

- `localhost`, `*.localhost`, `127.0.0.0/8` or `[::1]`
- an IP address of one of the machine's network interfaces, such as `192.168.1.20`
- the machine's host name, or its mDNS name `<host>.local` (for example `my-mac.local`)
- a name or IP address listed in `--allowed-hosts` or `allowedHosts`

Anything else gets `403 Forbidden` with a `host_not_allowed` error. This stops DNS rebinding,
where a web page points its own domain at your machine to reach the API from the browser.
Ketch apps connect to servers they discover on the network by IP address, and the browser
extension defaults to `http://127.0.0.1:8642`, so neither needs configuration.

With a token, any `Host` is accepted, since a web page cannot learn the token. Keep a token when
the server is reached through another name, such as a reverse proxy or a DNS alias, instead of
listing every name.

#### Web pages

A browser lets any web page send requests to the server, so it checks where a browser request
comes from:

- **Without a token**, it refuses requests from web pages on another origin (a different host
  or port) with `403 Forbidden` and an `origin_not_allowed` error, and ignores `--cors`.
  Otherwise any site you visit could start downloads, list your tasks, and pause or cancel
  them. These still work: the web UI this server serves, browser extensions such as Ketch's
  own, and clients outside a browser, such as the Ketch apps and `curl`.
- **With a token**, `--cors` lists the origins whose pages may call the API, such as
  `http://localhost:3000` (a bare `localhost:3000` allows both `http` and `https`), or `*` for
  any. Without `--cors` or `corsAllowedHosts`, any origin may, so the hosted web app can
  connect. Pages still need the token, which they cannot learn.

Behind a reverse proxy that rewrites the `Host` header, the web UI counts as another origin;
keep a token there. The Ketch apps' server follows the same rules.

### MCP server

Run Ketch as a [Model Context Protocol](https://modelcontextprotocol.io) server over stdio, so AI
agents can list, start, pause, resume, cancel and remove downloads, resolve URLs, and change speed
limits, priorities and the download config. It uses the same config file and
[task database](#database) as `ketch server`, and restores saved tasks when it starts.

```bash
ketch mcp [options]
```

| Option | Description |
|---|---|
| `--config <path>` | Path to a TOML config file |
| `--dir <path>` | Download directory (default: `~/Downloads`) |
| `--help`, `-h` | Show help message |

Each tool returns a JSON document as the text of its result. Parameters that have a default,
such as everything but `url` in `startDownload`, are optional in the tool's input schema.

Stdout carries only the MCP protocol; the banner and all logs go to stderr. The server exits once
the client closes stdin, after answering the requests it has already read, so it can also be
scripted, e.g. `printf '%s\n' '<request>' | ketch mcp`. Register it with your MCP client, for
example in Claude Desktop's `claude_desktop_config.json`:

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

### AI discovery

Ask an LLM agent to find download links for a natural-language query. It prints the agent's
summary and the candidates it found with their URL, file name, size and confidence. The version
banner, the model and query it uses, the agent's steps as they happen, the questions and errors
go to stderr, so stdout holds only the results.

```bash
ketch ai-discover <query> [options]
```

| Option | Description |
|---|---|
| `--sites <domains>` | Comma-separated domains to limit discovery to, subdomains included; redirects to download hosts are followed (see [AI discovery](../docs/ai-discovery.md#limiting-discovery-to-websites)) |
| `--max-results <n>` | Max candidates to return (default: 5) |
| `-y`, `--yes` | Open websites without asking |
| `--no-filter` | Show results the [content filter](../docs/ai-discovery.md#the-content-filter) hides |
| `-h`, `--help` | Show the command's usage |

Unknown options are an error rather than part of the query.

The command reads the `[ai]` section of the default [config file](#config-file-locations), which
the apps edit under Settings → Discover. Blank API keys are filled from `OPENAI_API_KEY`,
`ANTHROPIC_API_KEY` or `GEMINI_API_KEY`; without an `[ai]` section, exporting one of them is
enough. See [AI discovery](../docs/ai-discovery.md) for providers, web search keys, and how
settings and environment variables combine.

#### Page access

The command follows `[ai.access]`, which the apps edit under Settings → Discover. Unless its
`mode` is `"allow"` or the site is in `trustedSites`, it asks before the agent opens a website
(reads a page or checks a file, redirects to a new host included):

```text
Allow Discover to open www.blender.org? https://www.blender.org/download/
  Discover says: Read the Blender download page
[y] allow  [s] allow blender.org for this run  [a] allow all  [n] deny:
```

- `y` allows the site for the rest of the run in the default `"ask-site"` mode, and only this
  request in `"ask"` mode
- `s` allows the site and its subdomains for the rest of the run, `a` every website
- `n`, an empty or unknown answer, or the end of input denies; after the end of input (Ctrl+D)
  every later request is denied without asking. A denied site is not asked about again

Answers last for the run; the CLI never writes `config.toml`. It asks on the controlling
terminal (`/dev/tty`), so questions still reach you when stdout or stdin is redirected. On
Windows it asks on the console, and only while stdin and stdout are both the console: a
redirected run there needs `--yes` or `--sites`. Searches through the search provider never ask,
and neither does a run limited with `--sites`: every host it may open is one you named.

Without a terminal, such as under cron or in CI, a run that may ask stops at once with exit
status 1. Pass `--yes`, limit it with `--sites`, or set `mode = "allow"` to run it there.

The exit status is 0 when the search ran, whether or not it found candidates; 1 when it could
not run (AI discovery not configured, no terminal to ask on) or failed (a provider or agent
error); and 2 for invalid arguments.

**Examples:**

```bash
ketch ai-discover "latest Ubuntu 24.04 ISO"
ketch ai-discover "ffmpeg release" --sites ffmpeg.org
ketch ai-discover --yes "blender 4.2 macOS" > results.txt
```

### Update

Replace the `ketch` binary with the latest release from GitHub. The archive is checked against the
SHA-256 digest GitHub published for it before anything is replaced, and the license notices next
to the binary are updated too.

```bash
ketch update [options]
```

| Option | Description |
|---|---|
| `--check` | Only report whether a newer release exists |
| `--version <version>` | Install this release instead, also an older one (e.g. `0.0.1-rc15`) |
| `--help`, `-h` | Show help message |

On macOS and Linux the new binary is renamed over the old one, so a `ketch server` that is running
keeps working until you restart it. Windows cannot replace a running program, so the old binary
is renamed to `ketch.exe.old` and removed the next time `ketch` runs. A binary installed in a
folder you cannot write to, such as `/usr/local/bin`, needs `sudo ketch update` (on Windows, a
terminal opened as administrator). Only the native binary updates itself; `./gradlew :cli:run`
and `installDist` builds can still run `ketch update --check`.

**Examples:**

```bash
ketch update --check
sudo ketch update
ketch update --version 0.0.1-rc15
```

## Configuration File

The `server` and `mcp` commands support TOML configuration files. CLI flags always take precedence
over config file values. The download command reads only `[torrent]`, and `ai-discover` only
`[ai]`, from the default path.

### Config file locations

| Platform | Default path |
|---|---|
| macOS | `~/Library/Application Support/ketch/config.toml` |
| Linux | `$XDG_CONFIG_HOME/ketch/config.toml` (default: `~/.config/ketch/config.toml`) |
| Windows | `%APPDATA%\ketch\config.toml` |

If no `--config` flag is provided, the CLI automatically loads from the default path when the file
exists. The desktop app uses the same directory, so the CLI shares its settings and task database.
The [portable Windows app](../docs/updates.md#the-portable-windows-app) keeps both in its own
`data` folder instead, which the CLI does not read.

### Generating a config file

```bash
ketch server --generate-config
```

This creates a commented config file at the default location. Edit it to customize your setup. It
never overwrites an existing file.

### Config file format

Keys are camelCase and match the Kotlin property names. Unknown keys are ignored without a
warning, so a misspelled key silently keeps its default.

```toml
# Ketch Configuration

# Instance name shown to clients and announced over mDNS
# name = "My Ketch"

[server]
host = "0.0.0.0"
port = 8642
# Without apiToken, a server listening beyond loopback uses KETCH_API_TOKEN or
# the token it keeps in the api-token file beside this one.
# apiToken = "my-secret"
# mdnsEnabled = true
# corsAllowedHosts = ["localhost:3000"]  # host[:port] without a scheme, or "*"
# Without apiToken, requests must address this machine: localhost, one of its
# IP addresses, its host name or <host>.local. List any other name used to
# reach the server here.
# allowedHosts = ["nas.example.com"]
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

# Extra trackers announced alongside public torrents' own trackers, e.g. when
# a network blocks a torrent's own tracker. Private torrents ignore them. The
# apps edit this under Settings > BitTorrent.
# [torrent]
# trackers = ["udp://tracker.opentrackr.org:1337/announce"]
# Subscribe to a list of public trackers (one announce URL per line), downloaded
# daily and used after the trackers above.
# trackerList = true
# trackerListUrl = "https://raw.githubusercontent.com/ngosang/trackerslist/master/trackers_best.txt"

# Pre-configured remote servers.
# [[remotes]]
# host = "192.168.1.100"
# port = 8642
# apiToken = "token"
# secure = false
```

### Config reference

`name` must appear before the first `[table]` header.

| Key | Type | Default | Description |
|---|---|---|---|
| `name` | string | `"Ketch"` | Instance name shown to clients and announced over mDNS |

#### `[server]`

| Key | Type | Default | Description |
|---|---|---|---|
| `host` | string | `"0.0.0.0"` | Network interface to bind to |
| `port` | int | `8642` | Port to listen on (1-65535) |
| `apiToken` | string | *(none)* | Bearer token for API authentication; without one, a server beyond loopback uses `KETCH_API_TOKEN` or the saved `api-token` (see [Access token](#access-token)) |
| `corsAllowedHosts` | string[] | `[]` | Origins whose web pages may call the API (`["*"]` for any); needs a token, and any origin may with a token and no list (see [Web pages](#web-pages)) |
| `allowedHosts` | string[] | `[]` | Extra `Host` names or IPs accepted without a token (see [Accepted hosts](#accepted-hosts)) |
| `allowedDirectories` | string[] | `[]` | Folders, besides `defaultDirectory`, that clients may save to and delete files from; a leading `~` is your home folder (see [Save folders](#save-folders)) |
| `mdnsEnabled` | bool | `true` | Announce the server on the local network (`_ketch._tcp`) |

#### `[download]`

| Key | Type | Default | Description |
|---|---|---|---|
| `defaultDirectory` | string | `~/Downloads` | Default save directory; a leading `~` is your home folder |
| `speedLimit` | string | `"unlimited"` | Global speed limit (`"500k"`, `"10m"`, or bytes) |
| `maxConnectionsPerDownload` | int | `4` | Connections (segments) per HTTP or FTP download |
| `maxConcurrentDownloads` | int | `4` | Max simultaneous downloads (`0` = unlimited) |
| `maxConnectionsPerHost` | int | `16` | Max simultaneous downloads per host (`0` = unlimited) |
| `retryCount` | int | `3` | Max automatic retries after a retryable failure |
| `retryDelayMs` | long | `1000` | Base delay between retries (exponential backoff) |
| `progressIntervalMs` | long | `200` | Progress update throttle interval |
| `saveIntervalMs` | long | `5000` | Segment progress persistence interval |
| `bufferSize` | int | `8192` | FTP read buffer size in bytes |

A file with an invalid value fails to load. `maxConnectionsPerDownload`, `progressIntervalMs`,
`saveIntervalMs` and `bufferSize` must be greater than 0; the other counts and delays must not
be negative.

#### `[torrent]`

| Key | Type | Default | Description |
|---|---|---|---|
| `trackers` | string[] | `[]` | Extra `http`, `https` or `udp` trackers that public torrents also announce to |
| `trackerList` | bool | `true` | Subscribe to the tracker list at `trackerListUrl`, downloaded daily; its trackers are used after `trackers` |
| `trackerListUrl` | string | ngosang's [`trackers_best.txt`](https://github.com/ngosang/trackerslist) | `http` or `https` URL of a plain-text list, one announce URL per line |

The `[ai]` section is described in [AI discovery](../docs/ai-discovery.md#configtoml). The apps
also keep `[[remotes]]`, `[appearance]` and `server.autoStart` in this file; the CLI ignores them.

## Speed Limit Format

Speed limits accept human-readable suffixes:

| Suffix | Unit | Example |
|---|---|---|
| `k` | KB/s | `500k` = 500 KB/s |
| `m` | MB/s | `10m` = 10 MB/s |
| *(none)* | bytes/s | `1048576` = 1 MB/s |

`unlimited` removes the limit.

## Database

Tasks of `ketch server` and `ketch mcp` are stored in a SQLite database in the config directory:

| Platform | Default path |
|---|---|
| macOS | `~/Library/Application Support/ketch/ketch.db` |
| Linux | `$XDG_CONFIG_HOME/ketch/ketch.db` (default: `~/.config/ketch/ketch.db`) |
| Windows | `%APPDATA%\ketch\ketch.db` |

BitTorrent DHT state is kept in the `torrent-state` folder of the same directory.
