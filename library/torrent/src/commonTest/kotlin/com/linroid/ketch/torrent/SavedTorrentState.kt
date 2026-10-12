package com.linroid.ketch.torrent

import com.linroid.ketch.core.engine.SourceResumeState
import kotlinx.serialization.json.Json

/** This state as a task record saves it. */
internal fun TorrentResumeState.saved(): SourceResumeState =
  SourceResumeState(
    TorrentDownloadSource.TYPE,
    Json.encodeToString(TorrentResumeState.serializer(), this),
  )
