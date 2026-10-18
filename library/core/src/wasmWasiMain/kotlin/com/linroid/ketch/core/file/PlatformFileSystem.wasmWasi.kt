package com.linroid.ketch.core.file

import okio.FileHandle
import okio.FileMetadata
import okio.FileSystem
import okio.IOException
import okio.Path
import okio.Sink
import okio.Source
import okio.WasiFileSystem

/**
 * [WasiFileSystem], or [NoPreopensFileSystem] on a host that preopened no directories.
 *
 * `WasiFileSystem`'s initializer throws when there are no preopens, and every access after that
 * failure traps the whole module (it reads the half-built object), so it is touched only here.
 */
internal actual val platformFileSystem: FileSystem = try {
  WasiFileSystem.also { it.toString() }
} catch (_: IllegalStateException) {
  NoPreopensFileSystem
}

/**
 * The file system of a WASI host without preopened directories: like a path outside every
 * preopen, nothing exists and every operation fails with an [IOException].
 */
private object NoPreopensFileSystem : FileSystem() {
  private fun unavailable(path: Path): Nothing =
    throw IOException("no preopened directory holds $path")

  override fun canonicalize(path: Path): Path = unavailable(path)

  override fun metadataOrNull(path: Path): FileMetadata? = null

  override fun list(dir: Path): List<Path> = unavailable(dir)

  override fun listOrNull(dir: Path): List<Path>? = null

  override fun openReadOnly(file: Path): FileHandle = unavailable(file)

  override fun openReadWrite(file: Path, mustCreate: Boolean, mustExist: Boolean): FileHandle =
    unavailable(file)

  override fun source(file: Path): Source = unavailable(file)

  override fun sink(file: Path, mustCreate: Boolean): Sink = unavailable(file)

  override fun appendingSink(file: Path, mustExist: Boolean): Sink = unavailable(file)

  override fun createDirectory(dir: Path, mustCreate: Boolean) = unavailable(dir)

  override fun atomicMove(source: Path, target: Path) = unavailable(source)

  override fun delete(path: Path, mustExist: Boolean) {
    if (mustExist) unavailable(path)
  }

  override fun createSymlink(source: Path, target: Path) = unavailable(source)

  override fun close() = Unit

  override fun toString() = "NoPreopensFileSystem"
}
