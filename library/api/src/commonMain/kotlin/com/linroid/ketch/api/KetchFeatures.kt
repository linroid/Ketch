package com.linroid.ketch.api

/** Names of optional behaviors a Ketch instance lists in [KetchStatus.features]. */
object KetchFeatures {
  /** Repeated [DownloadRequest.requestId] submissions reuse a retained task. */
  const val REQUEST_ID: String = "task.requestId"
  /** [DownloadTask.setConnections] accepts 0 (Auto). */
  const val AUTO_CONNECTIONS: String = "task.autoConnections"

  /** [DownloadTask.queuePosition] reports positions. */
  const val QUEUE_POSITION: String = "task.queuePosition"

  /** [DownloadConfig.proxy] and [DownloadRequest.proxy] are applied to HTTP(S) downloads. */
  const val PROXY: String = "http.proxy"

  /** Finite, clear single-stream HLS and DASH downloads, with restart rather than byte resume. */
  const val FINITE_MEDIA: String = "media.finite"

  /** Finite, unencrypted HLS downloads that restart on resume. */
  const val FINITE_HLS: String = "hls.finite"

  /** Finite, unencrypted DASH downloads that restart on resume. */
  const val FINITE_DASH: String = "dash.finite"

  /** [DownloadConfig.categories] sort downloads into folders. */
  const val CATEGORY_FOLDERS: String = "download.categories"

  /** Every feature this version of Ketch supports, including optional sources. */
  val ALL: Set<String> = setOf(
    AUTO_CONNECTIONS, QUEUE_POSITION, REQUEST_ID, PROXY, FINITE_MEDIA, FINITE_HLS, FINITE_DASH,
    CATEGORY_FOLDERS
  )
}
