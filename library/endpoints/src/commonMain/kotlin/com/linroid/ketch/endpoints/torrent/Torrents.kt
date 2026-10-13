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
 * - `GET /api/torrents/{id}`           — a torrent task's snapshot
 * - `GET /api/torrents/{id}/files`     — a page of its files (`?limit&cursor&sort&desc`)
 * - `PUT /api/torrents/{id}/selection` — choose its files, guarded by a revision
 * - `PUT /api/torrents/{id}/seeding`   — start or stop seeding it, guarded by a revision
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
