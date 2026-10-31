package com.linroid.ketch.endpoints.torrent

import kotlinx.serialization.Serializable

/**
 * Body and answer of `PUT /api/torrents/{id}/sequential`.
 *
 * @property sequential whether the task's pieces download in order
 */
@Serializable
data class TorrentSequentialRequest(val sequential: Boolean)
