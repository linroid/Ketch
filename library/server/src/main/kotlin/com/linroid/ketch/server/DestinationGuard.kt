package com.linroid.ketch.server

import com.linroid.ketch.api.Destination
import com.linroid.ketch.api.DownloadConfig
import com.linroid.ketch.api.DownloadRequest
import com.linroid.ketch.api.DownloadState
import com.linroid.ketch.api.DownloadTask
import com.linroid.ketch.api.FileConflict
import com.linroid.ketch.api.KetchApi
import com.linroid.ketch.core.file.DestinationPathPolicy
import com.linroid.ketch.core.file.PathRejectedException
import java.io.File

/**
 * Checks the paths API callers choose: where new downloads are saved, where a resumed task
 * continues, which folder becomes the download directory, and which files removing a task
 * deletes.
 *
 * When [confined], callers are kept to the download directory and [allowedDirectories].
 * Otherwise they may choose any folder, as owners pick folders on their other devices, except
 * places where a new file can take over the account: [protectedDirectories] and hidden entries,
 * such as `~/.ssh`, unless they lie in the download directory or [allowedDirectories]. Either
 * way a new download never replaces an existing file, unless existing files are overwritten
 * ([FileConflict.OVERWRITE]), which callers can only ask for when [authenticated], and which
 * only applies inside the download directory and [allowedDirectories] ([newDownload]).
 *
 * The download directory is read for every request, as it can change while the server runs.
 *
 * @param confined whether callers are kept to the download directory and [allowedDirectories]
 * @param authenticated whether callers proved they hold the API token
 * @param protectedDirectories folders refused when not [confined]
 * @param fileSystemRoots the file system roots callers may choose from when not [confined]
 */
