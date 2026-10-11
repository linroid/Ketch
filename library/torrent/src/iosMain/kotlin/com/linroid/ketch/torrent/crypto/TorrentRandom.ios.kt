package com.linroid.ketch.torrent.crypto

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.posix.arc4random_buf

@OptIn(ExperimentalForeignApi::class)
internal actual fun torrentRandomBytes(size: Int): ByteArray = ByteArray(size).also { bytes ->
  if (size > 0) bytes.usePinned { arc4random_buf(it.addressOf(0), size.toULong()) }
}
