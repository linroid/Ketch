package com.linroid.ketch.app.instance

import com.linroid.ketch.api.KetchFeatures
import com.linroid.ketch.torrent.api.TorrentFeatures

/**
 * What the engine inside this app lists in [com.linroid.ketch.api.KetchStatus.features]: it is
 * this build of Ketch, with every optional source and controller, so it supports everything.
 */
internal val EmbeddedFeatures: Set<String> = KetchFeatures.ALL + TorrentFeatures.ALL
