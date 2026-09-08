package com.linroid.ketch.torrent

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import okio.FileSystem
import platform.posix.arc4random_buf

internal actual val torrentFileSystem: FileSystem = FileSystem.SYSTEM

@OptIn(ExperimentalForeignApi::class)
internal actual fun torrentRandomBytes(size: Int): ByteArray = ByteArray(size).also { bytes ->
  if (size > 0) bytes.usePinned { arc4random_buf(it.addressOf(0), size.toULong()) }
}

internal actual fun createTorrentNetwork(): TorrentNetwork = PosixTorrentNetwork()
