package com.linroid.ketch.torrent

import kotlinx.cinterop.ExperimentalForeignApi
import okio.IOException
import okio.Path
import platform.posix.EEXIST
import platform.posix.errno
import platform.posix.link

@OptIn(ExperimentalForeignApi::class)
internal actual fun torrentPublishCatalogObject(source: Path, target: Path): Boolean {
  if (link(source.toString(), target.toString()) == 0) return true
  val error = errno
  if (error == EEXIST) return false
  throw IOException("Catalog publication failed (errno $error)")
}
