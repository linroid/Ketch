package com.linroid.ketch.endpoints.torrent

import kotlinx.serialization.Serializable

/**
 * Body and answer of `PUT /api/torrents/{id}/priority`.
 *
 * @property fileIds the files the task downloads before its others; empty for the usual order
 */
@Serializable
data class TorrentPriorityRequest(val fileIds: Set<String> = emptySet())
