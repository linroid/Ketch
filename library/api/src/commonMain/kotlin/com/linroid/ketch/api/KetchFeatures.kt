package com.linroid.ketch.api

/** Names of optional behaviors a Ketch instance lists in [KetchStatus.features]. */
object KetchFeatures {
  /** Repeated [DownloadRequest.requestId] submissions reuse a retained task. */
  const val REQUEST_ID: String = "task.requestId"
  /** [DownloadTask.setConnections] accepts 0 (Auto). */
  const val AUTO_CONNECTIONS: String = "task.autoConnections"

  /** [DownloadTask.queuePosition] reports positions. */
  const val QUEUE_POSITION: String = "task.queuePosition"

  /** Finite, clear single-stream HLS and DASH downloads, with restart rather than byte resume. */
  const val FINITE_MEDIA: String = "media.finite"

  /** Every feature this version of Ketch supports. */
  val ALL: Set<String> = setOf(AUTO_CONNECTIONS, QUEUE_POSITION, REQUEST_ID, FINITE_MEDIA)
}
