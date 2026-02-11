package com.linroid.kdown

data class DownloadProgress(
  val downloadedBytes: Long,
  val totalBytes: Long,
  val bytesPerSecond: Long = 0
) {
  val percent: Float
    get() = if (totalBytes > 0) downloadedBytes.toFloat() / totalBytes else 0f

  val isComplete: Boolean
    get() = totalBytes > 0 && downloadedBytes >= totalBytes
}
