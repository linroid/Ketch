package com.linroid.ketch.torrent.storage

import okio.IOException
import okio.Path
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.Paths

internal actual fun torrentLinkFile(source: Path, target: Path): Boolean = try {
  Files.createLink(Paths.get(target.toString()), Paths.get(source.toString()))
  true
} catch (_: FileAlreadyExistsException) {
  false
} catch (e: UnsupportedOperationException) {
  throw IOException("Hard links are not supported", e)
}
