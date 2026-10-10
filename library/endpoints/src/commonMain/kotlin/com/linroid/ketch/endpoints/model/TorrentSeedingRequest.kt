package com.linroid.ketch.endpoints.model

import com.linroid.ketch.api.torrent.TorrentCommandContext
import kotlinx.serialization.Serializable

/**
 * Request body of `PUT /api/torrents/{id}/seeding`.
 *
 * @property seeding whether to share the completed task's content with other peers
 * @property context the idempotency key and the revision the change is based on
 */
@Serializable
data class TorrentSeedingRequest(
  val seeding: Boolean,
  val context: TorrentCommandContext,
)
