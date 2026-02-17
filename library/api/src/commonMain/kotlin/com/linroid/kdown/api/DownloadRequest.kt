package com.linroid.kdown.api

import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

/**
 * Describes a file to download.
 *
 * @property url the HTTP(S) URL to download from. Must not be blank.
 * @property output where the file should be saved. See [Output].
 * @property connections number of concurrent connections (segments) to
 *   use. Must be greater than 0. Falls back to a single connection if
 *   the server does not support HTTP Range requests.
 * @property headers custom HTTP headers to include in every request
 *   (HEAD and GET) for this download.
 * @property properties arbitrary key-value pairs for use by custom
 *   extensions. KDown itself does not read these values.
 * @property speedLimit per-task speed limit. Overrides the global
 *   speed limit for this download. Defaults to
 *   [SpeedLimit.Unlimited] (use global limit).
 * @property priority queue priority for this download. Higher-priority
 *   tasks are started before lower-priority ones when download slots
 *   become available. Defaults to [DownloadPriority.NORMAL].
 * @property schedule when the download should start. Defaults to
 *   [DownloadSchedule.Immediate].
 * @property conditions list of [DownloadCondition]s that must all be
 *   met before the download starts. Not persisted across restarts.
 * @property selectedFileIds IDs of files selected from
 *   [ResolvedSource.files]. Empty means download all/default.
 *   Sources read this via the download context to determine
 *   which files to download.
 * @property resolvedUrl pre-resolved URL metadata from
 *   [KDownApi.resolve]. When present, the download engine skips
 *   its own probe and uses this information directly. Not persisted
 *   across restarts.
 */
@Serializable
data class DownloadRequest(
  val url: String,
  val output: Output = Output.DirectoryAndFile(),
  val connections: Int = 1,
  val headers: Map<String, String> = emptyMap(),
  val properties: Map<String, String> = emptyMap(),
  val speedLimit: SpeedLimit = SpeedLimit.Unlimited,
  val priority: DownloadPriority = DownloadPriority.NORMAL,
  val schedule: DownloadSchedule = DownloadSchedule.Immediate,
  val selectedFileIds: Set<String> = emptySet(),
  @Transient
  val conditions: List<DownloadCondition> = emptyList(),
  @Transient
  val resolvedUrl: ResolvedSource? = null,
) {
  constructor(
    url: String,
    directory: String? = null,
    fileName: String? = null,
    connections: Int = 1,
    headers: Map<String, String> = emptyMap(),
    properties: Map<String, String> = emptyMap(),
    speedLimit: SpeedLimit = SpeedLimit.Unlimited,
    priority: DownloadPriority = DownloadPriority.NORMAL,
    schedule: DownloadSchedule = DownloadSchedule.Immediate,
    selectedFileIds: Set<String> = emptySet(),
    conditions: List<DownloadCondition> = emptyList(),
    resolvedUrl: ResolvedSource? = null,
  ) : this(
    url = url,
    output = Output.DirectoryAndFile(directory, fileName),
    connections = connections,
    headers = headers,
    properties = properties,
    speedLimit = speedLimit,
    priority = priority,
    schedule = schedule,
    selectedFileIds = selectedFileIds,
    conditions = conditions,
    resolvedUrl = resolvedUrl,
  )

  init {
    require(url.isNotBlank()) { "URL must not be blank" }
    require(connections > 0) { "Connections must be greater than 0" }
  }
}

@Serializable
sealed interface Output {
  fun toDestPath(defaultDirectory: String, defaultFileName: String?, deduplicate: Boolean): String

  /**
   * @property directory the local directory where the file will be saved.
   *   When `null`, the implementation chooses a default location.
   * @property fileName explicit file name to save as. When `null`, the
   *   file name is determined from the server response
   *   (Content-Disposition header, URL path, or a fallback).
   */
  @Serializable
  data class DirectoryAndFile(val directory: String? = null, val fileName: String? = null) : Output {
    override fun toDestPath(defaultDirectory: String, defaultFileName: String?, deduplicate: Boolean): String {
      val directory = directory?.let { Path(it) }
        ?: Path(defaultDirectory)
      val initialDestPath = (fileName ?: defaultFileName)?.let {
        Path(directory, it).let { p ->
          if (deduplicate) {
            deduplicatePath(p)
          } else {
            p
          }
        }
      } ?: directory

      return initialDestPath.toString()
    }

    companion object {
      internal fun deduplicatePath(
        candidate: Path,
      ): Path {
        val fileName = candidate.name
        val directory = candidate.parent ?: return candidate
        if (!SystemFileSystem.exists(candidate)) return candidate

        val dotIndex = fileName.lastIndexOf('.')
        val baseName: String
        val extension: String
        if (dotIndex > 0) {
          baseName = fileName.take(dotIndex)
          extension = fileName.substring(dotIndex)
        } else {
          baseName = fileName
          extension = ""
        }

        var seq = 1
        while (true) {
          val path = Path(directory, "$baseName ($seq)$extension")
          if (!SystemFileSystem.exists(path)) return path
          seq++
        }
      }
    }
  }

  /**
   * @param path the full path to the destination file. On Android, this can also be
   *    a content Uri pointing to a file.
   * @param displayName an optional display name for the file. You can use this to help
   *    track the name of a file in your UI.
   *
   * Note that this method doesn't support deduplicating when starting a new download and
   *   will overwrite existing files.
   */
  @Serializable
  data class PathOrUri(val path: String, val displayName: String? = null) : Output {
    override fun toDestPath(defaultDirectory: String, defaultFileName: String?, deduplicate: Boolean): String {
      return path
    }
  }
}
