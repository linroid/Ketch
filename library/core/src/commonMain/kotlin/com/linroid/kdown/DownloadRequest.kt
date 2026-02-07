package com.linroid.kdown

import kotlin.uuid.Uuid

data class DownloadRequest(
  val url: String,
  val destPath: String,
  val taskId: String = generateTaskId(),
  val connections: Int = 4
) {
  init {
    require(url.isNotBlank()) { "URL must not be blank" }
    require(destPath.isNotBlank()) { "Destination path must not be blank" }
    require(connections > 0) { "Connections must be greater than 0" }
  }

  companion object {
    fun generateTaskId(): String = Uuid.random().toString()
  }
}
