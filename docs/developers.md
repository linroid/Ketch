# Developer guide

[![Maven Central](https://img.shields.io/maven-central/v/com.linroid.ketch/core?label=Maven%20Central&logo=apache-maven&logoColor=white)](https://central.sonatype.com/namespace/com.linroid.ketch)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.4.20-7F52FF.svg?logo=kotlin&logoColor=white)](https://kotlinlang.org)
[![Kotlin Multiplatform](https://img.shields.io/badge/Kotlin-Multiplatform-4c8dec?logo=kotlin&logoColor=white)](https://kotlinlang.org/docs/multiplatform.html)
[![Ktor](https://img.shields.io/badge/Ktor-3.5.2-087CFA.svg?logo=ktor&logoColor=white)](https://ktor.io)

The Ketch apps, the command line and the server all run on one Kotlin Multiplatform library,
published on Maven Central under `com.linroid.ketch`. You can use it to:

- **Embed the download engine** in an Android, iOS, JVM, Node.js or WASI app (`core`)
- **Control a Ketch server** from an Android, iOS or JVM app, or from the browser (`remote`)
- **Talk to a server from any language** over its REST API and server-sent events
- **Add protocols, storage or logging** by implementing small interfaces

`Ketch` (the in-process engine) and `RemoteKetch` (the client of a server) implement the same
`KetchApi`, so code written against one works with the other.

> [!NOTE]
> The library is still in release candidates; its API can change between them.

## Modules

| Artifact | What it does | Platforms |
|---|---|---|
| `api` | Public interfaces and models: `KetchApi`, `DownloadTask`, `DownloadRequest`, `DownloadState`, `Logger` | All |
| `core` | The download engine: segmented downloads, queue, scheduler, speed limits, resume | Android, iOS, JVM, JS (Node.js), WasmWasi |
| `ktor` | `KtorHttpEngine`, the default HTTP engine for `core` | Android, iOS, JVM |
| `sqlite` | `SqliteTaskStore`, which keeps tasks across restarts | Android, iOS, JVM |
| `ftp` | FTP and FTPS downloads | Android, iOS, JVM |
| `torrent` | BitTorrent and magnet downloads ([details](torrent.md)) | Android, iOS, JVM |
| `remote` | `RemoteKetch`, a `KetchApi` that controls a Ketch server | Android, iOS, JVM, WasmJs |
| `endpoints` | REST resource and wire model definitions shared by the server and `remote` | Android, iOS, JVM, WasmJs |
| `kermit` | `KermitLogger`, an optional [Kermit](https://github.com/touchlab/Kermit) logging backend | Android, iOS, JVM, JS, WasmJs |

The server (`library:server`) and the MCP server (`library:mcp`) are JVM modules used by the
command line and the apps; they are not published. [Installation](api.md#installation) shows the
version catalog setup and which source sets each module goes in.

## Quick start

Add the engine and an HTTP engine:

```kotlin
// build.gradle.kts
dependencies {
  implementation("com.linroid.ketch:core:<latest-version>")
  implementation("com.linroid.ketch:ktor:<latest-version>")
}
```

Create a `Ketch`, start it once, and download:

```kotlin
val ketch = Ketch(
  httpEngine = KtorHttpEngine(),
  config = DownloadConfig(
    maxConnectionsPerDownload = 4,
    maxConcurrentDownloads = 3,
  ),
)
ketch.start() // restores saved tasks

val task = ketch.download(
  DownloadRequest(
    url = "https://example.com/large-file.zip",
    destination = Destination("/path/to/downloads/"), // trailing slash: a directory
  )
)

task.state.collect { state ->
  when (state) {
    is DownloadState.Downloading -> {
      val p = state.progress
      println("${(p.percent * 100).toInt()}%  ${p.bytesPerSecond / 1024} KB/s")
    }
    is DownloadState.Completed -> println("Done: ${state.outputPath}")
    is DownloadState.Failed -> println("Error: ${state.error}")
    else -> {}
  }
}
```

Every task can be paused, resumed, canceled or removed, and its speed limit, connections,
priority and schedule changed while it runs. Tasks are kept in memory by default; pass
`taskStore = createSqliteTaskStore(driverFactory)` from `sqlite` to resume them after a restart.
Add `FtpDownloadSource()` or `TorrentDownloadSource()` to `additionalSources` for FTP or
BitTorrent.

The [API reference](api.md) covers configuration, priorities and scheduling, speed limits, errors
and logging; [multiple networks](multiple-networks.md) shows how to spread a download over several
network interfaces.

## Control a Ketch server

Run a server with `ketch server` ([CLI](../cli/README.md#server)) or turn on sharing in the apps,
then connect to it with `RemoteKetch` from `remote`. It mirrors the server's tasks and follows
their changes as they happen:

```kotlin
val api: KetchApi = RemoteKetch(
  host = "192.168.1.20",
  port = 8642,
  apiToken = "my-secret", // the server's access code, if it has one
)
api.start() // connects and keeps reconnecting
api.download(DownloadRequest(url = "https://example.com/file.iso"))
```

`RemoteKetch` works in the browser too (WasmJs), where `core` does not run; the Ketch web app is
built this way.

## REST API

A Ketch server answers JSON over HTTP on port 8642, under `/api`. With an access code set, send it
as `Authorization: Bearer <token>`.

| Method and path | What it does |
|---|---|
| `GET /api/status` | Name, version, uptime, configuration, download folder, free space and supported `features` |
| `PUT /api/config` | Replace the `DownloadConfig` |
| `GET`, `PUT /api/network-interfaces` | List the network interfaces, choose the ones to use |
| `POST /api/resolve` | Probe a URL: size, file name, resume support, files of a torrent |
| `POST /api/resolve/content` | Probe file content the client holds, such as a `.torrent` file |
| `GET /api/tasks` | List the tasks |
| `POST /api/tasks` | Add a download; the body is a `DownloadRequest` |
| `GET /api/tasks/{id}` | One task |
| `POST /api/tasks/{id}/pause`, `/resume`, `/cancel` | Control a task |
| `PUT /api/tasks/{id}/speed-limit`, `/priority`, `/connections` | Change a task while it runs; `connections` 0 means Auto |
| `DELETE /api/tasks/{id}?deleteFiles=true` | Remove a task, and its files if asked |
| `GET /api/events`, `/api/events/{id}` | Server-sent events: `task_added`, `task_removed`, `state_changed`, `progress` |
| `POST /api/pairing`, `GET`, `DELETE /api/pairing/{id}` | Ask the server's owner for the access code, poll for the answer, withdraw; no code needed |

```bash
curl -X POST http://localhost:8642/api/tasks \
  -H 'Content-Type: application/json' \
  -d '{"url": "https://example.com/file.zip", "priority": "HIGH"}'

curl -N http://localhost:8642/api/events
```

The pairing routes only exist on servers whose owner can answer, such as the apps' (mDNS TXT
`pairing=1`); `RemotePairing` in `remote` sends a request and waits for the answer.

The routes are defined once as Ktor resources in `endpoints` (`Api`), with the wire models in
`com.linroid.ketch.endpoints.model`. Without an access code the server only answers requests
addressed to this machine and refuses web pages on other origins; see
[the CLI's server options](../cli/README.md#server) for hosts, CORS and mDNS.

## Extend Ketch

Every part that varies by platform or use is an interface you can replace:

| Interface | Replace it to | Built in |
|---|---|---|
| `DownloadSource` | Add a protocol | HTTP(S); `FtpDownloadSource`, `TorrentDownloadSource` |
| `HttpEngine` | Use another HTTP client | `KtorHttpEngine`, `MultiNetworkHttpEngine` |
| `TaskStore` | Keep tasks somewhere else | `InMemoryTaskStore`, `SqliteTaskStore` |
| `Logger` | Send logs to your backend | `Logger.console()`, `Logger.combine()`, `KermitLogger` |
| `FileNameResolver` | Name files the source names nothing | `DefaultFileNameResolver` |

A `DownloadSource` says which URLs it takes (`canHandle`), probes them (`resolve`), downloads and
resumes them, and saves what it needs to resume as a `SourceResumeState`. Register it with
`Ketch(additionalSources = listOf(MySource()))`; the first source whose `canHandle` matches wins,
and HTTP(S) is the fallback. The [architecture](architecture.md#pluggable-components) shows how
the parts fit together.

To give AI agents control of a `KetchApi`, run `ketch mcp`
([MCP server](../cli/README.md#mcp-server)).

## How a download works

1. **Resolve** — Ask the source about the download (a HEAD request for HTTP): its size, whether
   it supports ranges, and its ETag and Last-Modified.
2. **Plan** — With range support, split the file into one segment per connection; otherwise use
   a single connection.
3. **Queue** — Start the download when the concurrency and per-host limits allow, by priority.
4. **Download** — Each segment downloads its byte range at once and writes at its own offset in
   the file. Changing the connection count splits or merges the remaining ranges as it runs.
5. **Throttle** — Token buckets hold each download to its own speed limit and all of them to the
   global one.
6. **Persist** — Segment progress is saved to the `TaskStore`, so pause and resume work across
   restarts.
7. **Resume** — Check that the file on the server is unchanged and the local file is intact, then
   continue. A server without range support, or content of unknown size, starts over.

The [architecture](architecture.md#download-pipeline) describes each step.

## Further reading

- [API reference](api.md) — Installation, `KetchApi`, configuration, errors and logging
- [Architecture](architecture.md) — Modules, the download pipeline, the server and the apps
- [Logging](logging.md) — Log format, levels and what never gets logged
- [BitTorrent](torrent.md) — Torrent support, configuration and verification
- [Multiple networks](multiple-networks.md) — Downloading over several network interfaces
- [AI discovery](ai-discovery.md) — The `ai:discover` module and its providers
- [Code style](development/code-style.md) and [testing](development/testing.md) — For
  contributors
