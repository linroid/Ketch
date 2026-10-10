# Ketch CLI

Command-line interface for Ketch. Downloads a single file, adds, lists, pauses, resumes and
watches the downloads of the Ketch app or server that is running, runs the Ketch daemon server,
serves Ketch to AI agents over MCP, and finds downloads with AI discovery.

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
trackers, and the tracker lists it subscribes to, of the default
[config file](#config-file-locations).

HTTP(S) `.m3u8` and `.mpd` URLs download finite, unencrypted HLS/DASH streams as one media file:
`ketch 'https://example.com/video/index.m3u8'`. HLS master playlists choose the highest-bandwidth
variant. Live streams and separate audio/video tracks are unsupported; see
[media support and limits](../docs/media.md).

The download is kept in memory only and is not recorded in the [task database](#database). The
command exits when the download completes or fails. To add a download to the Ketch app or server
instead, use [`ketch add`](#add-a-download).

| Option | Description |
|---|---|
| `--speed-limit <value>` | Limit download speed (e.g., `500k`, `1m`, `10m`) |
| `--priority <level>` | Set download priority: `low`, `normal`, `high`, `urgent` |
| `--max-concurrent <n>` | Max simultaneous downloads (default: 4) |
| `-H`, `--header <header>` | Send a request header, as `'Name: value'`; repeatable |
| `--user-agent <value>` | Send this `User-Agent` instead of `Ketch/<version>` |
| `--referer <url>` | Send this `Referer` |
| `--proxy <url>` | Download HTTP(S) through this proxy: `http://[user:pass@]host:port` or `socks5://[user:pass@]host:port` |
| `--proxy-bypass <hosts>` | Hosts `--proxy` leaves out, comma-separated (`*.lan,10.0.0.0/8`) |
| `--no-proxy` | Connect directly, whatever the configured or system proxy |
| `--help`, `-h` | Show help message |

Headers go with every request of the download. A redirect to another scheme, host or port keeps
only `User-Agent`, `Accept`, `Accept-Encoding`, `Accept-Language` and the origin of `Referer`;
cookies, `Authorization` and other headers stay with the site they were given for.

Without `--proxy` or `--no-proxy`, HTTP(S) downloads use `[download.proxy]` of the
[config file](#downloadproxy), which by default follows the `https_proxy`, `http_proxy`,
`all_proxy` and `no_proxy` environment variables. FTP and torrents always connect directly. See
[proxies](../docs/proxy.md).

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

# Through a SOCKS5 proxy, reaching the local network directly
ketch --proxy socks5://127.0.0.1:1080 --proxy-bypass '*.lan' https://example.com/file.zip

# FTP with credentials, and a magnet link into a directory, with debug logs
ketch ftp://user:secret@ftp.example.com/pub/file.iso
ketch -v "magnet:?xt=urn:btih:<info-hash>" ~/Downloads/
```

### Work on a running Ketch

These commands work on the downloads of a Ketch that is running, rather than starting a download
engine of their own: the Ketch app, a `ketch server`, or another device's server.

```bash
ketch add [options] <url> [destination]
ketch list [options]
ketch pause [options] <task-id>... | --all
ketch resume [options] <task-id>... | --all
ketch watch [options] [<task-id>...]
```

They use the first of:

1. the server `--server <url>` names, or the `KETCH_SERVER` environment variable, with the token
   from `--token` or `KETCH_API_TOKEN`. An address without a port uses 8642 for `http` and 443
   for `https`, as behind a reverse proxy; `nas:8642` means `http://nas:8642`.
2. the Ketch app running for your user. It opens a connection only this machine can use, with
   an access code made for that run, so it needs nothing set up; Sharing can stay off. The
   [portable Windows app](../docs/updates.md#the-portable-windows-app) is not found.
3. the [`ketch server`](#server) running on this machine, or a
   [`ketch mcp --standalone`](#mcp-server), with the token it uses.

Without any, they stop with `Ketch isn't running`.

Every one of them takes:

| Option | Description |
|---|---|
| `--server <url>` | Use the Ketch server at this address; also read from `KETCH_SERVER` |
| `--token <token>` | Its access token; also read from `KETCH_API_TOKEN` |
| `--help`, `-h` | Show the command's usage |

Results go to stdout and errors to stderr. The exit status is 0 on success, 1 on failure (no
Ketch running, a refusal, a task ID that matches nothing, a watched download that did not
complete, a lost connection) and 2 for invalid arguments.

A task ID can be shortened to any start only that task has, such as the 8 characters
`ketch list` shows.

#### Add a download

`ketch add` adds a download and prints its task ID, so scripts can follow it:

```bash
id=$(ketch add https://example.com/file.zip)
ketch watch "$id"
```

Without a destination, it goes to Ketch's download folder, or to the
[category folder](#downloadcategories) there that matches it. A destination works as for
[`ketch <url>`](#download-a-file): an existing directory, or a path ending in a separator, keeps
the file name from the source, and a relative path is taken from the current directory when Ketch
runs on this machine. Another device's paths are passed as they are; that server may keep
downloads to [its folders](#save-folders).

| Option | Description |
|---|---|
| `--speed-limit <value>` | Limit its speed (e.g., `500k`, `10m`) |
| `--priority <level>` | `low`, `normal`, `high` or `urgent` |
| `--connections <n>` | Connections to use; `0`, the default, uses Ketch's setting |
| `-H`, `--header <header>` | Send a request header, as `'Name: value'`; repeatable |
| `--user-agent <value>` | Send this `User-Agent` instead of `Ketch/<version>` |
| `--referer <url>` | Send this `Referer` |
| `--proxy <url>` | Download through this HTTP or SOCKS5 proxy instead of the instance's [proxy setting](#downloadproxy) |
| `--proxy-bypass <hosts>` | Hosts `--proxy` leaves out, comma-separated |
| `--no-proxy` | Connect directly, whatever the instance's proxy |
| `--idempotency-key <key>` | Running the command again with the same key adds the download once |
| `--json` | Print the task as JSON (see [Task JSON](#task-json)) instead of its ID |

`ketch add` sends each download with a request ID, so when the connection fails before Ketch
answers it can send it again without adding it twice; it tries three times. When it still cannot
tell whether the download was added, it says so and prints the request ID: run the command again
with `--idempotency-key <that ID>` to finish without a duplicate.

`--idempotency-key` makes retrying safe across runs, such as from a script or a job that may run
twice: the same key always sends the same request ID, so Ketch answers with the task it added
the first time while that task is still in its list. A UUID is used as it is, and any other text
turns into one. Using a key again for another download (another URL, destination, headers or
file selection) is refused. Older Ketch versions without request IDs refuse the option, and get
no retries without it.

#### List downloads

```bash
ketch list
```

```text
ID        STATE        DONE  SIZE     SPEED     NAME
3f2a9c1e  downloading  42%   1.2 GB   5.1 MB/s  ubuntu-24.04.iso
9c1e3f2a  queued #1    -     -        -         debian-12.iso
```

`--json` prints a JSON array of [task objects](#task-json) instead.

#### Pause and resume

```bash
ketch pause 3f2a 9c1e
ketch resume --all
```

Each task's new state is printed on a line of its own. `pause --all` pauses every waiting and
running download; `resume --all` resumes every paused one, but not those waiting for an urgent
download, which resume on their own.

#### Watch

`ketch watch` prints the downloads as JSON lines while they change, one [task object](#task-json)
per line with an `event`:

| `event` | When |
|---|---|
| `snapshot` | Each download as it is when `watch` starts |
| `added` | A download was added |
| `state` | A download's state, or its place in the queue, changed |
| `progress` | A running download made progress (a few times a second) |
| `removed` | A download was removed; the line has only `event` and `taskId` |

```bash
ketch watch | jq -r 'select(.event == "state" and .state == "completed") | .name'
```

With task IDs it follows those downloads only, and exits once each has finished: with status 0
when all completed, and 1 when one failed, was canceled or removed. Without, it runs until you
stop it. Either way it exits with status 1 when the connection to Ketch is lost, as when the app
quits.

#### Task JSON

`list --json`, `add --json` and `watch` describe a download with the fields `ketch mcp`'s tools
use: `taskId`, `name`, `url`, `destination`, `state` (`scheduled`, `queued`, `downloading`,
`paused`, `completed`, `failed` or `canceled`) and `createdAt`, and, as they apply,
`downloadedBytes`, `totalBytes`, `bytesPerSecond`, `percent` (0 to 1), `pauseReason`,
`preemptedBy`, `outputPath`, `completedAt`, `downloadTimeMs`, `error`, `queuePosition`,
`priority`, `speedLimit` (bytes per second) and `requestId`. Request headers are left out, as
they can hold credentials.

### Server

Start the Ketch daemon server with REST API, SSE event stream, and the bundled web UI. It
downloads HTTP(S), FTP/FTPS and BitTorrent sources, stores tasks in the
[task database](#database) and restores them when it starts, and announces itself on the local
network over mDNS unless `mdnsEnabled` is `false`. It listens on every interface by default and
then requires an [access token](#access-token).

Only one Ketch runs the downloads in the task database at a time, so the same downloads are never
resumed twice into the same files. `ketch server` stops with status 1 when the Ketch app is
running, or another `ketch server` or `ketch mcp --standalone` already is. Use the
[commands above](#work-on-a-running-ketch) and `ketch mcp`, which work through the one that
runs, or turn on **Settings → Sharing** in the app for other devices.

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
limits, priorities and the download config.

```bash
ketch mcp [options]
```

It works on the downloads of the Ketch that is running, found like the
[commands above](#work-on-a-running-ketch) find it: the Ketch app, the `ketch server` running on
this machine, or the server `--server` names. It looks for it when a tool is first called rather
than when it starts, and again once the connection is lost, as when the app restarts, so the MCP
client may start before Ketch does. While none runs, tools fail with `Ketch isn't running`.

`--standalone` runs the downloads itself instead, as earlier versions did, for a machine where no
Ketch app or server runs: it uses the same config file and [task database](#database) as
`ketch server`, restores saved tasks when it starts, and the commands above work through it
while it runs. Like `ketch server`, it refuses to start while another Ketch runs those downloads.

| Option | Description |
|---|---|
| `--server <url>` | Use the Ketch server at this address; also read from `KETCH_SERVER` |
| `--token <token>` | Its access token; also read from `KETCH_API_TOKEN` |
| `--standalone` | Run the downloads itself |
| `--config <path>` | Path to a TOML config file (with `--standalone`) |
| `--dir <path>` | Download directory (with `--standalone`; default: `~/Downloads`) |
| `--help`, `-h` | Show help message |

Each tool returns a JSON document as the text of its result. Parameters that have a default,
such as everything but `url` in `startDownload`, are optional in the tool's input schema.
`startDownload` takes a `requestId`, a UUID the agent makes up: calling it again with the same
`requestId` and arguments, such as after a timeout, returns the download it started instead of
adding another. An older Ketch without request IDs refuses it.

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
| `--provider <name>` | Search with a saved provider, by its id or name, or with a preset such as `deepseek` whose key is in the environment |
| `--model <id>` | Call this model |
| `-h`, `--help` | Show the command's usage |

Unknown options are an error rather than part of the query.

The command reads the `[ai]` section of the default [config file](#config-file-locations), which
the apps edit under Settings → Discover. Blank API keys are filled from each provider's
variable, such as `OPENAI_API_KEY`, `ANTHROPIC_API_KEY`, `GEMINI_API_KEY` or `DEEPSEEK_API_KEY`;
without an `[ai]` section, exporting one of them is enough. See [AI discovery](../docs/ai-discovery.md) for providers, web search keys, and how
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

The `server` and `mcp --standalone` commands support TOML configuration files. CLI flags always
take precedence over config file values. The download command reads only `[torrent]`, and
`ai-discover` only `[ai]`, from the default path.

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
# folder of the first category it matches, inside defaultDirectory.
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

#### `[download.proxy]`

| Key | Type | Default | Description |
|---|---|---|---|
| `mode` | string | `"system"` | `"system"`: the proxy environment variables, then the JVM's and system's settings; `"direct"`: no proxy; `"manual"`: `url` |
| `url` | string | *(none)* | `http://host:port` or `socks5://host:port`, without credentials; required for `"manual"` |
| `username` | string | *(none)* | Proxy user name (HTTP Basic or SOCKS5 authentication) |
| `password` | string | *(none)* | Password for `username`, stored in plain text |
| `bypass` | string[] | `[]` | Hosts `"manual"` reaches directly: domains (subdomains included), IP addresses, CIDR ranges, `<local>` or `*` |

Requests to this machine (`localhost`, `127.0.0.0/8`, `::1`) never use a proxy. `ketch server` and
`ketch mcp` apply the setting to every HTTP(S) download; see [proxies](../docs/proxy.md).

#### `[[download.categories]]`

Category folders sort the downloads that do not choose a folder (the server's clients send no
destination, or only a file name, as the browser extension does) into folders inside
`defaultDirectory`. Each `[[download.categories]]` table is one category; a download goes to
the first one it matches, and stays in `defaultDirectory` when it matches none.

| Key | Type | Default | Description |
|---|---|---|---|
| `folder` | string | *(required)* | Folder inside `defaultDirectory`, such as `"Video"`; `/` nests folders. Absolute paths and `..` fail to load |
| `extensions` | string[] | `[]` | File name extensions, such as `"mp4"` or `"tar.gz"` |
| `mimeTypes` | string[] | `[]` | Media types the server reports, such as `"application/pdf"`, or `"video/*"` for a whole kind |
| `hosts` | string[] | `[]` | Sites, such as `"github.com"`, which also covers its subdomains |

A download matches when its extension or media type is listed (either is enough; with neither
list set, any type matches) and, when `hosts` is set, it comes from one of them. A category
without any rule matches nothing. The apps also edit them under Settings → Downloads; on a
server, like the other download settings changed there, they last until it restarts.

#### `[torrent]`

| Key | Type | Default | Description |
|---|---|---|---|
| `trackers` | string[] | `[]` | Extra `http`, `https` or `udp` trackers that public torrents also announce to |
| `trackerList` | bool | `true` | Subscribe to the tracker lists at `trackerListUrls`, downloaded daily; their trackers are used after `trackers` |
| `trackerListUrls` | string[] | ngosang's [`trackers_best.txt`](https://github.com/ngosang/trackerslist) and XIU2's [`best.txt`](https://github.com/XIU2/TrackersListCollection) | `http` or `https` URLs of plain-text lists, one announce URL per line |

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

Tasks of `ketch server` and `ketch mcp --standalone` are stored in a SQLite database in the config
directory, which the Ketch app shares:

| Platform | Default path |
|---|---|
| macOS | `~/Library/Application Support/ketch/ketch.db` |
| Linux | `$XDG_CONFIG_HOME/ketch/ketch.db` (default: `~/.config/ketch/ketch.db`) |
| Windows | `%APPDATA%\ketch\ketch.db` |

BitTorrent DHT state is kept in the `torrent-state` folder of the same directory. While one of
the two runs, it holds `instance.lock` there, so no other opens the database, and describes
itself in `instance.json`, readable by your user only as it holds the access token, so the
[commands above](#work-on-a-running-ketch) can find it. The Ketch app is found through its own
`app.endpoint` instead.
