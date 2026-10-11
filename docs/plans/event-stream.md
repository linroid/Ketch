# One subscribed event stream

**Status:** In progress · 2026-10-11 · step 1 of 5 done

This plan replaces the three event streams a remote client opens today with one stream per device,
on which the client subscribes to what it shows. Progress has per-subscription intervals, and the
server computes and encodes each change once for every stream.

Paths: `server/` = `library/server/src/main/kotlin/com/linroid/ketch/server/`, `remote/` =
`library/remote/src/commonMain/kotlin/com/linroid/ketch/remote/`, `endpoints/` =
`library/endpoints/src/commonMain/kotlin/com/linroid/ketch/endpoints/`, `app/` =
`app/shared/src/commonMain/kotlin/com/linroid/ketch/app/`.

---

## 1. Why

| Stream | Opened | Carries |
|---|---|---|
| `/api/events` | from `RemoteKetch.start()` until `close()` | every task, every progress tick (`progressIntervalMs`, 200 ms by default), each `progress` with the whole segment list |
| `/api/torrents/{id}/events` | while `TorrentController.observe` is collected | one torrent's snapshots |
| `/api/connections/events` | while `activeConnections` is collected | live connections |

- The apps start every saved remote, so every paired device receives every task's progress five
  times a second, shown or not, in the foreground or not.
- Each `/api/events` client runs its own `combine` over every task (`server/api/EventRoutes.kt`)
  and encodes the same JSON again.
- `RemoteKetch` fetches `GET /api/tasks`, then opens the stream, and has to reconcile the two with
  `taskRevision` (`remote/RemoteKetch.kt`, `fetchAllTasks`).
- The inspector of a torrent opens a second and a third connection; browsers allow six per origin.

Inferring interest from flow collectors does not work: the apps collect every task's state all the
time. The Pulse bar sums every task's speed (`app/state/PulseState.kt`, `summarize`) and
`SpeedHistoryStore` samples every row once a second. Interest has to be declared, and the totals
need a topic of their own.

## 2. Decisions

| # | Decision | Why |
|---|---|---|
| D1 | One SSE stream, `GET /api/stream`; subscriptions replaced whole with `PUT /api/stream/{streamId}/subscription`. | SSE keeps bearer auth (browsers cannot set headers on a WebSocket), the startup gate and reverse proxies working unchanged. Subscriptions change only when the screen does. |
| D2 | The stream carries its own snapshots: `subscribed`, then a snapshot of each topic just added, then changes. A reconnect resyncs the same way; no `Last-Event-ID` replay. | Ordering is guaranteed by one writer; no snapshot/stream race. |
| D3 | Topics by cost: task lifecycle and state (default on), `stats` (one event per device), per-task progress, segments, torrents, connections (opt-in). | Steady state costs what is on screen. |
| D4 | Intervals round up to fixed steps (200 ms, 0.5, 1, 2, 5, 10, 30, 60 s); the shortest matching rule wins; state changes never wait. | Few timers, predictable load, clients can predict the applied value. |
| D5 | The server keeps one versioned frame per task, shared by all streams; each stream remembers the versions it sent and sends the difference. Encoded payloads are cached on the frame. | Compute and encode once; a slow client conflates per task instead of queueing, so no overflow handling is needed and memory per stream is bounded by the task count. |
| D6 | `streamId` is random, bound to the token that opened it, and dies with the connection. | A stream cannot be steered by another client. |
| D7 | `KetchFeatures.EVENT_STREAM` (`events.stream`) gates the client; the old endpoints stay, served from the same hub. | Old clients and old servers keep working. |
| D8 | Task data stays on the existing `StateFlow`s; interest is declared separately (`KetchApi.interest`), for as long as a flow is collected. In-process `Ketch` ignores it. | Screens keep reading tasks as they do; structured concurrency ends interest. |

## 3. Wire protocol (`endpoints/stream/`)

Event names, error codes, limits and interval steps live in `StreamEvents`.

```
event: hello            StreamHello {streamId, protocol, intervalsMs}
event: subscribed       StreamSubscribed {revision, subscription (as applied)}
event: tasks            TasksResponse        snapshot, sent when `tasks` turns on
event: task.added       TaskSnapshot
event: task.removed     StreamTaskRef {taskId}
event: task.state       TaskStateUpdate {taskId, state, queuePosition, request?}  request only when changed
event: task.progress    TaskProgressUpdate {taskId, progress}
event: task.segments    TaskSegmentsUpdate {taskId, segments}
event: stats            StreamStats {downloadBps, downloadedBytes, totalBytes, remainingBytes, downloading, unknownSizes}
event: torrent          TorrentSnapshot
event: torrent.removed  StreamTaskRef
event: connections      ActiveConnections
event: error            StreamError {error, message, topic?, taskId?}   no topic: the stream ends
```

