# One subscribed event stream

**Status:** Implemented · 2026-10-11

This plan replaced the three event streams a remote client opened with one stream per device that
carries only what the client asks for. The goal is remote control from a phone: too many updates
cost mobile data and battery on the phone, and CPU on the server.

Paths: `api/` = `library/api/src/commonMain/kotlin/com/linroid/ketch/api/`, `server/` =
`library/server/src/main/kotlin/com/linroid/ketch/server/`, `remote/` =
`library/remote/src/commonMain/kotlin/com/linroid/ketch/remote/`, `endpoints/` =
`library/endpoints/src/commonMain/kotlin/com/linroid/ketch/endpoints/`, `app/` =
`app/shared/src/commonMain/kotlin/com/linroid/ketch/app/`.

---

## 1. Why

| Stream | Opened | Carried |
|---|---|---|
| `/api/events` | from `RemoteKetch.start()` until `close()` | every task, every progress tick (`progressIntervalMs`, 200 ms), each `progress` with the whole segment list |
| `/api/torrents/{id}/events` | while `TorrentController.observe` was collected | one torrent's snapshots |
| `/api/connections/events` | while `activeConnections` was collected | live connections |

- The apps start every saved remote, so every paired device received every task's progress and
  segments five times a second, shown or not, in the foreground or not. A torrent has one
  segment per file.
- Each `/api/events` client ran its own `combine` over every task and encoded the same JSON again.
- `RemoteKetch` fetched `GET /api/tasks` before opening the stream and reconciled the two with
  `taskRevision`.

## 2. Decisions

| # | Decision | Why |
|---|---|---|
| D1 | One SSE stream, `GET /api/stream`; the subscription is replaced whole with `PUT /api/stream/{streamId}/subscription`. | SSE keeps bearer auth (a browser cannot set headers on a WebSocket), the startup gate and reverse proxies working unchanged, and latency is the same for server-to-client traffic. Subscriptions change only when the screen does. |
| D2 | The stream carries its own snapshot: `hello`, `subscribed`, `tasks`, then changes. A reconnect resyncs the same way; no `Last-Event-ID` replay. | One writer orders everything; no snapshot/stream race. |
| D3 | Task lifecycle and state changes always come at once; progress of downloading tasks comes at one rate per client (`progressIntervalMs`); segments only for the tasks a client follows. | State changes are rare and the client needs them for notifications and badges; progress is about 160 bytes per downloading task; segments are the heavy part. |
| D4 | `KetchApi.subscribe(interest: Interest<T>, interval): Flow<T>`, abstract, replaces `activeConnections` and `TorrentController.observe`. `Interest` is a serializable interface naming its `valueSerializer`: `Segments`, `Stats` and `Connections` in `library:api`, `TorrentInterest` in `library:torrent-api`. A flow ends when what it follows is gone. | One typed entry point that modules extend, as they do `ControlKey`; the interests travel to the server as they are, and their values back, type-checked against the serializer. |
| D5 | Intervals round up to fixed steps (200 ms, 0.5, 1, 2, 5, 10, 30, 60 s); where callers follow the same thing, the shortest interval wins; the first update comes at once. | Few timers, predictable load. |
| D6 | The server keeps one versioned frame per task, shared by every stream, with payloads encoded once; each stream sends the difference to the versions it sent. | A slow client conflates instead of queueing; memory per stream is bounded by the task count. |
| D7 | The stream ID is random and bound to the credentials that opened it; it dies with the connection. A heartbeat comment every 15 s also frees the streams of clients that left. | A stream cannot be steered by another client, and none leaks. |
| D8 | `events.stream` in `/api/status` gates the client; older servers get `/api/events`. The old endpoints stay as they were, marked deprecated, rather than being rebuilt on the hub. | Old clients keep working with no risk to their tested behavior. |
| D9 | Interests other than segments share one channel: the client gives each an ID, the server runs `KetchApi.subscribe` once per interest and sends `interest` values, then `interest.end` or `interest.error`, under that ID. Segments are a task setting beside progress, not an interest on the wire. | A module's interest needs no server or client code, and an interest the server cannot serve fails instead of waiting. Segments stay task events, encoded once per task and written into the client's tasks; a generic value would resend every named task's segments on each change. |

## 3. Wire protocol (`endpoints/stream/`)

