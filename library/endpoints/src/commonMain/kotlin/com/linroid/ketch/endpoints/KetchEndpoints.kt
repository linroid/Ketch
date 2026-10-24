package com.linroid.ketch.endpoints

import com.linroid.ketch.endpoints.stream.StreamEvents
import io.ktor.resources.Resource
import kotlinx.serialization.Serializable
import com.linroid.ketch.api.ActiveConnections as ConnectionsSnapshot

/**
 * Type-safe Ktor Resource definitions for the Ketch REST API.
 *
 * These resources are shared between the server and remote client
 * to ensure endpoint paths are defined in a single place.
 *
 * ## Endpoints
 *
 * ### Server
 * - `GET  /api/status`       — server health and task counts
 * - `PUT  /api/config`       — update download configuration
 * - `GET  /api/network-interfaces` — discover interfaces and current selection
 * - `PUT  /api/network-interfaces` — select interfaces for new HTTP requests
 * - `POST /api/resolve`      — resolve URL metadata without downloading
 * - `POST /api/resolve/content` — resolve metadata from uploaded file bytes
 *
 * ### Tasks
 * - `GET    /api/tasks`                  — list all tasks
 * - `POST   /api/tasks`                  — create a new download
 * - `GET    /api/tasks/{id}`             — get task by ID
 * - `POST   /api/tasks/{id}/pause`       — pause a download
 * - `POST   /api/tasks/{id}/resume`      — resume a download
 * - `POST   /api/tasks/{id}/cancel`      — cancel a download
 * - `DELETE /api/tasks/{id}`             — remove a task
 * - `PUT    /api/tasks/{id}/speed-limit`  — set task speed limit
 * - `PUT    /api/tasks/{id}/priority`     — set task priority
 * - `PUT    /api/tasks/{id}/connections`  — set task connections
 * - `PUT    /api/tasks/{id}/files`        — choose the files of a task with several
 *
 * Torrent controls (`/api/torrents`) are defined in
 * [Torrents][com.linroid.ketch.endpoints.torrent.Torrents].
 *
 * ### Live connections
 * - `GET /api/connections`        — a snapshot of the live connections (`?limit=1..1024`)
 * - `GET /api/connections/events` — SSE stream of their snapshots (`?limit=1..1024`)
 *
 * ### Event stream (SSE), on servers listing [StreamEvents.FEATURE]
 * - `GET /api/stream`                        — the subscribed event stream
 * - `PUT /api/stream/{id}/subscription`      — what the stream sends
 *
 * ### Events (SSE), deprecated: use the event stream
 * - `GET /api/events`       — SSE stream of all task events
 * - `GET /api/events/{id}`  — SSE stream for a specific task
 *
 * ### Pairing (without the access token)
 * - `POST   /api/pairing`      — ask the server's owner for its access token
 * - `GET    /api/pairing/{id}` — whether the owner answered
 * - `DELETE /api/pairing/{id}` — withdraw the request
 *
 * ### Health (without the access token)
 * - `GET /api/health` — 200 once the server serves its saved tasks, 503 until then
 */
@Serializable
@Resource("/api")
class Api {

  @Serializable
  @Resource("status")
  data class Status(val parent: Api = Api())

  /** Whether the server is ready, which health checks such as Docker's ask without the token. */
  @Serializable
  @Resource("health")
  data class Health(val parent: Api = Api())

  @Serializable
  @Resource("config")
  data class Config(val parent: Api = Api())

  /**
   * The instance's IP to country database (`CountryDatabaseStatus`), served where the status
   * lists `net.countries`.
   */
  @Serializable
  @Resource("countries")
  data class Countries(val parent: Api = Api()) {
    /** `POST` starts downloading it and answers the status. */
    @Serializable
    @Resource("download")
    data class Download(val parent: Countries = Countries())
  }

  /**
   * The instance's speed modes, served where the status lists `speed.modes`: `GET` answers the
   * `SpeedModeState`, `PUT` applies a `SpeedModeChange` and answers the state that results.
   */
  @Serializable
  @Resource("speed")
  data class Speed(val parent: Api = Api())

