package com.linroid.ketch.endpoints.model

import com.linroid.ketch.api.torrent.TorrentRevision
import kotlinx.serialization.Serializable

/**
 * Generic error response body.
 *
 * @property error a stable error code, such as `not_found`
 * @property message a description in English
 * @property revision the torrent task's current revision, for torrent commands that conflicted
 *   (`revision_conflict`); `null` otherwise
 */
@Serializable
data class ErrorResponse(
  val error: String,
  val message: String,
  val revision: TorrentRevision? = null,
)
