package com.linroid.ketch.endpoints.model

import kotlinx.serialization.Serializable

/**
 * Request body of `PUT /api/tasks/{id}/files`.
 *
 * @property fileIds the IDs of the files to download, from the task's resolved source: between
 *   1 and 100,000 of them, each 1 to 128 characters long
 */
@Serializable
data class FileSelectionRequest(
  val fileIds: Set<String>,
)