Each event's `id` counts the stream's events.

```json
PUT /api/stream/{streamId}/subscription
{ "revision": 3,
  "tasks": true,
  "statsIntervalMs": 1000,
  "progress": [ { "intervalMs": 10000 },
                { "intervalMs": 500, "taskIds": ["a1", "b2"] } ],
  "segments": [ { "intervalMs": 1000, "taskIds": ["a1"] } ],
  "torrents": ["a1"],
  "connectionsLimit": 36 }
```

- `taskIds` absent means every task. A stream starts at revision 0 with `tasks` only.
- A revision not above the applied one changes nothing; the answer is the applied subscription.
- Limits: 16 rules per list, 1024 task ids per list, 16 torrents, `connectionsLimit` in 1..1024.
  Beyond them: `400 invalid_subscription`. Unknown or foreign stream: `404 unknown_stream`.
- What counts as a state change: anything but a downloading task's progress and segments, so a
  new request, queue position, pause reason or `Completed.sharing` is sent at once.
- `stats` carries only what progress decides; counts per state are known from lifecycle events.
- Progress is throttled on the leading edge: the first change after a quiet step goes out at once,
  then at most once per step. Tasks newly covered by a rule get their current progress and
  segments at once.

## 4. Server (`server/stream/`)

- `TaskTracker`: watches `KetchApi.tasks` while at least one stream is open. Seeds a `TaskFrame`
  per task synchronously, then follows each task's `state`, `requestState`, `segments` and
  `queuePosition`, bumping per-field versions and notifying streams of the task id.
- `TaskFrame`: immutable; the request as views show it (`TaskMapper.viewOf`), versions, and lazily
  encoded `task.added`, `task.state` (with and without request), `task.progress` and
  `task.segments` payloads.
- `SharedSource`: one upstream per key (torrent task id, connection limit) while any stream uses
  it, keeping the latest item; failures become `error` events of that topic.
- `EventStream`: the inbox (dirty task ids, latest subscription, latest torrent and connection
  items) under a lock, and a single writer that turns it into events: subscription changes and
  snapshots first, then lifecycle and state, torrents and connections, then due progress,
  segments and stats.
- `EventHub`: opens streams (at most 32), checks owners, validates and normalizes subscriptions.
- Keepalive: SSE heartbeat comments every 15 s (route, step 2).
- The old `/api/events` becomes a stream with `progress: [{intervalMs: 200}]` and segments of
  every task, mapped to `TaskEvent`s; `/api/torrents/{id}/events` and `/api/connections/events`
  become single-topic subscriptions (step 2).

## 5. Library and apps

- `library:api`: `KetchApi.interest(interest: Interest): Flow<Nothing>` (no-op by default) and
  `KetchApi.stats(interval): Flow<KetchStats>` (computed from tasks by default).
- `RemoteKetch`: an `EventStream` owning connection, reconnect with backoff, `hello`, and an
  interest registry merging the active interests into one subscription (debounced ~50 ms, sent
  again after every reconnect). `TorrentController.observe` and `activeConnections` become
  interests. Servers without `events.stream` get today's endpoints.
- Constructor settings: `progressInterval` (500 ms) and `backgroundProgressInterval` (10 s).

| Who | Interest |
|---|---|
| Connected | lifecycle, progress of every task every 10 s |
| Pulse bar, sidebar live line | `stats` every second |
| Rows on screen | progress every 500 ms |
| Open inspector | progress 250-500 ms, segments every second, torrent, its Connections tab |
| Connections grid | `connections` |
| App in the background, window hidden | lifecycle only |
| `ketch watch` | lifecycle, progress of its ids at `--interval` |

## 6. Steps

1. **Wire models and hub** (done) — `endpoints/stream/` models; `server/stream/` `TaskFrame`,
   `TaskTracker`, `SharedSource`, `EventStream`, `EventHub` with tests: snapshot then changes,
   state before progress, interval steps and leading-edge throttling, conflation of a slow
   stream, newly covered tasks, stale revisions, limits, owners, torrent and connection topics and
   their failures, lazy start and stop of the tracker.
2. **Routes** — `GET /api/stream`, `PUT /api/stream/{id}/subscription`, heartbeat, stream cap,
   `events.stream` feature; the old endpoints served from the hub.
3. **`RemoteKetch`** — `EventStream`, interest registry, `interest`/`stats`, fallback.
4. **Apps and CLI** — rows, inspector, Pulse on `stats`, background mode, `ketch watch
   --interval`, the background interval in Settings.
5. **Deprecation** — mark the old endpoints deprecated a few releases after step 4.
