package com.linroid.ketch.torrent

import com.linroid.ketch.core.engine.SourceState
import kotlinx.serialization.json.Json

/** This state as a task record saves it. */
internal fun TorrentSourceState.saved(): SourceState =
  SourceState(
    TorrentDownloadSource.TYPE,
    Json.encodeToString(TorrentSourceState.serializer(), this),
  )