internal class DestinationGuard(
  private val ketch: KetchApi,
  private val allowedDirectories: List<String>,
  private val confined: Boolean,
  private val authenticated: Boolean,
  private val protectedDirectories: List<String> = emptyList(),
  private val fileSystemRoots: List<String> = File.listRoots().map { it.path },
) {
  /**
   * [request] with its destination [confined][DestinationPathPolicy.confine]: inside the
   * folders callers may use, relative to the download directory, and never naming an existing
   * file.
   *
   * A request sent again with the [DownloadRequest.requestId] of a task keeps the destination
   * that task got, when it names the same place: the task's download may have created the file
   * since, which would give a new submission another name and make the two differ.
   *
   * Replacing existing files ([FileConflict.OVERWRITE]) lets a caller destroy what is there, so
   * it is held tighter than saving new files. A request that asks for it itself is refused
   * unless the caller is [authenticated], and unless it saves in the download directory or
   * [allowedDirectories]: elsewhere callers may add files, as owners do on their other devices,
   * but never replace one. A request that only follows [DownloadConfig.fileConflict], which the
   * owner set, is made to save beside existing files ([FileConflict.RENAME]) in such a folder
   * instead of being refused, also while the configuration renames, as a queued or scheduled
   * task reads it only when it starts. A file the caller names is given a free name as before,
   * unless the request asks to replace it and it lies in the download directory or
   * [allowedDirectories]: it then keeps its name, and the engine replaces the file once the
   * download completes, never one another download writes. Asked for elsewhere, the file gets
   * a free name and the request is made to rename.
   *
   * @throws PathRejectedException if the destination lies outside the folders
   * @throws OverwriteRejectedException if the request may not replace existing files
   */
  suspend fun newDownload(request: DownloadRequest): DownloadRequest {
    val status = ketch.status()
    val directory = status.system.downloadDirectory
    val asked = request.fileConflict == FileConflict.OVERWRITE
    if (asked && !authenticated) {
      throw OverwriteRejectedException("Replacing existing files needs the API token")
    }
    // A file the caller names keeps its name only for a request that may replace it.
    val named = if (asked) placed(request, directory, keepFileName = true) else null
    if (named != null && replaceableIn(named.destination, directory)) return named
    val placed = placed(request, directory, keepFileName = false)
    if (replaceableIn(placed.destination, directory)) return placed
    if (asked && placed.destination?.namesFile() != true) {
      throw OverwriteRejectedException(
        "Existing files are only replaced in the download folder and the allowed folders",
      )
    }
    // Whatever the configuration says now: a task reads it when it starts, which may be after
    // the owner switched to overwriting.
    return placed.copy(fileConflict = FileConflict.RENAME)
  }

  /**
   * [request] with its destination [confined][DestinationPathPolicy.confine]; with
   * [keepFileName], a file path keeps its name although a file is there.
   */
  private fun placed(
    request: DownloadRequest,
    directory: String,
    keepFileName: Boolean,
  ): DownloadRequest {
    val destination = request.destination
    if (destination == null || passesAsUri(destination.value)) return request
    val policy = policy(directory)
    val previous = request.requestId?.let { id ->
      ketch.tasks.value.find { it.request.requestId == id }?.request?.destination
    }
    if (previous != null && policy.confinesTo(destination, directory, previous)) {
      return request.copy(destination = previous)
    }
    val confined = policy.confine(destination, directory, keepFileName)
    return if (confined == destination) request else request.copy(destination = confined)
  }

  /**
   * Whether a download saved to [destination], already confined, may replace a file there: it
   * goes to the download [directory] (no destination, or a bare name), or to a folder inside it
   * or [allowedDirectories], or is a file in such a folder. A URI never has files replaced, so
   * it needs no check.
   */
  private fun replaceableIn(destination: Destination?, directory: String): Boolean {
    if (destination == null || destination.isBareName() || "://" in destination.value) return true
    val folder = if (destination.namesFolder()) {
      destination.value
    } else {
      File(destination.value).parent ?: return false
    }
    return DestinationPathPolicy(allowedDirectories + directory).contains(folder)
  }

  /**
   * [destination], the file a resumed task continues in, as an absolute path inside the folders.
   *
   * @throws PathRejectedException if it is relative, a folder, or outside the folders
   */
  suspend fun resumeDestination(destination: String): Destination {
    if (passesAsUri(destination)) return Destination(destination)
    val directory = ketch.status().system.downloadDirectory
    return Destination(policy(directory).confineFile(destination))
  }

  /**
   * Checks that [config] keeps the download directory inside the folders. Moving it to the
   * platform's default folder, by leaving [DownloadConfig.defaultDirectory] unset, needs that
   * folder to be inside too.
   *
   * Callers that are not [authenticated] cannot switch [DownloadConfig.fileConflict] to
   * [FileConflict.OVERWRITE]; one the owner set stays.
   *
   * @throws PathRejectedException if [config] moves it elsewhere
   * @throws OverwriteRejectedException if [config] starts replacing existing files unasked
   */
  suspend fun checkConfig(config: DownloadConfig) {
    val status = ketch.status()
    if (!authenticated && config.fileConflict == FileConflict.OVERWRITE &&
      status.config.fileConflict != FileConflict.OVERWRITE
    ) {
      throw OverwriteRejectedException("Replacing existing files needs the API token")
    }
    if (config.defaultDirectory == status.config.defaultDirectory) return
    val target = config.defaultDirectory
      ?: status.system.defaultDownloadDirectory
      ?: throw PathRejectedException("the default download folder", "is unknown")
    if (passesAsUri(target)) return
    if (!policy(status.system.downloadDirectory).contains(target)) {
      throw PathRejectedException(target)
    }
  }

  /**
   * Checks that a download's files may be moved into [directory], as a download may be saved
   * there.
   *
   * @throws PathRejectedException if the folder lies outside the folders
   */
  suspend fun checkMove(directory: String) {
    val status = ketch.status()
    if (passesAsUri(directory) || !policy(status.system.downloadDirectory).contains(directory)) {
      throw PathRejectedException(directory)
    }
  }

  /**
   * Checks that removing [task] with its files deletes nothing outside the folders.
   *
   * @throws PathRejectedException if its files lie outside the folders
   */
  suspend fun checkDeletion(task: DownloadTask) {
    val path = task.outputPath
      ?: (task.state.value as? DownloadState.Completed)?.outputPath
      ?: return
    if (passesAsUri(path)) return
    val directory = ketch.status().system.downloadDirectory
    if (!policy(directory).contains(path)) {
      throw PathRejectedException(path, "is outside the folders files can be deleted from")
    }
  }

  /**
   * Whether [value] is an Android `content://` URI, which may be used when not [confined]: the app
   * can only write to folders the owner granted it, wherever they are. Other URIs, such as
   * `file://`, would reach any path, so the folder checks apply to them.
   */
  private fun passesAsUri(value: String): Boolean =
    !confined && value.startsWith(CONTENT_URI_PREFIX, ignoreCase = true)

  private fun policy(downloadDirectory: String): DestinationPathPolicy {
    val folders = allowedDirectories + downloadDirectory
    return if (confined) {
      DestinationPathPolicy(folders)
    } else {
      DestinationPathPolicy(folders + fileSystemRoots, protectedDirectories, refuseHidden = true)
    }
  }

  private companion object {
    const val CONTENT_URI_PREFIX = "content://"
  }
}

// What a destination names, by the rules of file paths. Not `library:api`'s `isFile`, `isName`
// and `isDirectory`: those compile to a class per platform (`Destination_jvmKt`), which the
// Android app, where this server also runs, does not have.
private fun Destination.isBareName(): Boolean = '/' !in value && '\\' !in value

private fun Destination.namesFolder(): Boolean = value.endsWith('/') || value.endsWith('\\')

private fun Destination.namesFile(): Boolean = !isBareName() && !namesFolder()

/**
 * Thrown when an API caller asks for downloads to replace existing files
 * ([FileConflict.OVERWRITE]) where the server does not let callers do so.
 */
internal class OverwriteRejectedException(message: String) : IllegalArgumentException(message)
