package com.linroid.ketch.endpoints

import com.linroid.ketch.api.ActiveConnections as ConnectionsSnapshot
import io.ktor.resources.Resource
import kotlinx.serialization.Serializable

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
 * - `PUT    /api/tasks/{id}/files`        — choose the files of a torrent task
 *
 * ### Torrents
 * - `GET /api/torrents/capabilities`  — what the torrent controller can do
 * - `GET /api/torrents/{id}`          — a torrent task's snapshot
 * - `GET /api/torrents/{id}/files`    — a page of its files (`?limit&cursor&sort&desc`)
 * - `PUT /api/torrents/{id}/selection` — choose its files, guarded by a revision
 * - `PUT /api/torrents/{id}/seeding`  — start or stop seeding it, guarded by a revision
 * - `GET /api/torrents/{id}/events`   — SSE stream of its snapshots
 *
 * ### Live connections
 * - `GET /api/connections`        — a snapshot of the live connections (`?limit=1..1024`)
 * - `GET /api/connections/events` — SSE stream of their snapshots (`?limit=1..1024`)
 *
 * ### Events (SSE)
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

      /** The files a torrent task downloads; takes a `FileSelectionRequest`. */
      @Serializable
      @Resource("files")
      data class Files(val parent: ById)
    }
  }

  /** Typed torrent controls, which servers listing `torrent.control` serve. */
  @Serializable
  @Resource("torrents")
  data class Torrents(val parent: Api = Api()) {

    @Serializable
    @Resource("capabilities")
    data class Capabilities(val parent: Torrents = Torrents())

    @Serializable
    @Resource("{id}")
    data class ById(
      val parent: Torrents = Torrents(),
      val id: String,
    ) {

      /**
       * One page of the task's files, sorted by [sort] (a `TorrentFileOrder` wire name, metainfo
       * order by default), reversed when [desc] is true.
       */
      @Serializable
      @Resource("files")
      data class Files(
        val parent: ById,
        val limit: Int? = null,
        val cursor: String? = null,
        val sort: String? = null,
        val desc: Boolean? = null,
      )

      /** Chooses the task's files; takes a `TorrentSelectionRequest`. */
      @Serializable
      @Resource("selection")
      data class Selection(val parent: ById)

      /** Starts or stops seeding the task; takes a `TorrentSeedingRequest`. */
      @Serializable
      @Resource("seeding")
      data class Seeding(val parent: ById)

      /** SSE stream: `snapshot` events, then `removed` or `error` before it closes. */
      @Serializable
      @Resource("events")
      data class Events(val parent: ById)
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
