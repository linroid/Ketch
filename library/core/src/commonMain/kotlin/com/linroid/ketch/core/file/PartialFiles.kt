package com.linroid.ketch.core.file

import okio.Path
import okio.Path.Companion.toPath

/**
 * Added to the name of the file a download writes until it completes, such as
 * `ubuntu.iso.ketch` for `ubuntu.iso`, so an unfinished file never looks complete.
 */
internal const val PARTIAL_FILE_SUFFIX = ".ketch"

/**
 * The name a download of [fileName] writes under until it completes, or `null` when the suffix
 * would make it longer than [MAX_FILE_NAME_BYTES]; such a download writes to its final name.
 */
internal fun partialFileName(fileName: String): String? =
  (fileName + PARTIAL_FILE_SUFFIX).takeIf { it.encodeToByteArray().size <= MAX_FILE_NAME_BYTES }

/**
 * The partial file next to [outputPath], a file system path, or `null` when the download writes
 * to [outputPath] itself (see [partialFileName]).
 */
internal fun partialPathFor(outputPath: String): String? = partialPathFor(outputPath.toPath())

internal fun partialPathFor(outputPath: Path): String? {
  val name = partialFileName(outputPath.name) ?: return null
  val parent = outputPath.parent ?: return name
  return (parent / name).toString()
}

/**
 * Moves the finished [partial] file to [outputPath], atomically where the file system allows,
 * and returns where it is now.
 *
 * With [replace], as for a file the caller named, an existing [outputPath] is replaced. Otherwise
 * a file that appeared at [outputPath] while the download ran is kept, and the download takes the
 * first free `name (n).ext` beside it instead, reserved in [OutputPathReservations] for the
 * caller to [release][OutputPathReservations.release]. A file created in the instant between the
 * check and the move would still be replaced: okio has no move that refuses to.
 *
 * @param reserved called with a path reserved for the caller
 */
internal fun movePartialToFinal(
  partial: String,
  outputPath: String,
  replace: Boolean,
  reserved: (String) -> Unit,
): String {
  val fileSystem = platformFileSystem
  val target = if (replace || !fileSystem.exists(outputPath.toPath())) {
    outputPath
  } else {
    OutputPathReservations.reserveUnique(outputPath).also(reserved)
  }
  fileSystem.atomicMove(partial.toPath(), target.toPath())
  return target
}

/**
 * Creates the document a download of [fileName] writes to in [directory], an Android
 * `content://` folder, under its partial name ([partialFileName]), and returns its URI. `null`
 * when the platform has no such folders, or the name or the provider does not allow it (a
 * provider that cannot rename documents): the download then creates its document under the final
 * name with [resolveChildPath].
 */
internal expect fun createPartialDocument(directory: String, fileName: String): String?

/**
 * Renames [partial], a document [createPartialDocument] created, to its final name, the provider
 * picking a free one when the name is taken, and returns its URI, which may differ from
 * [partial]. `null` when it cannot be renamed; the document then keeps its partial name.
 */
internal expect fun finishPartialDocument(partial: String): String?
