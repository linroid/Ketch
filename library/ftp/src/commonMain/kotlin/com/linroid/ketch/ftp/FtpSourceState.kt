package com.linroid.ketch.ftp

import kotlinx.serialization.Serializable

/**
 * What [FtpDownloadSource] saves to resume a download.
 *
 * @property totalBytes total file size as reported by the server
 * @property mdtm last modification time from MDTM command, used to
 *   detect if the remote file has changed since the download started
 */
@Serializable
data class FtpSourceState(
  val totalBytes: Long,
  val mdtm: String? = null,
)
