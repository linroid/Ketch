package com.linroid.ketch.ftp

import kotlinx.serialization.Serializable

/**
 * Persisted resume state for FTP downloads.
 *
 * @property totalBytes total file size as reported by the server
 * @property mdtm last modification time from MDTM command, used to
 *   detect if the remote file has changed since the download started
 */
@Serializable
internal data class FtpResumeState(
  val totalBytes: Long,
  val mdtm: String? = null,
)
