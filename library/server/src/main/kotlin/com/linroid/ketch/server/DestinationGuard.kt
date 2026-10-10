package com.linroid.ketch.server

import com.linroid.ketch.api.Destination
import com.linroid.ketch.api.DownloadConfig
import com.linroid.ketch.api.DownloadRequest
import com.linroid.ketch.api.DownloadState
import com.linroid.ketch.api.DownloadTask
import com.linroid.ketch.api.KetchApi
import com.linroid.ketch.core.file.DestinationPathPolicy
import com.linroid.ketch.core.file.PathRejectedException

/**
 * Keeps the paths API callers choose in the download directory and [allowedDirectories]: where
 * new downloads are saved, where a resumed task continues, which folder becomes the download
 * directory, and which files removing a task deletes.
 *
 * The download directory is read for every request, as it can change while the server runs.
 *
 * @param enabled whether callers are kept to the folders; when `false`, paths pass unchanged
 */
internal class DestinationGuard(
  private val ketch: KetchApi,
  private val allowedDirectories: List<String>,
  val enabled: Boolean,
) {
  /**
   * [request] with its destination [confined][DestinationPathPolicy.confine]: inside the
   * folders, relative to the download directory, and never naming an existing file.
   *
   * A request sent again with the [DownloadRequest.requestId] of a task keeps the destination
   * that task got, when it names the same place: the task's download may have created the file
   * since, which would give a new submission another name and make the two differ.
   *
   * @throws PathRejectedException if the destination lies outside the folders
   */
  suspend fun newDownload(request: DownloadRequest): DownloadRequest {
    val destination = request.destination
    if (!enabled || destination == null) return request
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
    if (!enabled) return Destination(destination)
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
    if (!enabled) return
    val status = ketch.status()
    if (config.defaultDirectory == status.config.defaultDirectory) return
    val target = config.defaultDirectory
      ?: status.system.defaultDownloadDirectory
      ?: throw PathRejectedException("the default download folder", "is unknown")
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
    if (!enabled) return
    val path = task.outputPath
      ?: (task.state.value as? DownloadState.Completed)?.outputPath
      ?: return
    val directory = ketch.status().system.downloadDirectory
    if (!policy(directory).contains(path)) {
      throw PathRejectedException(path, "is outside the folders files can be deleted from")
    }
  }

  private fun policy(downloadDirectory: String) =
    DestinationPathPolicy(allowedDirectories + downloadDirectory)
}