  @Serializable
  @Resource("network-interfaces")
  data class NetworkInterfaces(val parent: Api = Api())

  @Serializable
  @Resource("resolve")
  data class Resolve(val parent: Api = Api()) {

    /** Resolves the request body, e.g. `.torrent` bytes, as file content. */
    @Serializable
    @Resource("content")
    data class Content(
      val parent: Resolve = Resolve(),
      val fileName: String? = null,
    )
  }

  @Serializable
  @Resource("tasks")
  data class Tasks(val parent: Api = Api()) {

    @Serializable
    @Resource("{id}")
    data class ById(
      val parent: Tasks = Tasks(),
      val id: String,
    ) {

      @Serializable
      @Resource("pause")
      data class Pause(val parent: ById)

      @Serializable
      @Resource("resume")
      data class Resume(val parent: ById, val destination: String? = null)

      @Serializable
      @Resource("cancel")
      data class Cancel(val parent: ById)

      @Serializable
      @Resource("speed-limit")
      data class SpeedLimit(val parent: ById)

      @Serializable
      @Resource("priority")
      data class Priority(val parent: ById)

      @Serializable
      @Resource("connections")
      data class Connections(val parent: ById)

      /** The files a task with several files downloads; takes a `FileSelectionRequest`. */
      @Serializable
      @Resource("files")
      data class Files(val parent: ById)
    }
  }

  /**
   * The live connections of every task, which servers listing `net.activeConnections` serve: a
   * `com.linroid.ketch.api.ActiveConnections` snapshot holding at most [limit] of them, in
   * 1..1024; another limit is `400` `invalid_limit`.
   */
  @Serializable
  @Resource("connections")
  data class ActiveConnections(
    val parent: Api = Api(),
    val limit: Int = ConnectionsSnapshot.DEFAULT_LIMIT,
  ) {

    /**
     * SSE stream of the snapshots, named in [ConnectionEvents], with the parent's `limit`. A
     * server streams to a few clients at once and answers `429` `too_many_streams` beyond them.
     */
    @Serializable
    @Resource("events")
    data class Events(val parent: ActiveConnections = ActiveConnections())
  }

  /**
   * The subscribed event stream, named in [StreamEvents]: one SSE stream per client, which
   * starts with the task lifecycle and sends what its subscription asks for. A server streams
   * to a few clients at once and answers `429` `too_many_streams` beyond them.
   */
  @Serializable
  @Resource("stream")
  data class Stream(val parent: Api = Api()) {

    @Serializable
    @Resource("{id}")
    data class ById(
      val parent: Stream = Stream(),
      val id: String,
    ) {

      /**
       * `PUT` a [StreamSubscription][com.linroid.ketch.endpoints.stream.StreamSubscription] to
       * replace what the stream sends; answers a
       * [StreamSubscribed][com.linroid.ketch.endpoints.stream.StreamSubscribed]. A stream that
       * closed is `404` `unknown_stream`, a subscription beyond the limits `400`
       * `invalid_subscription`.
       */
      @Serializable
      @Resource("subscription")
      data class Subscription(val parent: ById)
    }
  }

  /**
   * Task events of the first protocol, every task's progress as often as it changes.
   * Deprecated: servers listing [StreamEvents.FEATURE] serve [Stream], which sends only what a
   * client asks for. Kept for older clients.
   */
  @Serializable
  @Resource("events")
  data class Events(val parent: Api = Api()) {

    @Serializable
    @Resource("{id}")
    data class ById(
      val parent: Events = Events(),
      val id: String,
    )
  }

  /** Pairing requests, which servers that can ask their owner take without the token. */
  @Serializable
  @Resource("pairing")
  data class Pairing(val parent: Api = Api()) {

    @Serializable
    @Resource("{id}")
    data class ById(
      val parent: Pairing = Pairing(),
      val id: String,
    )
  }
}
