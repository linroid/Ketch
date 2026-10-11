package com.linroid.ketch.hooks

import com.linroid.ketch.api.DownloadConfig
import com.linroid.ketch.api.DownloadRequest
import com.linroid.ketch.api.DownloadState
import com.linroid.ketch.api.KetchError
import com.linroid.ketch.api.categoryFor
import com.linroid.ketch.api.log.redactUrl
import com.linroid.ketch.api.log.redactUrlsIn
import com.linroid.ketch.config.HookConfig
import com.linroid.ketch.config.HookEvent
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.time.Instant

/**
 * What a hook receives about a download event: the JSON body a webhook is posted and a command
 * reads on its standard input, whose fields a command also finds in `KETCH_*` environment
 * variables ([environment]). Fields that do not apply are left out.
 *
 * The URL passes through [redactUrl], so passwords and query values never reach a hook.
 *
 * @property event the [HookEvent] id, such as `completed`.
 * @property timestamp when the event happened, ISO 8601 in UTC.
 * @property deliveryId unique for each delivery, so a receiver can drop repeats of one.
 * @property taskId the download's task ID.
 * @property state the download's state when the event happened, such as `downloading`.
 * @property url the download's URL, redacted.
 * @property host the URL's host, lowercased; `null` for magnets and other URLs without one.
 * @property fileName the name of the file it saves.
 * @property outputPath where it saves, once chosen.
 * @property totalBytes its size, when known.
 * @property downloadedBytes bytes saved so far, while it runs or is paused.
 * @property downloadTimeMillis time spent downloading, for a completed download.
 * @property createdAt when the download was added.
 * @property completedAt when it completed.
 * @property origin where it was added from (`browser`, `discover`, `agent`, `app` or `cli`).
 * @property category the folder of the download category it matches.
 * @property sourceType the source that downloads it, such as `http` or `torrent`.
 * @property error why a failed download failed.
 */
@Serializable
data class HookPayload(
  val event: String,
  val timestamp: String,
  val deliveryId: String,
  val taskId: String,
  val state: String,
  val url: String,
  val host: String? = null,
  val fileName: String? = null,
  val outputPath: String? = null,
  val totalBytes: Long? = null,
  val downloadedBytes: Long? = null,
  val downloadTimeMillis: Long? = null,
  val createdAt: String? = null,
  val completedAt: String? = null,
  val origin: String? = null,
  val category: String? = null,
  val sourceType: String? = null,
  val error: HookError? = null,
) {
  /** This payload as the JSON a hook receives. */
  fun toJson(): String = json.encodeToString(serializer(), this)

  /**
   * The environment variables a command gets, besides the ones it inherits: `KETCH_EVENT`,
   * `KETCH_TASK_ID`, `KETCH_OUTPUT_PATH` and so on, one per field that is set.
   */
  fun environment(): Map<String, String> = buildMap {
    put("KETCH_EVENT", event)
    put("KETCH_TIMESTAMP", timestamp)
    put("KETCH_DELIVERY_ID", deliveryId)
    put("KETCH_TASK_ID", taskId)
    put("KETCH_STATE", state)
    put("KETCH_URL", url)
    host?.let { put("KETCH_HOST", it) }
    fileName?.let { put("KETCH_FILE_NAME", it) }
    outputPath?.let { put("KETCH_OUTPUT_PATH", it) }
    totalBytes?.let { put("KETCH_TOTAL_BYTES", it.toString()) }
    downloadedBytes?.let { put("KETCH_DOWNLOADED_BYTES", it.toString()) }
    downloadTimeMillis?.let { put("KETCH_DOWNLOAD_TIME_MS", it.toString()) }
    createdAt?.let { put("KETCH_CREATED_AT", it) }
    completedAt?.let { put("KETCH_COMPLETED_AT", it) }
    origin?.let { put("KETCH_ORIGIN", it) }
    category?.let { put("KETCH_CATEGORY", it) }
    sourceType?.let { put("KETCH_SOURCE_TYPE", it) }
    error?.let { error ->
      put("KETCH_ERROR_KIND", error.kind)
      error.code?.let { put("KETCH_ERROR_CODE", it.toString()) }
      error.message?.let { put("KETCH_ERROR_MESSAGE", it) }
    }
  }

  companion object {
    private val json = Json {
      explicitNulls = false
      encodeDefaults = true
    }

    /**
     * The payload of [event] for a download, from what it was when the event happened.
     *
     * @param config the engine's download settings, which name the categories; `null` leaves the
     *   category out.
     */
    fun of(
      event: HookEvent,
      at: Instant,
      deliveryId: String,
      taskId: String,
      request: DownloadRequest,
      state: DownloadState,
      outputPath: String?,
      createdAt: Instant?,
      config: DownloadConfig?,
    ): HookPayload {
      val completed = state as? DownloadState.Completed
      val progress = when (state) {
        is DownloadState.Downloading -> state.progress
        is DownloadState.Paused -> state.progress
        else -> null
      }
      val source = request.resolvedSource
      val path = completed?.outputPath ?: outputPath
      val fileName = path?.let(::lastSegment)?.ifEmpty { null } ?: source?.suggestedFileName
      val host = hostOf(request.url)
      return HookPayload(
        event = event.id,
        timestamp = at.toString(),
        deliveryId = deliveryId,
        taskId = taskId,
        state = state.id,
        url = redactUrl(request.url),
        host = host,
        fileName = fileName,
        outputPath = path,
        totalBytes = completed?.totalBytes
          ?: progress?.totalBytes?.takeIf { it > 0 }
          ?: source?.totalBytes?.takeIf { it > 0 },
        downloadedBytes = progress?.downloadedBytes,
        downloadTimeMillis = completed?.downloadTime?.inWholeMilliseconds,
        createdAt = createdAt?.toString(),
        completedAt = completed?.completedAt?.toString(),
        origin = request.properties[ORIGIN_PROPERTY]?.ifBlank { null },
        category = fileName?.let {
          config?.categoryFor(it, source?.contentType, host)?.folder
        },
        sourceType = source?.sourceType,
        error = (state as? DownloadState.Failed)?.error?.let(HookError::of),
      )
    }

    /** The request property that names where a download was added from. */
    const val ORIGIN_PROPERTY: String = "ketch.origin"
  }
}

