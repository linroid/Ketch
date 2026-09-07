package com.linroid.ketch.torrent

import okio.Path
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.Paths
import java.nio.file.attribute.BasicFileAttributes

internal expect fun platformTorrentFileIdentity(path: Path): String?

internal actual fun torrentFileIdentity(path: Path): String? = platformTorrentFileIdentity(path)

internal fun nioTorrentFileIdentity(path: Path): String? = try {
  Files.readAttributes(Paths.get(path.toString()), BasicFileAttributes::class.java,
    LinkOption.NOFOLLOW_LINKS).let { if (it.isSymbolicLink) null else it.fileKey()?.toString() }
} catch (_: NoSuchFileException) {
  null
}
