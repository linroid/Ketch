package com.linroid.ketch.torrent

internal actual fun createTorrentEngine(
  config: TorrentConfig,
): TorrentEngine = createLibtorrent4jEngine(config)
