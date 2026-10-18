# Ketch CLI

`ketch` is Ketch's command-line tool. Use it to download a file from a terminal, to control the
downloads of the Ketch app or server that is already running, to run Ketch as a server on a NAS
or headless machine, to give AI agents access to Ketch over MCP, and to find downloads with AI
discovery.

This page is for people who use the command. To run the server in a container, see the
[Docker guide](../docs/docker.md).

## Install

Install the native binary (no Java needed) with the install script:

```bash
curl -fsSL https://raw.githubusercontent.com/linroid/Ketch/main/install.sh | bash
```

The script installs `ketch` to `/usr/local/bin`, using `sudo` when that folder is not writable,
and puts the license notices in a `ketch-licenses` folder next to it. Two environment variables
change what it does:

| Variable | Effect |
|---|---|
| `KETCH_VERSION` | Install this release instead of the latest |
| `KETCH_INSTALL` | Install to this folder instead of `/usr/local/bin` |

```bash
curl -fsSL https://raw.githubusercontent.com/linroid/Ketch/main/install.sh \
  | KETCH_INSTALL="$HOME/.local/bin" bash
```

You can also download an archive from
[GitHub Releases](https://github.com/linroid/Ketch/releases/latest). Each is named
`ketch-cli-<version>-<os>-<arch>.tar.gz` (`.zip` on Windows) and holds the `ketch` binary and a
`licenses` folder.

| OS (`<os>`) | Architectures (`<arch>`) |
|---|---|
| `macos` | `x64`, `arm64` |
| `linux` | `x64`, `arm64` |
| `windows` | `x64` |

Once installed, [`ketch update`](#update) keeps the binary current.

## Quick start

Download a file into the current folder:

```bash
ketch https://example.com/file.zip
```

Add a download to the Ketch app (or server) that is running, then follow it:

```bash
id=$(ketch add https://example.com/file.zip)
ketch watch "$id"
```

Run Ketch as a server that the apps, the browser extension and the web UI connect to:

```bash
ketch server
```

Run `ketch` with no arguments, or with `--help`, to print the usage. Every command also takes
`--help` (or `-h`).

## Commands

| Command | What it does |
|---|---|
| [`ketch <url> [destination]`](#download-a-file) | Download one file and exit |
| [`ketch add`, `list`, `pause`, `resume`, `watch`](#work-on-a-running-ketch) | Control the downloads of the Ketch app or server that is running |
| [`ketch server`](#server) | Run the Ketch server: REST API, web UI and downloads |
| [`ketch mcp`](#mcp-server) | Serve Ketch to AI agents over the Model Context Protocol |
| [`ketch ai-discover <query>`](#ai-discovery) | Ask an LLM agent to find download links |
| [`ketch health`](#health-check) | Check whether the local server is ready |
| [`ketch update`](#update) | Replace the binary with the latest release |

### Global options

These flags can go anywhere on the command line and apply to every command. Logs go to the
console at info level by default, and only warnings and errors for downloads, `--list-files`,
`update` and the commands that work on a running instance; see [logging](../docs/logging.md) for
what each level shows.

| Option | Effect |
|---|---|
| `-v`, `--verbose` | Debug logging |
| `--debug` | Even more detail, including speed limiter waits and per-peer activity |

## Download a file

```bash
ketch [options] <url> [destination]
```

Downloads one URL, prints its progress, and exits when it completes or fails, ending with
`Download completed: <path>` and status 0, or `Download failed: <reason>` on stderr and status 1,
as when no source handles the link. It handles:

- HTTP(S) URLs
- FTP and FTPS URLs: `ftp://[user:password@]host[:port]/path`
- magnet links, and `.torrent` URLs or files
- finite HLS (`.m3u8`) and DASH (`.mpd`) streams, saved as one media file: the best quality
  unless `--files <id>` picks another of those `--list-files` lists, with separate fragmented MP4
  audio merged in and AES-128 HLS decrypted. Live streams and DRM are not supported; see
  [media support and limits](../docs/media.md).
- Metalink (`.meta4`, `.metalink`) URLs, downloaded from all their mirrors at once and checked
  against their hashes; see [Metalink](../docs/metalink.md).

Where the file goes:

- With no destination, it is saved in the current folder under the name the source gives it.
- An existing folder, or a path ending in `/` (or `\`), keeps the source's file name.
- Any other path, including a bare name such as `file.zip`, is the file path, relative to the
  current folder.
- Torrents follow the [torrent destination rules](../docs/torrent.md).

While a torrent makes no progress, its progress line says for how long (`stalled 2m 30s`), and a
line above it says why each time the reason changes, such as `[Stalled] No peers, still looking`;
see [stalls](../docs/torrent.md#when-a-download-stops-making-progress).

The download is kept in memory only: it is not added to the [task database](#database), and the
Ketch app does not see it. To add a download to the app or server instead, use
[`ketch add`](#add-a-download).

### Examples

```bash
# Save as file.zip in the current folder
ketch https://example.com/latest.zip file.zip

# Save to a specific path
ketch https://example.com/file.zip /tmp/file.zip

# Limit the speed and raise the priority
ketch --speed-limit 1m --priority high https://example.com/file.zip

# A file behind a sign-in, with the cookie and referring page the site expects
ketch -H 'Cookie: session=abc123' --referer https://example.com/downloads \
  https://example.com/files/report.pdf

# Through a SOCKS5 proxy, reaching the local network directly
ketch --proxy socks5://127.0.0.1:1080 --proxy-bypass '*.lan' https://example.com/file.zip

# FTP with credentials
ketch ftp://user:secret@ftp.example.com/pub/file.iso

# A magnet link into a folder, with debug logs
ketch -v "magnet:?xt=urn:btih:<info-hash>" ~/Downloads/

# A finite HLS stream
ketch 'https://example.com/video/index.m3u8'

# Fail unless the file has the published SHA-256
ketch --checksum sha256:9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08 \
  https://example.com/file.iso

# List a torrent's files, largest first, then download only two of them
ketch --list-files --sort size --reverse ./pack.torrent
ketch --files 0,3 ./pack.torrent ~/Downloads/
```

### Options

| Option | Description |
|---|---|
| `--speed-limit <value>` | Limit the speed, such as `500k` or `10m` (see [speed limit format](#speed-limit-format)) |
| `--priority <level>` | `low`, `normal` (default), `high` or `urgent` |
| `--max-concurrent <n>` | Maximum simultaneous downloads, above 0 (default: 4) |
| `-H`, `--header <header>` | Send a request header, as `'Name: value'`; repeatable |
| `--user-agent <value>` | Send this `User-Agent` instead of `Ketch/<version>` |
| `--referer <url>` | Send this `Referer` |
| `--proxy <url>` | Download HTTP(S) through this proxy: `http://[user:pass@]host:port` or `socks5://[user:pass@]host:port` |
| `--proxy-bypass <hosts>` | Hosts `--proxy` leaves out, comma-separated, such as `*.lan,10.0.0.0/8`; needs `--proxy` |
| `--no-proxy` | Connect directly, whatever the configured or system proxy |
| `--checksum <digest>` | Fail unless the finished file has this digest (see [Checksums](#checksums)) |
| `--files <ids>` | Download only these files of a torrent or other multi-file download: comma-separated IDs from `--list-files`, exactly one when the choices are variants |
| `--list-files` | List the files or variants of the URL with their IDs and sizes instead of downloading it |
| `--sort <order>` | Order of `--list-files`: `name` (natural order, so 2 comes before 10), `size` or `type` (by file extension); default: the download's own order |
| `--reverse` | Reverse the order of `--list-files` |

Invalid options, such as `--sort` without `--list-files`, `--list-files` together with `--files`
or `--checksum`, or `--proxy` together with `--no-proxy`, exit with status 2.

### Request headers

Headers go with every request of the download. A redirect to another scheme, host or port keeps
only `User-Agent`, `Accept`, `Accept-Encoding`, `Accept-Language` and the origin of `Referer`:
cookies, `Authorization` and other headers stay with the site they were given for.

### Listing a download's files

`--list-files` resolves the URL (for a magnet, that means finding its metadata from peers) and
prints one `ID  SIZE  PATH` row per file to stdout. When the choices are variants, of which only
one downloads, it says so on stderr, and `--files` then takes one ID. It exits with status 1 when
the URL cannot be resolved or has no files to choose.

### Checksums

With `--checksum`, Ketch hashes the file once the last byte is saved. It takes `sha256:<hex>`,
`sha1:`, `sha512:` or `md5:` digests, as well as base64 and bare hex digests. A match prints
`[Verified]`; a difference fails the download and deletes the file.

Without `--checksum`, an HTTP download is checked against a digest the server publishes for the
whole, unencoded file in a `Repr-Digest` or `Digest` header. Torrents check their own pieces and
refuse `--checksum`.

### Proxies

Without `--proxy` or `--no-proxy`, HTTP(S) downloads use [`[download.proxy]`](#downloadproxy) of
the config file, which by default follows the `https_proxy`, `http_proxy`, `all_proxy` and
`no_proxy` environment variables. FTP and BitTorrent always connect directly. See
[proxies](../docs/proxy.md).

### Settings it reads

The command reads the `[download.proxy]` and `[torrent]` sections of the default
[config file](#config-file-locations): public torrents also announce to the extra trackers and
tracker lists configured there. A config file that cannot be read is reported on stderr and
ignored.

## Work on a running Ketch

These commands control the downloads of a Ketch that is already running, instead of starting a
download engine of their own: the Ketch app, a `ketch server`, or another device's server.

```bash
ketch add [options] <url> [destination]
ketch list [options]
ketch pause [options] <task-id>... | --all
ketch resume [options] <task-id>... | --all
ketch watch [options] [<task-id>...]
```

### Which Ketch they use

They use the first of these:

1. The server that `--server <url>` (or the `KETCH_SERVER` environment variable) names, with the
   token from `--token` (or `KETCH_API_TOKEN`). An address without a port uses 8642 for `http`
   and 443 for `https`, as behind a reverse proxy; `nas:8642` means `http://nas:8642`.
2. The Ketch app running for your user. It opens a connection that only this machine can use,
   with an access code made for that run, so nothing needs setting up and Sharing can stay off.
   The [portable Windows app](../docs/updates.md#the-portable-windows-app) is not found.
3. The [`ketch server`](#server) or [`ketch mcp --standalone`](#mcp-server) running on this
   machine, with the token it uses.

When none is running, they stop with `Ketch isn't running`.

### Common options and exit status

| Option | Description |
|---|---|
| `--server <url>` | Use the Ketch server at this address; also read from `KETCH_SERVER` |
| `--token <token>` | Its access token; also read from `KETCH_API_TOKEN` |
| `-h`, `--help` | Show the command's usage |

Results go to stdout and errors to stderr. The exit status is:

- 0 on success
- 1 on failure: no Ketch running, a refusal, a task ID that matches nothing, a watched download
  that did not complete, or a lost connection
- 2 for invalid arguments

A task ID can be shortened to any prefix that only one task has, such as the 8 characters that
`ketch list` shows.

### Add a download

`ketch add` adds a download and prints its task ID, so scripts can follow it:

```bash
id=$(ketch add https://example.com/file.zip)
ketch watch "$id"
```

Without a destination, the download goes to Ketch's download folder, or to the matching
[category folder](#downloadcategories) inside it. A destination works as for
[`ketch <url>`](#download-a-file): an existing folder, or a path ending in a separator, keeps the
source's file name, and a relative path is taken from the current folder when Ketch runs on this
machine. Another device gets the path as it is; that server may limit downloads to
[its save folders](#save-folders).

| Option | Description |
|---|---|
| `--speed-limit <value>` | Limit its speed, such as `500k` or `10m` |
| `--priority <level>` | `low`, `normal` (default), `high` or `urgent` |
| `--connections <n>` | Connections to use; `0`, the default, uses Ketch's setting |
| `-H`, `--header <header>` | Send a request header, as `'Name: value'`; repeatable |
| `--user-agent <value>` | Send this `User-Agent` instead of `Ketch/<version>` |
| `--referer <url>` | Send this `Referer` |
| `--proxy <url>` | Download through this HTTP or SOCKS5 proxy instead of the instance's [proxy setting](#downloadproxy) |
| `--proxy-bypass <hosts>` | Hosts `--proxy` leaves out, comma-separated; needs `--proxy` |
| `--no-proxy` | Connect directly, whatever the instance's proxy |
| `--checksum <digest>` | Fail unless the finished file has this digest, such as `sha256:<hex>` |
| `--idempotency-key <key>` | Running the command again with the same key adds the download only once |
| `--json` | Print the task as JSON (see [Task JSON](#task-json)) instead of its ID |

`--proxy`, `--no-proxy`, `--checksum` and `--idempotency-key` need a Ketch version that supports
them; an older one is refused with a message to update it.

**Retries.** `ketch add` sends each download with a request ID, so when the connection fails, or
Ketch answers with a server error, before the download is confirmed, it can send it again without
adding it twice. It tries three times. When it still cannot tell whether the download was added,
it says so and prints the request ID: run the command again with `--idempotency-key <that ID>` to
finish without a duplicate.

**Idempotency keys.** `--idempotency-key` makes retrying safe across runs, such as from a script
or a job that may run twice. The same key always sends the same request ID, so Ketch answers with
the task it added the first time, as long as that task is still in its list. A UUID is used as
it is, and any other text is turned into one. Using a key again for a different download (another
URL, destination, headers or file selection) is refused.

### List downloads

```bash
ketch list
```

```text
ID        STATE        DONE  SIZE     SPEED     NAME
3f2a9c1e  downloading  42%   1.2 GB   5.1 MB/s  ubuntu-24.04.iso
9c1e3f2a  queued #1    -     -        -         debian-12.iso
```

Downloads are listed oldest first. Besides the [task states](#task-json), `STATE` can show
`queued #n` (its place in the queue), `waiting` (paused for an urgent download) and
`choose files` (waiting for a file selection). `--json` prints a JSON array of
[task objects](#task-json) instead.

### Pause and resume

```bash
ketch pause 3f2a 9c1e
ketch resume --all
```

Each task's new state is printed on a line of its own. `pause --all` pauses every waiting and
running download. `resume --all` resumes every paused download, except those waiting for an
urgent download, which resume on their own. Give task IDs or `--all`, not both.

### Watch

`ketch watch` prints downloads as JSON lines while they change, one [task object](#task-json)
per line with an `event` field:

| `event` | When |
|---|---|
| `snapshot` | Each download as it is when `watch` starts |
| `added` | A download was added |
| `state` | A download's state, or its place in the queue, changed |
| `progress` | A running download made progress |
| `removed` | A download was removed; the line has only `event` and `taskId` |

```bash
ketch watch | jq -r 'select(.event == "state" and .state == "completed") | .name'
```

| Option | Description |
|---|---|
| `--interval <duration>` | How often progress lines come, such as `500ms`, `2s` or a number of seconds (default: every second) |

With task IDs, it follows only those downloads and exits once each has finished: with status 0
when all completed, and 1 when one failed, was canceled or was removed. Without IDs, it runs until
you stop it. Either way, it exits with status 1 when the connection to Ketch is lost, as when the
app quits.

### Task JSON

`list --json`, `add --json` and `watch` describe a download with the same field names as
`ketch mcp`'s tools:

- Always: `taskId`, `name`, `url`, `destination`, `state` (`scheduled`, `queued`,
  `downloading`, `paused`, `completed`, `failed` or `canceled`) and `createdAt`.
- While downloading or paused: `downloadedBytes`, `totalBytes` and `percent` (0 to 1), plus
  `bytesPerSecond` while downloading.
- While paused: `pauseReason` (`user`, `preempted`, `shutdown`, `awaiting_file_selection` or
  `waiting_for_condition`) and, when an urgent download took its place, `preemptedBy`.
- Once completed: `outputPath`, `totalBytes`, `downloadTimeMs`, `completedAt` and
  `verifiedChecksum` (the digest the file matched, which may be the server's).
- When failed: `error`.
- When they apply: `queuePosition`, `priority` (when not normal), `speedLimit` (bytes per second),
  `requestId` and `checksum` (the one asked for).

Request headers are left out, as they can hold credentials.

## Server

```bash
ketch server [options]
```

`ketch server` runs Ketch as a long-lived server with a REST API, an event stream and the bundled
web UI, which the Ketch apps and the browser extension connect to. It:

- downloads HTTP(S), FTP/FTPS, BitTorrent and magnet links, finite HLS/DASH streams and Metalinks
- stores tasks in the [task database](#database) and restores them when it starts
- announces itself on the local network over mDNS, unless `mdnsEnabled` is `false`
- listens on every interface by default, and then requires an [access token](#access-token)
- runs the [hooks](../docs/hooks.md) of the config file

### Examples

```bash
# Every interface, with a token it creates on the first run
ketch server

# Another port and download folder
ketch server --port 9000 --dir /tmp/downloads

# Your own token, and only pages on localhost:3000 may call the API
ketch server --token my-secret --cors "localhost:3000"

# The same token, from the environment
KETCH_API_TOKEN=my-secret ketch server

# This machine only, without a token
ketch server --host 127.0.0.1 --no-token

# Let clients also save to two more folders
ketch server --allowed-dirs /srv/media,/srv/iso

# A config file, with the port overridden
ketch server --config /path/to/config.toml --port 9999

# Write a commented default config file
ketch server --generate-config

# A fixed BitTorrent port, to forward on the router
ketch server --torrent-port 51413

# Upload nothing to torrent peers
ketch server --torrent-upload off

# Configured through the environment, as in a container
KETCH_PORT=9000 KETCH_DOWNLOAD_DIR=/srv/downloads ketch server
```

### Options

| Option | Description |
|---|---|
| `--config <path>` | Read this TOML config file instead of the [default one](#config-file-locations) |
| `--generate-config` | Write a commented default config file to the default path and exit; fails if one exists |
| `--host <address>` | Address to listen on (default: `0.0.0.0`, every interface) |
| `--port <number>` | Port, 1-65535 (default: `8642`) |
| `--token <string>` | API access token; also read from `KETCH_API_TOKEN`. See [Access token](#access-token) |
| `--no-token` | Require no token, even when listening beyond this machine. Anyone who can reach the server can then use it |
| `--cors <origins>` | Comma-separated origins whose web pages may call the API (see [Web pages](#web-pages)) |
| `--allowed-hosts <names>` | Comma-separated extra `Host` names accepted without a token (see [Accepted hosts](#accepted-hosts)) |
| `--allowed-dirs <paths>` | Comma-separated folders, besides the download folder, that clients may save to and delete files from (see [Save folders](#save-folders)) |
| `--dir <path>` | Download folder (default: `~/Downloads`) |
| `--speed-limit <value>` | Global speed limit, such as `10m` or `500k` |
| `--torrent-port <number>` | Port BitTorrent peers connect to (TCP), which DHT also uses (UDP), 0-65535. `0`, the default, picks a free one at every start; set one to forward it on a router |
| `--torrent-upload <mode>` | What torrent peers may download: `off`, `while-downloading` (the default) or `seed`, as [`[torrent] upload`](#torrent) describes |

Options take precedence over [environment variables](#environment-variables), which take
precedence over the [config file](#configuration-file). When environment variables are set, the
server lists their names when it starts.

The server exits with status 1 when it cannot start (the config file cannot be read, the database
cannot be opened, another Ketch already runs the downloads) and 2 for invalid options or
environment variables.

### One Ketch at a time

Only one Ketch runs the downloads of a [task database](#database) at a time, so the same
downloads are never resumed twice into the same files. `ketch server` stops with status 1 when the
Ketch app is running, or when another `ketch server` or `ketch mcp --standalone` already is. Use
the [commands above](#work-on-a-running-ketch) and `ketch mcp`, which work through whichever
runs, or turn on **Settings → Sharing** in the app for other devices.

The other way round, the Ketch app opened while `ketch server` runs leaves the downloads to the
server: it shows them through the server, as a device named `ketch server`, and runs them itself
again once you stop the server and reopen the app.

### Access token

A server that listens beyond this machine (any `--host` except `127.0.0.1`, `localhost` or `::1`)
requires a bearer token, `Authorization: Bearer <token>`, on every API request. It uses the first
of:

1. `--token`
2. the `KETCH_API_TOKEN` environment variable
3. `apiToken` in the [config file](#configuration-file)
4. the token in the `api-token` file of the [config folder](#config-file-locations). The first
   run creates it, readable by your user only, and prints it:

   ```text
     Auth:          new token, saved to /home/me/.config/ketch/api-token
     Token:         3f2a9c1e…
   ```

   Later runs print where they read it from. `cat` the file to see it again, or delete it to get
   a new one.

Enter the token where the Ketch apps or the browser extension ask for an access code, or open the
web UI as `http://<address>:8642/#token=<token>`. An address that sends ten wrong tokens within a
minute gets `429 Too Many Requests` for any token until that minute ends.

A server on loopback takes no token unless you give one, since only this machine can reach it.
`--no-token` runs without one even beyond loopback, and prints a warning: anyone who can reach the
server can then add downloads, list, pause and remove tasks, and delete their files in the
[save folders](#save-folders).

### Save folders

Clients choose where downloads are saved, and removing a task with `deleteFiles=true` deletes its
files. Without a token, the server keeps clients to the download folder (`--dir` or
`defaultDirectory`) and the folders in `--allowed-dirs` or `allowedDirectories`:

- A new task's destination must be inside them. A relative path is taken from the download
  folder, and an existing file is never overwritten: the download gets a free name such as
  `file (1).zip`.
- A resumed task's `destination` must be an absolute file path inside them.
- `PUT /api/config` may only move the download folder to a folder inside them.
- `deleteFiles=true` only deletes files inside them; otherwise remove the task without it.

Anything else gets `403 Forbidden` with a `path_rejected` error. Paths are compared after `..` is
resolved and symbolic links are followed, so a link inside a folder that points elsewhere counts
as elsewhere.

With a token, clients may choose any folder, as the Ketch apps let you type any folder on another
device, but never one where a new file could take over your account: system folders, `~/Library`
on macOS, `AppData` on Windows, the config directory, and hidden files and folders such as
`~/.ssh`, unless they are inside the download folder. An existing file is still never
overwritten. Set `--allowed-dirs` or `allowedDirectories` to keep clients to these folders too.

File names a client gives are cleaned like the names sites suggest: only the part after the last
`/` or `\` is kept, and characters Windows forbids become `_`.

### Accepted hosts

Without an access token (on loopback, or with `--no-token`), the server only answers requests
whose `Host` header (ignoring the port) names this machine:

- `localhost`, `*.localhost`, `127.0.0.0/8` or `[::1]`
- an IP address of one of the machine's network interfaces, such as `192.168.1.20`
- the machine's host name, or its mDNS name `<host>.local`, such as `my-mac.local`
- a name or IP address listed in `--allowed-hosts` or `allowedHosts`

Anything else gets `403 Forbidden` with a `host_not_allowed` error. This stops DNS rebinding,
where a web page points its own domain at your machine to reach the API from the browser. The
Ketch apps connect to servers they discover by IP address, and the browser extension defaults to
`http://127.0.0.1:8642`, so neither needs configuration.

With a token, any `Host` is accepted, since a web page cannot learn the token. Keep a token when
the server is reached through another name, such as a reverse proxy or a DNS alias, rather than
listing every name.

### Web pages

A browser lets any web page send requests to the server, so the server checks where a browser
request comes from:

- **Without a token**, it refuses requests from web pages on another origin (a different host or
  port) with `403 Forbidden` and an `origin_not_allowed` error, and ignores `--cors`. Otherwise
  any site you visit could start downloads, list your tasks, and pause or cancel them. These
  still work: the web UI this server serves, browser extensions such as Ketch's own, and clients
  outside a browser, such as the Ketch apps and `curl`.
- **With a token**, `--cors` lists the origins whose pages may call the API: a full origin such
  as `http://localhost:3000`, a bare `localhost:3000` for both `http` and `https`, or `*` for any.
  Without `--cors` or `corsAllowedHosts`, any origin may, so the hosted web app can connect.
  Pages still need the token, which they cannot learn.

Behind a reverse proxy that rewrites the `Host` header, the web UI counts as another origin, so
keep a token there. The server the Ketch apps share follows the same rules.

### Request limits

JSON request bodies must be `application/json` and at most 1 MiB, or 32 MiB for a new task,
which can carry a resolved torrent. Uploaded `.torrent` content may be 16 MiB. Larger bodies get
`413 Payload Too Large`.

### Health

`GET /api/health` answers without a token: `200 {"status":"ready"}` once the server has restored
the tasks saved by earlier runs, and `503 {"status":"starting"}` while it does. The server starts
listening before it restores them, so a second server that cannot get the port never resumes the
same downloads. Meanwhile every other endpoint except pairing answers `503` with a `starting`
error and `Retry-After: 1`, so nothing is added while the tasks load; the apps reconnect on their
own. [`ketch health`](#health-check) asks this endpoint.

## MCP server

```bash
ketch mcp [options]
```

Runs Ketch as a [Model Context Protocol](https://modelcontextprotocol.io) server over stdio, so
AI agents can list, start, pause, resume, cancel and remove downloads, resolve URLs, choose the
files of torrents and other multi-file downloads, and change speed limits, priorities and the
download settings.

Register it with your MCP client, for example in Claude Desktop's `claude_desktop_config.json`:

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

### Which Ketch it uses

By default it works on the downloads of the Ketch that is running, found the same way as the
[commands above](#which-ketch-they-use): the server `--server` names, the Ketch app, or the
`ketch server` on this machine. It looks for Ketch when a tool is first called rather than when
it starts, and again after the connection is lost, as when the app restarts, so the MCP client
may start before Ketch does. While no Ketch runs, tools fail with `Ketch isn't running`.

`--standalone` runs the downloads itself instead, for a machine where no Ketch app or server
runs. It uses the same config file and [task database](#database) as `ketch server`, restores
saved tasks when it starts, and runs the config file's [hooks](../docs/hooks.md). While it runs,
the commands above and the Ketch app work through it. Like `ketch server`, it refuses to start,
with status 1, while another Ketch runs those downloads.

| Option | Description |
|---|---|
| `--server <url>` | Use the Ketch server at this address; also read from `KETCH_SERVER` |
| `--token <token>` | Its access token; also read from `KETCH_API_TOKEN` |
| `--standalone` | Run the downloads itself |
| `--config <path>` | TOML config file to read (only with `--standalone`) |
| `--dir <path>` | Download folder (only with `--standalone`; default: `~/Downloads`) |

`--server` and `--token` cannot be combined with `--standalone`, and `--config` and `--dir` need
it; such combinations, and unknown options, exit with status 2. With `--standalone`, a `--config`
file that cannot be read exits with status 1.

### Tools

The 14 tools are `listDownloads`, `getDownload`, `startDownload`, `pauseDownload`,
`resumeDownload`, `cancelDownload`, `removeDownload`, `resolveUrl`, `listDownloadFiles`,
`selectDownloadFiles`, `getStatus`, `setSpeedLimit`, `setPriority` and `updateConfig`. Each
returns a JSON document as the text of its result. Parameters that have a default, such as
everything but `url` in `startDownload`, are optional.

- `startDownload` takes a `requestId`, a UUID the agent makes up: calling it again with the same
  `requestId` and arguments, such as after a timeout, returns the download it started instead of
  adding another. An older Ketch without request IDs refuses it. It also takes a `checksum`, such
  as `sha256:<hex>`: the download fails, and its file is deleted, when the finished file differs.
  Task JSON then has `checksum`, and a completed task the `verifiedChecksum` it matched.
- `resolveUrl` lists a URL's `files`, each with its `id`, and the `selectionMode`: `MULTIPLE` for
  any subset, `SINGLE` for exactly one variant. It leaves out a torrent's metainfo.
- `startDownload` takes `fileIds`, comma-separated, to download only those files.
- `listDownloadFiles` lists a task's files with their `id`, `path`, `size` and whether they are
  `selected`, plus its `selectionMode`, up to `limit` (1 to 1000, default 100) at a time. Pass
  the `nextCursor` it returns as `cursor` for the next page. `sort` orders them `torrent`
  (default), `name` (natural order), `size`, `extension` or `selected` (selected first), and
  `descending` reverses the order.
- `selectDownloadFiles` changes a task's files at any time, from comma-separated IDs (one for a
  `SINGLE` choice). A task waiting for its files starts with them, while `resumeDownload`
  downloads its default: every file, or the first variant.
- A task's JSON counts its `selectedFiles` and `totalFiles`, and says whether a completed torrent
  is `seeding`.

There is no tool to start or stop seeding. `ketch mcp --standalone` seeds the torrents that finish
while it runs, as `[torrent] upload = "seed"` says, but unlike `ketch server` and the apps it
does not seed again, after a restart, the torrents that seeded before.

### Output and exit

Stdout carries only the MCP protocol; the banner and all logs go to stderr. The server exits once
the client closes stdin, after answering the requests it has already read, so it can also be
scripted:

```bash
printf '%s\n' '<request>' | ketch mcp
```

## AI discovery

```bash
ketch ai-discover <query> [options]
```

Asks an LLM agent to find download links for a natural-language query, and prints the agent's
summary and the candidates it found, with their URL, file name, size and confidence. Only the
results go to stdout; the banner, the model and query in use, the agent's steps, questions and
errors go to stderr.

```bash
ketch ai-discover "latest Ubuntu 24.04 ISO"
ketch ai-discover "ffmpeg release" --sites ffmpeg.org
ketch ai-discover --yes "blender 4.2 macOS" > results.txt
ketch ai-discover --provider deepseek "krita 5 windows"
```

| Option | Description |
|---|---|
| `--sites <domains>` | Comma-separated domains to limit discovery to, subdomains included; redirects to download hosts are followed (see [limiting discovery to websites](../docs/ai-discovery.md#limiting-discovery-to-websites)) |
| `--max-results <n>` | Maximum candidates to return, above 0 (default: 5) |
| `-y`, `--yes` | Open websites without asking |
| `--no-filter` | Show results the [content filter](../docs/ai-discovery.md#the-content-filter) hides |
| `--provider <name>` | Search with a saved provider, by its id or name, or with a preset such as `deepseek` whose key is in the environment |
| `--model <id>` | Call this model |

Words that are not options form the query; unknown options are an error rather than part of it.

### Setting up a provider

The command reads the `[ai]` section of the default [config file](#config-file-locations), which
the apps edit under **Settings → Discover**; it never writes the file. Blank API keys are filled
from each provider's environment variable, such as `OPENAI_API_KEY`, `ANTHROPIC_API_KEY`,
`GEMINI_API_KEY` or `DEEPSEEK_API_KEY`. Without an `[ai]` section, exporting one of them is
enough. Web search uses `BRAVE_SEARCH_API_KEY`, or `GOOGLE_SEARCH_API_KEY` with
`GOOGLE_SEARCH_CX`, when the settings name no search provider. See
[AI discovery](../docs/ai-discovery.md) for every provider and how settings and environment
variables combine.

### Page access

The command follows `[ai.access]`, which the apps edit under **Settings → Discover**. Unless its
`mode` is `"allow"` or the site is in `trustedSites`, it asks before the agent opens a website
(reads a page or checks a file, including a redirect to a new host):

```text
Allow Discover to open www.blender.org? https://www.blender.org/download/
  Discover says: Read the Blender download page
[y] allow  [s] allow blender.org for this run  [a] allow all  [n] deny:
```

- `y` allows the site for the rest of the run in the default `"ask-site"` mode, and only this
  request in `"ask"` mode.
- `s` allows the site and its subdomains for the rest of the run; `a` allows every website.
- `n`, an empty or unknown answer, or the end of input denies. After the end of input (Ctrl+D),
  every later request is denied without asking. A denied site is not asked about again.

Answers last for the run only. The command asks on the controlling terminal (`/dev/tty`), so
questions still reach you when stdout or stdin is redirected. On Windows it asks on the console,
and only while stdin and stdout are both the console: a redirected run there needs `--yes` or
`--sites`. Searches through the search provider never ask, and neither does a run limited with
`--sites`, as every host it may open is one you named.

Without a terminal, such as under cron or in CI, a run that may ask stops at once with status 1.
Pass `--yes`, limit it with `--sites`, or set `mode = "allow"` to run it there.

### Exit status

- 0 when the search ran, whether or not it found candidates
- 1 when it could not run (AI discovery is not configured, or there is no terminal to ask on) or
  failed (a provider or agent error)
- 2 for invalid arguments, including a `--provider` that names no provider

## Health check

```bash
ketch health [options]
```

Checks whether the `ketch server` on this machine is ready, as container health checks and
service managers do. It asks the server's [health endpoint](#health) at the address the running
server published in `instance.json` in the config folder, so options such as `--port` are
followed. Until the server has written that file, which it does once ready, `ketch health` asks
where the config file and the [environment variables](#environment-variables) say. A server on
every interface is asked on `127.0.0.1`.

| Option | Description |
|---|---|
| `--host <address>` | Address to ask instead |
| `--port <number>` | Port to ask instead, 1-65535 |
| `--config <path>` | The config file the server was started with |

It prints one line, `ready` or why not, and exits with:

- 0 once the server is ready
- 1 while the server restores its tasks, or when it cannot be reached within 5 seconds
- 2 for invalid options or environment variables

## Update

```bash
ketch update [options]
```

Replaces the `ketch` binary with the latest release from GitHub. The archive is checked against
the SHA-256 digest GitHub published for it before anything is replaced, and the license notices
next to the binary are updated too.

```bash
ketch update --check
sudo ketch update
ketch update --version 0.0.1-rc15
```

| Option | Description |
|---|---|
| `--check` | Only report whether a newer release exists |
| `--version <version>` | Install this release instead, even an older one, such as `0.0.1-rc15` |

- On macOS and Linux the new binary is renamed over the old one, so a `ketch server` that is
  running keeps working until you restart it.
- Windows cannot replace a running program, so the old binary is renamed to `ketch.exe.old` and
  removed the next time `ketch` runs.
- A binary in a folder you cannot write to, such as `/usr/local/bin`, needs `sudo ketch update`
  (on Windows, a terminal opened as administrator).
- Only the native binary updates itself. Builds run with `./gradlew :cli:run` or `installDist`
  can still run `ketch update --check`.
- Inside the [Docker image](../docs/docker.md#updating), `ketch update` refuses: pull the new
  image instead.

It exits with status 0 on success (or when Ketch is already up to date), and 1 when the update
fails or the options are invalid. See [updates](../docs/updates.md#the-command) for how it works.

## Exit codes

| Command | 0 | 1 | 2 |
|---|---|---|---|
| `ketch <url>` | Finished: completed or failed (read the last line), or `--list-files` listed the files | `--list-files` could not list files | Invalid options |
| `add`, `list`, `pause`, `resume`, `watch` | Success | Failure (see [above](#common-options-and-exit-status)) | Invalid arguments |
| `server` | Stopped normally | Could not start | Invalid options or environment variables |
| `mcp` | The client closed stdin | Could not start (`--standalone`) | Invalid options |
| `ai-discover` | The search ran | Not configured, no terminal, or failed | Invalid arguments |
| `health` | Ready | Starting or unreachable | Invalid options or environment variables |
| `update` | Updated or up to date | Failed, or invalid options | |

## Environment variables

| Variable | Read by | Effect |
|---|---|---|
| `KETCH_CONFIG_DIR` | All commands | Replaces the [config folder](#config-file-locations), which holds `config.toml`, the database, `api-token` and `torrent-state` |
| `KETCH_SERVER` | `add`, `list`, `pause`, `resume`, `watch`, `mcp` | Server to use, like `--server` |
| `KETCH_API_TOKEN` | Those commands, `server` | Access token, like `--token` |
| `OPENAI_API_KEY`, `ANTHROPIC_API_KEY`, `GEMINI_API_KEY`, `DEEPSEEK_API_KEY`, … | `ai-discover` | LLM provider keys, filling blank keys of the settings |
| `BRAVE_SEARCH_API_KEY`, `GOOGLE_SEARCH_API_KEY`, `GOOGLE_SEARCH_CX` | `ai-discover` | Web search keys |
| `https_proxy`, `http_proxy`, `all_proxy`, `no_proxy` | Commands that download | Proxy, when [`[download.proxy]`](#downloadproxy) follows the system (the default) |

`ketch server` (and `ketch health`, to find it) also reads these, which take precedence over the
config file; the server's options take precedence over both. Blank values count as unset, and a
value that cannot be used, such as `KETCH_PORT=http`, stops the server with status 2 and an error
naming the variable. The [Docker image](../docs/docker.md) is configured this way.

| Variable | Option or config key |
|---|---|
| `KETCH_NAME` | `name` |
| `KETCH_HOST` | `--host`, `host` |
| `KETCH_PORT` | `--port`, `port` |
| `KETCH_CORS` | `--cors`, `corsAllowedHosts`; comma-separated |
| `KETCH_ALLOWED_HOSTS` | `--allowed-hosts`, `allowedHosts`; comma-separated |
| `KETCH_ALLOWED_DIRS` | `--allowed-dirs`, `allowedDirectories`; comma-separated |
| `KETCH_MDNS` | `mdnsEnabled`; `true` or `false` (also `yes`/`no`, `on`/`off`, `1`/`0`) |
| `KETCH_DOWNLOAD_DIR` | `--dir`, `defaultDirectory` |
| `KETCH_SPEED_LIMIT` | `--speed-limit`, `speedLimit` |
| `KETCH_MAX_CONCURRENT_DOWNLOADS` | `maxConcurrentDownloads`; `0` for no limit |
| `KETCH_MAX_CONNECTIONS_PER_DOWNLOAD` | `maxConnectionsPerDownload`; at least 1 |
| `KETCH_MAX_CONNECTIONS_PER_HOST` | `maxConnectionsPerHost`; `0` for no limit |
| `KETCH_TORRENT_PORT` | `--torrent-port`, `[torrent] listenPort` |
| `KETCH_TORRENT_UPLOAD` | `--torrent-upload`, `[torrent] upload`; `off`, `while-downloading` or `seed` |

## Configuration file

`ketch server` and `ketch mcp --standalone` read a TOML config file: the one `--config` names, or
the default one when it exists. Command-line options always take precedence over it, and
`ketch server` reads [environment variables](#environment-variables) in between. The other
commands read only parts of the default file: `ketch <url>` its `[download.proxy]` and
`[torrent]` sections, and `ai-discover` its `[ai]` section. The CLI never writes the file, apart
from `--generate-config`.

### Config file locations

| Platform | Default path |
|---|---|
| macOS | `~/Library/Application Support/ketch/config.toml` |
| Linux | `$XDG_CONFIG_HOME/ketch/config.toml` (default: `~/.config/ketch/config.toml`) |
| Windows | `%APPDATA%\ketch\config.toml` |

When `KETCH_CONFIG_DIR` is set, it replaces this folder for the CLI and the desktop app, along
with `api-token`, the [database](#database) and `torrent-state`. The Docker image sets it to
`/config`.

The desktop app uses the same folder, so the CLI shares its settings and task database. The
[portable Windows app](../docs/updates.md#the-portable-windows-app) keeps both in its own `data`
folder instead, which the CLI does not read.

### Generating a config file

```bash
ketch server --generate-config
```

This writes a commented config file to the default location. It never overwrites an existing
file: delete that first to generate a new one.

### Config file format

Keys are camelCase. Unknown keys are ignored without a warning, so a misspelled key silently keeps
its default. A value that cannot be used, such as a negative count, makes the file fail to load.
`name` must come before the first `[table]` header.

```toml
# Instance name shown to clients and announced over mDNS
# name = "My Ketch"

[server]
host = "0.0.0.0"
port = 8642
# apiToken = "my-secret"
# mdnsEnabled = true
# corsAllowedHosts = ["localhost:3000"]
# allowedHosts = ["nas.example.com"]
# allowedDirectories = ["~/Media"]

[download]
# defaultDirectory = "~/Downloads"  # ~ is your home folder
# speedLimit = "unlimited"  # "unlimited", "10m" (MB/s), "500k" (KB/s)
maxConnectionsPerDownload = 4
maxConcurrentDownloads = 4  # 0 means unlimited
maxConnectionsPerHost = 16  # 0 means unlimited

# [download.proxy]
# mode = "manual"
# url = "socks5://127.0.0.1:1080"
# bypass = ["*.lan", "10.0.0.0/8"]

# [[download.categories]]
# folder = "Video"
# extensions = ["mp4", "mkv", "webm"]
# mimeTypes = ["video/*"]

# [torrent]
# trackers = ["udp://tracker.opentrackr.org:1337/announce"]
# listenPort = 6881
# upload = "while-downloading"

# [[hooks]]
# events = ["completed"]
# command = ["/usr/local/bin/ketch-done"]
```

The file `--generate-config` writes explains each setting in its comments. The sections below
list every key the CLI reads.

### Top level

| Key | Type | Default | Description |
|---|---|---|---|
| `name` | string | `"Ketch"` | Instance name shown to clients and announced over mDNS |

### `[server]`

| Key | Type | Default | Description |
|---|---|---|---|
| `host` | string | `"0.0.0.0"` | Address to listen on |
| `port` | int | `8642` | Port to listen on (1-65535) |
| `apiToken` | string | *(none)* | API access token; without one, a server beyond loopback uses `KETCH_API_TOKEN` or the saved `api-token` (see [Access token](#access-token)) |
| `corsAllowedHosts` | string[] | `[]` | Origins whose web pages may call the API (`["*"]` for any); needs a token, and any origin may with a token and no list (see [Web pages](#web-pages)) |
| `allowedHosts` | string[] | `[]` | Extra `Host` names or IP addresses accepted without a token (see [Accepted hosts](#accepted-hosts)) |
| `allowedDirectories` | string[] | `[]` | Folders, besides `defaultDirectory`, that clients may save to and delete files from; a leading `~` is your home folder (see [Save folders](#save-folders)) |
| `mdnsEnabled` | bool | `true` | Announce the server on the local network (`_ketch._tcp`) |

### `[download]`

| Key | Type | Default | Description |
|---|---|---|---|
| `defaultDirectory` | string | `~/Downloads` | Download folder; a leading `~` is your home folder |
| `speedLimit` | string | `"unlimited"` | Global speed limit, in the [speed limit format](#speed-limit-format) |
| `maxConnectionsPerDownload` | int | `4` | Connections (segments) per HTTP or FTP download; above 0 |
| `maxConcurrentDownloads` | int | `4` | Maximum simultaneous downloads (`0` = unlimited) |
| `maxConnectionsPerHost` | int | `16` | Maximum simultaneous downloads from one host (`0` = unlimited) |
| `retryCount` | int | `3` | Automatic retries in a row, without progress, after a failure that may pass |
| `unlimitedRetries` | bool | `false` | Retry such failures until the download is paused |
| `retryDelayMs` | long | `1000` | Delay before the first retry, doubled for each further one |
| `maxRetryDelayMs` | long | `60000` | Longest retry delay; each delay is randomized between half and all of it |
| `idleTimeoutMs` | long | `60000` | Retry a connection that receives no data this long (`0` = off) |
| `minSpeed` | long | `0` | Bytes per second a connection must average over 30 s, or it is retried (`0` = off) |
| `progressIntervalMs` | long | `200` | How often progress is reported; above 0 |
| `saveIntervalMs` | long | `5000` | How often download progress is saved; above 0 |
| `bufferSize` | int | `8192` | FTP read buffer size in bytes; above 0 |

The other counts and delays must not be negative.

### `[download.proxy]`

| Key | Type | Default | Description |
|---|---|---|---|
| `mode` | string | `"system"` | `"system"`: the proxy environment variables, then the system's settings; `"direct"`: no proxy; `"manual"`: `url` |
| `url` | string | *(none)* | `http://host:port` or `socks5://host:port`, without credentials; required for `"manual"` |
| `username` | string | *(none)* | Proxy user name (HTTP Basic or SOCKS5 authentication) |
| `password` | string | *(none)* | Password for `username`, stored in plain text |
| `bypass` | string[] | `[]` | Hosts `"manual"` reaches directly: domains (subdomains included), IP addresses, CIDR ranges, `<local>` or `*` |

Requests to this machine (`localhost`, `127.0.0.0/8`, `::1`) never use a proxy. The setting
applies to HTTP(S) downloads (including HLS and DASH) only; FTP and BitTorrent always connect
directly. See [proxies](../docs/proxy.md).

### `[[download.categories]]`

Category folders sort downloads that do not choose a folder (the client sends no destination, or
only a file name, as the browser extension does) into folders inside `defaultDirectory`. Each
`[[download.categories]]` table is one category. A download goes to the first category it
matches, and stays in `defaultDirectory` when it matches none.

| Key | Type | Default | Description |
|---|---|---|---|
| `folder` | string | *(required)* | Folder inside `defaultDirectory`, such as `"Video"`; `/` nests folders. Absolute paths and `..` fail to load |
| `extensions` | string[] | `[]` | File name extensions, such as `"mp4"` or `"tar.gz"` |
| `mimeTypes` | string[] | `[]` | Media types the server reports, such as `"application/pdf"`, or `"video/*"` for a whole kind |
| `hosts` | string[] | `[]` | Sites, such as `"github.com"`, which also covers its subdomains |

A download matches when its extension or media type is listed (either is enough; with neither
list set, any type matches) and, when `hosts` is set, it comes from one of them. A category
without any rule matches nothing. The apps also edit categories under **Settings → Downloads**;
on a server, changes made there, like the other download settings, last until it restarts.

### `[torrent]`

| Key | Type | Default | Description |
|---|---|---|---|
| `trackers` | string[] | `[]` | Extra `http`, `https` or `udp` trackers that public torrents also announce to |
| `trackerList` | bool | `true` | Subscribe to the tracker lists at `trackerListUrls`, downloaded daily; their trackers are used after `trackers` |
| `trackerListUrls` | string[] | ngosang's [`trackers_best.txt`](https://github.com/ngosang/trackerslist) and XIU2's [`best.txt`](https://github.com/XIU2/TrackersListCollection) | `http` or `https` URLs of plain-text lists, one announce URL per line |
| `listenPort` | int | `0` | Port `ketch server` accepts peers on (TCP) and runs DHT on (UDP), 0-65535; `0` picks a free one at every start. The apps and the other commands always pick one, so they never compete for it |
| `upload` | string | `"while-downloading"` | What torrents upload to peers: `while-downloading` (verified pieces, until each torrent finishes), `off` or `seed` (finished torrents too, also after `ketch server` restarts). Peers you upload to see your IP address. Unknown values load as `off`, so a typo never turns uploading on. `ketch server` also takes it from `--torrent-upload` and `KETCH_TORRENT_UPLOAD` |
| `uploadLimit` | string | `"unlimited"` | Upload cap shared by all torrents, in the [speed limit format](#speed-limit-format); a value that cannot be read loads as unlimited |
| `uploadWifiOnly` | bool | `true` | Read by the Android and iOS apps only, which pause uploads off Wi-Fi; the CLI and `ketch server` upload on any network |
| `encryption` | string | `"preferred"` | Protocol encryption with peers: `preferred` (encrypted first, plaintext when a peer refuses), `required` (encrypted only) or `off`. Unknown values load as `preferred` |
| `utp` | bool | `true` | Also reach peers over uTP (UDP) |
| `portMapping` | bool | `true` | Ask the home router (NAT-PMP, then UPnP) to forward the BitTorrent port |
| `localDiscovery` | bool | `true` | Find peers of public torrents on the local network (BEP 14) |

Every command uploads while it downloads unless the mode is `off`, which earlier versions used by
default: `upload = "off"` here, or for `ketch server` also `--torrent-upload off` or
`KETCH_TORRENT_UPLOAD=off`, which need no config file. Under `seed`, `ketch server` and
`ketch mcp --standalone` seed while they run; `ketch <url>` exits when its download finishes, so
it never seeds.

### `[[hooks]]`

Each `[[hooks]]` table runs a command or posts a webhook on download events. `ketch server` and
`ketch mcp --standalone` run them, reading them when they start; the other commands never do, and
no API can change them. See [automation hooks](../docs/hooks.md).

| Key | Type | Default | Description |
|---|---|---|---|
| `events` | string[] | `[]` | `added`, `started`, `completed`, `failed`, `canceled` and/or `removed` |
| `command` | string[] | `[]` | Program and arguments, never run through a shell; gets the event as JSON on stdin and in `KETCH_*` variables |
| `workingDirectory` | string | *(none)* | Folder the command runs in |
| `webhook` | string | *(none)* | `http` or `https` URL the JSON is posted to, when there is no `command` |
| `headers` | table | `{}` | Extra webhook headers, such as `{ Authorization = "Bearer …" }` |
| `secret` | string | *(none)* | Signs the webhook body: `X-Ketch-Signature: sha256=<HMAC-SHA256>` |
| `timeoutSeconds` | int | `30` | Time a command or webhook request may take |
| `origins`, `categories`, `hosts` | string[] | `[]` | Only downloads added from these origins, in these category folders or from these hosts |
| `name` | string | *(none)* | Label for the logs |
| `enabled` | bool | `true` | `false` keeps the hook without running it |

### Other sections

The `[ai]` section is described in [AI discovery](../docs/ai-discovery.md#configtoml). The apps
also keep `[[remotes]]`, `[appearance]`, `server.autoStart` and their own preferences in this
file; the CLI ignores them.

## Speed limit format

Speed limits accept these suffixes:

| Suffix | Unit | Example |
|---|---|---|
| `k` | KB/s | `500k` = 500 KB/s |
| `m` | MB/s | `10m` = 10 MB/s |
| *(none)* | bytes/s | `1048576` = 1 MB/s |

`unlimited` removes the limit.

## Database

`ketch server` and `ketch mcp --standalone` store their tasks in a SQLite database in the config
folder, which the Ketch app shares:

| Platform | Default path |
|---|---|
| macOS | `~/Library/Application Support/ketch/ketch.db` |
| Linux | `$XDG_CONFIG_HOME/ketch/ketch.db` (default: `~/.config/ketch/ketch.db`) |
| Windows | `%APPDATA%\ketch\ketch.db` |

A database SQLite cannot read is moved aside (the message on stderr says where) and an empty one
replaces it.

The same folder holds other files the CLI uses:

| File | Holds |
|---|---|
| `config.toml` | The [configuration](#configuration-file) |
| `api-token` | The server's [access token](#access-token), readable by your user only |
| `torrent-state/` | BitTorrent DHT state and downloaded tracker lists |
| `instance.lock` | Held by whichever of `ketch server`, `ketch mcp --standalone` or the Ketch app runs the downloads, so no other opens the database |
| `instance.json` | Where the running `ketch server` or `ketch mcp --standalone` listens, with its access token, so the [commands above](#work-on-a-running-ketch), `ketch health` and the Ketch app can find it; readable by your user only |

The Ketch app is found through its own `app.endpoint` file instead.

## Build & run

To build the CLI from source:

```bash
# Build the CLI (also builds the web UI that `ketch server` serves)
./gradlew :cli:build

# Run it through Gradle
./gradlew :cli:run --args="<arguments>"

# Or build the distribution and run its script
./gradlew :cli:installDist
./cli/build/install/cli/bin/cli <arguments>

# Or build the native binary (needs an Oracle GraalVM toolchain for JDK 21,
# or for the JDK -PnativeImageJdk names; releases use 25)
./gradlew :cli:nativeCompile
./cli/build/native/nativeCompile/ketch <arguments>
```

## See also

- [Docker](../docs/docker.md): run `ketch server` on a NAS
- [Automation hooks](../docs/hooks.md): commands and webhooks on download events
- [Updates](../docs/updates.md): how `ketch update` and the apps update
- [AI discovery](../docs/ai-discovery.md), [proxies](../docs/proxy.md),
  [torrents](../docs/torrent.md), [media](../docs/media.md) and [logging](../docs/logging.md)
