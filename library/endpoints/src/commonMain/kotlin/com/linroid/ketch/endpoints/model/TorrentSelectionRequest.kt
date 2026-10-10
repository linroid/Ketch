package com.linroid.ketch.endpoints.model

import com.linroid.ketch.api.torrent.TorrentCommandContext
import kotlinx.serialization.Serializable

/**
 * Request body of `PUT /api/torrents/{id}/selection`.
 *
 * @property fileIds the IDs of the files to download
 * @property context the idempotency key and the revision the change is based on
 */
@Serializable
data class TorrentSelectionRequest(
  val fileIds: Set<String>,
  val context: TorrentCommandContext,
)
