package com.linroid.ketch.torrent.storage

import okio.FileSystem

internal actual val torrentSystemFileSystem: FileSystem = FileSystem.SYSTEM

internal actual val torrentFileSystem: FileSystem = SafeTorrentFileSystem
