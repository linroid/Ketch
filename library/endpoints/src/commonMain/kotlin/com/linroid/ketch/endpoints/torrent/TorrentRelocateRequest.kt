package com.linroid.ketch.endpoints.torrent

import kotlinx.serialization.Serializable

/**
 * Body of `PUT /api/torrents/{id}/location`.
 *
 * @property directory the folder, an absolute path on the server, the task's files move into
 */
@Serializable
data class TorrentRelocateRequest(val directory: String)
