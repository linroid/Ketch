package com.linroid.ketch.torrent

internal actual fun createTorrentEngine(
  config: TorrentConfig,
): TorrentEngine {
  NativeLibraryLoader.ensureLoaded()
  return createLibtorrent4jEngine(config)
}