```
event: hello            StreamHello {streamId, protocol, intervalsMs}
event: subscribed       StreamSubscribed {revision, subscription}
event: tasks            TasksResponse        once, after the first subscribed
event: task.added       TaskSnapshot
event: task.removed     StreamTaskRef {taskId}
event: task.state       TaskStateUpdate {taskId, state, queuePosition, request?}  request only when changed
event: task.progress    TaskProgressUpdate {taskId, progress}
event: task.segments    TaskSegmentsUpdate {taskId, segments}
event: interest         InterestUpdate {id, value}           value: the interest's valueSerializer
event: interest.end     InterestRef {id}                     what it follows is gone
event: interest.error   InterestError {id, error, message}   unsupported, internal_error, torrent codes
event: error            StreamError {error, message}         the stream ends
```

```json
PUT /api/stream/{streamId}/subscription
{ "revision": 3,
  "progressIntervalMs": 1000,
  "segments": [ { "taskIds": ["a1"], "intervalMs": 500 } ],
  "interests": [
    { "id": 1, "interest": { "type": "stats" }, "intervalMs": 1000 },
    { "id": 2, "interest": { "type": "connections", "limit": 36 }, "intervalMs": 1000 },
    { "id": 3, "interest": { "type": "torrent", "taskId": "a1" }, "intervalMs": 1000 } ] }
```

- `StreamSerializers` registers the interests' `type`s; an unknown type decodes as
  `UnknownInterest`, which the server answers with `interest.error` `unsupported`, so newer
  clients work with older servers.
- An ID names one interest for the life of the stream; the client never gives it to another.
  `subscribed` comes before any event of the interests a subscription adds, and none follow for
  those it drops: the client applies a subscription when that event arrives and ignores events
  of IDs it does not name.
- Limits: 64 interests with distinct IDs, 64 segment entries naming 1024 task IDs; beyond them
  `400 invalid_subscription`. A closed or foreign stream is `404 unknown_stream`. A revision not
  above the applied one changes nothing.
- A state change is anything but a downloading task's progress and segments. Progress waits
  behind a state change the client has not had yet, which carries it.
- Updates are throttled on the leading edge: the first after a quiet interval goes out at once,
  then at most one per interval; an interest's end or failure never waits, and sends the value it
  held back first. Progress and segments a new subscription newly asks for come at once, as does
  the first value of a new interest.

## 4. Server (`server/stream/`, `server/api/StreamRoutes.kt`)

- `TaskTracker` follows `KetchApi.tasks` while any stream is open, keeping one `TaskFrame` per
  task, seeded synchronously so the first snapshot is complete.
- `TaskFrame`: per-field versions and lazily encoded `task.added`, `task.state` (with and without
  the request), `task.progress` and `task.segments` payloads.
- `SharedSource` runs one `KetchApi.subscribe` per interest while any stream follows it, keeping
  its latest value, encoded once, and how it ended (`TopicState`), so a stream that skips states
  still sends the last value before the end. Failures become `interest.error`s and the stream
  stays open.
- `StreamPlan` holds a progress step for every task, a step per task for segments, and each
  interest with its step, by ID.
- `EventStream`: an inbox under a lock and a single writer. `EventHub` caps streams at 32, binds
  them to their owner and validates subscriptions.

## 5. Clients

- `RemoteKetch(progressInterval = 1.seconds)`: `progressInterval` and `foreground` can change at
  any time. `StreamInterests` merges what callers hold into one subscription (debounced 50 ms,
  sent again after every reconnect): segments by step, every other interest once at its shortest
  interval, under an ID kept while anyone holds it. Every interest comes from the stream while it
  runs, decoded with its `valueSerializer`; otherwise segments, stats, connections and torrents
  come from their own endpoints, or from `tasks`, and others are unsupported.
  `RemoteTorrentController` adds the torrent's own rules on top: revisions, the tombstone and its
  error codes.
- `Ketch` answers segments and stats from its live tasks, connections from its registry, and
  module interests through `SourceExtension.subscribe`.

| Who | What it follows |
|---|---|
| Connected app | lifecycle and state, progress every second |
| Rows on screen (desktop, web) | segments every second, for the lane strips |
| Inspector | segments every 500 ms; a completed torrent's snapshots |
| Connections grid | `Interest.Connections` |
| App in the background | lifecycle and state only |
| `ketch watch` | lifecycle and state, progress every second or `--interval` |

## 6. Later

- Remove `/api/events`, `/api/torrents/{id}/events` and `/api/connections/events` a few releases
  after clients stop needing them.
- The desktop app could set `foreground = false` while its window is hidden; the tray and menu
  bar read progress, so it keeps the default for now.
