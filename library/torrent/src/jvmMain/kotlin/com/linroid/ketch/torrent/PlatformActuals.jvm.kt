package com.linroid.ketch.torrent

import com.linroid.ketch.torrent.engine.KotlinTorrentEngine
import com.linroid.ketch.torrent.engine.TorrentEngine

internal actual fun createTorrentEngine(config: TorrentConfig): TorrentEngine =
  KotlinTorrentEngine(config)
