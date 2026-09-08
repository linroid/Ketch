package com.linroid.ketch.torrent

import okio.Path

internal actual fun createSecureTorrentDirectory(path: Path, mustCreate: Boolean) =
  AndroidTorrentFileSystem.createDirectory(path, mustCreate)
