package com.linroid.ketch.torrent

import okio.Path
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.Paths

internal actual fun torrentPublishCatalogObject(source: Path, target: Path): Boolean = try {
  Files.createLink(Paths.get(target.toString()), Paths.get(source.toString()))
  true
} catch (_: FileAlreadyExistsException) {
  false
}
