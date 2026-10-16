package com.linroid.ketch.server

import com.linroid.ketch.api.Destination
import com.linroid.ketch.api.DownloadConfig
import com.linroid.ketch.api.DownloadRequest
import com.linroid.ketch.api.DownloadState
import com.linroid.ketch.api.DownloadTask
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
 * way a new download never replaces an existing file.
 *
 * The download directory is read for every request, as it can change while the server runs.
 *
 * @param confined whether callers are kept to the download directory and [allowedDirectories]
 * @param protectedDirectories folders refused when not [confined]
 * @param fileSystemRoots the file system roots callers may choose from when not [confined]
 */
internal class DestinationGuard(
  private val ketch: KetchApi,
  private val allowedDirectories: List<String>,
  private val confined: Boolean,
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
   * @throws PathRejectedException if the destination lies outside the folders
   */
  suspend fun newDownload(request: DownloadRequest): DownloadRequest {
    val destination = request.destination
    if (destination == null || passesAsUri(destination.value)) return request
    val directory = ketch.status().system.downloadDirectory
    val policy = policy(directory)
    val previous = request.requestId?.let { id ->
      ketch.tasks.value.find { it.request.requestId == id }?.request?.destination
    }
    if (previous != null && policy.confinesTo(destination, directory, previous)) {
      return request.copy(destination = previous)
    }
    val confined = policy.confine(destination, directory)
    return if (confined == destination) request else request.copy(destination = confined)
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
   * @throws PathRejectedException if [config] moves it elsewhere
   */
  suspend fun checkConfig(config: DownloadConfig) {
    val status = ketch.status()
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
