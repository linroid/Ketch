package com.linroid.ketch.api

/** Names of optional behaviors a Ketch instance lists in [KetchStatus.features]. */
object KetchFeatures {
  /** [DownloadTask.setConnections] accepts 0 (Auto). */
  const val AUTO_CONNECTIONS: String = "task.autoConnections"

  /** [DownloadTask.queuePosition] reports positions. */
  const val QUEUE_POSITION: String = "task.queuePosition"

  /** Every feature this version of Ketch supports. */
  val ALL: Set<String> = setOf(AUTO_CONNECTIONS, QUEUE_POSITION)
}
