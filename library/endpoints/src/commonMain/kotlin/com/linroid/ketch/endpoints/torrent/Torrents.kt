package com.linroid.ketch.endpoints.torrent

import com.linroid.ketch.endpoints.Api
import io.ktor.resources.Resource
import kotlinx.serialization.Serializable

/**
 * Typed torrent controls, which servers listing `torrent.control`
 * ([com.linroid.ketch.torrent.api.TorrentFeatures.CONTROL]) serve:
 *
 * - `GET /api/torrents/capabilities`   — what the torrent controller can do
 * - `GET /api/torrents/reachability`   — whether peers can connect to its engine
 * - `GET /api/torrents/settings`       — the settings every torrent runs with
 * - `PUT /api/torrents/settings`       — change some of them; takes a `TorrentSettingsChange`
 * - `GET /api/torrents/{id}`           — a torrent task's snapshot
 * - `GET /api/torrents/{id}/files`     — a page of its files (`?limit&cursor&sort&desc`)
 * - `PUT /api/torrents/{id}/selection` — choose its files, guarded by a revision
 * - `PUT /api/torrents/{id}/seeding`   — start or stop seeding it, guarded by a revision
 * - `PUT /api/torrents/{id}/sequential` — download its pieces in order or not
 * - `GET /api/torrents/{id}/export`    — its magnet link and info hashes (`?metainfo=true` adds
 *   its `.torrent` file)
 * - `GET /api/torrents/{id}/priority`, `PUT` — the files it downloads before its others
 * - `POST /api/torrents/{id}/recheck`  — check its files again and download what is missing
 * - `PUT /api/torrents/{id}/location`  — moves its files into another folder
 * - `GET /api/torrents/{id}/trackers`  — its own trackers and how each answers
 * - `PUT /api/torrents/{id}/trackers`  — adds and removes trackers (`TorrentTrackerEdit`)
 * - `GET /api/torrents/{id}/events`    — SSE stream of its snapshots
 */
@Serializable
@Resource("torrents")
data class Torrents(val parent: Api = Api()) {

  @Serializable
  @Resource("capabilities")
  data class Capabilities(val parent: Torrents = Torrents())

  @Serializable
  @Resource("reachability")
  data class Reachability(val parent: Torrents = Torrents())

  /** The engine's settings; `PUT` takes a `TorrentSettingsChange` and answers the new ones. */
  @Serializable
  @Resource("settings")
  data class Settings(val parent: Torrents = Torrents())

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

    /** Downloads the task's pieces in order or not; takes a `TorrentSequentialRequest`. */
    @Serializable
    @Resource("sequential")
    data class Sequential(val parent: ById)

    /**
     * The task's magnet link and info hashes as a `TorrentExport`, with its `.torrent` file as
     * Base64 when [metainfo] is true.
     */
    @Serializable
    @Resource("export")
    data class Export(val parent: ById, val metainfo: Boolean? = null)

    /** The files the task downloads first: a `TorrentPriorityRequest`, read or `PUT`. */
    @Serializable
    @Resource("priority")
    data class Priority(val parent: ById)

    /** Checks the task's files again; `POST` answers its snapshot. */
    @Serializable
    @Resource("recheck")
    data class Recheck(val parent: ById)

    /** Moves the task's files into another folder: `PUT` with a `TorrentRelocateRequest`. */
    @Serializable
    @Resource("location")
    data class Location(val parent: ById)

    /**
     * The task's own trackers in tier order, a list of `TorrentTracker`; `PUT` with a
     * `TorrentTrackerEdit` changes them and answers the list.
     */
    @Serializable
    @Resource("trackers")
    data class Trackers(val parent: ById)

    /** SSE stream: `snapshot` events, then `removed` or `error` before it closes. */
    @Serializable
    @Resource("events")
    data class Events(val parent: ById)
  }
}
