package com.linroid.ketch.torrent

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import okio.Path
import platform.posix.S_IFLNK
import platform.posix.S_IFMT
import platform.posix.lstat
import platform.posix.stat

@OptIn(ExperimentalForeignApi::class)
internal actual fun torrentFileIdentity(path: Path): String? = memScoped {
  val attributes = alloc<stat>()
  if (lstat(path.toString(), attributes.ptr) != 0 ||
    attributes.st_mode.toInt() and S_IFMT == S_IFLNK) return@memScoped null
  "${attributes.st_dev}:${attributes.st_ino}"
}