/**
 * Why a download failed.
 *
 * @property kind the error's type: `network`, `http`, `disk`, `unsupported`, `file_changed`,
 *   `corrupt_resume_state`, `canceled`, `source`, `auth_failed` or `unknown`.
 * @property code the HTTP status, for `http`.
 * @property message what went wrong, with any URL in it redacted.
 */
@Serializable
data class HookError(
  val kind: String,
  val code: Int? = null,
  val message: String? = null,
) {
  internal companion object {
    fun of(error: KetchError): HookError = HookError(
      kind = when (error) {
        is KetchError.Network -> "network"
        is KetchError.Http -> "http"
        is KetchError.Disk -> "disk"
        is KetchError.Unsupported -> "unsupported"
        is KetchError.FileChanged -> "file_changed"
        is KetchError.CorruptResumeState -> "corrupt_resume_state"
        is KetchError.Canceled -> "canceled"
        is KetchError.SourceError -> "source"
        is KetchError.AuthenticationFailed -> "auth_failed"
        is KetchError.ChecksumMismatch -> "checksum_mismatch"
        is KetchError.Unknown -> "unknown"
      },
      code = (error as? KetchError.Http)?.code,
      message = error.message?.let(::redactUrlsIn),
    )
  }
}

/** How [HookPayload.state] names a state. */
internal val DownloadState.id: String
  get() = when (this) {
    is DownloadState.Scheduled -> "scheduled"
    DownloadState.Queued -> "queued"
    is DownloadState.Downloading -> "downloading"
    is DownloadState.Paused -> "paused"
    is DownloadState.Completed -> "completed"
    is DownloadState.Failed -> "failed"
    DownloadState.Canceled -> "canceled"
  }

private fun lastSegment(path: String): String =
  path.substringAfterLast('/').substringAfterLast('\\')

/** The lowercased host of [url], without user info or port; `null` when it has none. */
internal fun hostOf(url: String): String? {
  val schemeEnd = url.indexOf("://")
  if (schemeEnd < 0) return null
  val rest = url.substring(schemeEnd + 3)
  val authority = rest.substring(0, rest.indexOfAny(charArrayOf('/', '?', '#')).let {
    if (it < 0) rest.length else it
  })
  val hostPort = authority.substringAfterLast('@')
  val host = if (hostPort.startsWith('[')) {
    hostPort.substringBefore(']').removePrefix("[")
  } else {
    hostPort.substringBefore(':')
  }
  return host.trimEnd('.').lowercase().ifEmpty { null }
}
