package com.linroid.ketch.torrent.storage

import okio.FileSystem

internal expect fun platformTorrentFileSystem(): FileSystem
internal actual val torrentSystemFileSystem: FileSystem = FileSystem.SYSTEM

internal actual val torrentFileSystem: FileSystem = platformTorrentFileSystem()
