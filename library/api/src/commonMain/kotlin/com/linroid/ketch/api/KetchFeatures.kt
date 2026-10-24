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

  /** Both [FINITE_HLS] and [FINITE_DASH]; kept for older clients. */
  const val FINITE_MEDIA: String = "media.finite"

  /** Finite, unencrypted HLS downloads, restarted on resume. */
  const val FINITE_HLS: String = "hls.finite"

  /** Finite, unencrypted DASH downloads, restarted on resume. */
  const val FINITE_DASH: String = "dash.finite"

  /**
   * Metalink (`.meta4`, `.metalink`) downloads come from every mirror they list at once, checked
   * against their hashes.
   */
  const val METALINK: String = "download.metalink"

  /** [DownloadConfig.categories] sort downloads into folders. */
  const val CATEGORY_FOLDERS: String = "download.categories"

  /**
   * [DownloadConfig.idleTimeoutMs], [DownloadConfig.minSpeed], [DownloadConfig.unlimitedRetries]
   * and [DownloadConfig.maxRetryDelayMs] apply.
   */
  const val TRANSFER_TIMEOUTS: String = "download.timeouts"

  /** [DownloadTask.selectFiles] works; listed by the sources that implement it. */
  const val FILE_SELECTION: String = "download.fileSelection"

  /** [DownloadRequest.awaitFileSelection] works; listed by the sources that implement it. */
  const val AWAIT_FILE_SELECTION: String = "download.awaitFileSelection"

  /** [DownloadRequest.checksum] and digests servers publish are verified. */
  const val CHECKSUM: String = "download.checksum"

  /** [Interest.Connections] reports live connections. */
  const val ACTIVE_CONNECTIONS: String = "net.activeConnections"

  /** [KetchApi.controller] offers a [CountryDatabase], for [ActiveConnection.country]. */
  const val COUNTRIES: String = "net.countries"

  /** [KetchApi.controller] offers [SpeedModes], which [Interest.SpeedMode] follows. */
  const val SPEED_MODES: String = "speed.modes"

  /**
   * [KetchApi.updateConfig] saves the configuration, so it outlasts a restart of the instance.
   * Listed by servers that save what clients change, never by an engine itself, which leaves
   * saving to its host; so not in [ALL].
   */
  const val CONFIG_PERSISTED: String = "config.persisted"

  /**
   * Every feature named here but [CONFIG_PERSISTED], which servers list; other modules name their
   * controllers' own.
   */
  val ALL: Set<String> = setOf(
    AUTO_CONNECTIONS, QUEUE_POSITION, REQUEST_ID, PROXY, FINITE_MEDIA, FINITE_HLS, FINITE_DASH,
    CATEGORY_FOLDERS, TRANSFER_TIMEOUTS, FILE_SELECTION, AWAIT_FILE_SELECTION, ACTIVE_CONNECTIONS,
    CHECKSUM, COUNTRIES, METALINK, SPEED_MODES,
  )
}
