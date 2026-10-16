package com.linroid.ketch.torrent.storage

import kotlinx.cinterop.ExperimentalForeignApi
import okio.IOException
import okio.Path
import platform.posix.EEXIST
import platform.posix.errno
import platform.posix.link

@OptIn(ExperimentalForeignApi::class)
internal actual fun torrentLinkFile(source: Path, target: Path): Boolean {
  if (link(source.toString(), target.toString()) == 0) return true
  val error = errno
  if (error == EEXIST) return false
  throw IOException("Hard link failed (errno $error)")
}
