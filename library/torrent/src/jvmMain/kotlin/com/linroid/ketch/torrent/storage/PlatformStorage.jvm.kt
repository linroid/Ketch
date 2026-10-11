package com.linroid.ketch.torrent.storage

internal actual fun platformTorrentFileSystem(): okio.FileSystem =
  when {
    System.getProperty("os.name").startsWith("Mac") -> MacTorrentFileSystem
    System.getProperty("os.name").startsWith("Windows") -> WindowsTorrentFileSystem
    else -> SafeTorrentFileSystem
  }

internal actual fun platformTorrentFileIdentity(path: okio.Path): String? =
  if (System.getProperty("os.name").startsWith("Windows")) WindowsTorrentFileSystem.identity(path)
  else nioTorrentFileIdentity(path)
