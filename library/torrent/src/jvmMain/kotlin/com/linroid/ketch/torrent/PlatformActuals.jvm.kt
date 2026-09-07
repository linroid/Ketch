package com.linroid.ketch.torrent

internal actual fun createTorrentEngine(config: TorrentConfig): TorrentEngine =
  KotlinTorrentEngine(config)

internal actual fun platformTorrentFileSystem(): okio.FileSystem =
  when {
    System.getProperty("os.name").startsWith("Mac") -> MacTorrentFileSystem
    System.getProperty("os.name").startsWith("Windows") -> WindowsTorrentFileSystem
    else -> SafeTorrentFileSystem
  }
