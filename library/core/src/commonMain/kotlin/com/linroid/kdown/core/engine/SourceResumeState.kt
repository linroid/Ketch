package com.linroid.kdown.core.engine

import kotlinx.serialization.Serializable

/**
 * Opaque resume state persisted by a [DownloadSource].
 *
 * Each source type serializes its own resume data into the [data]
 * string. The [sourceType] field identifies which source produced
 * the state so the correct source can deserialize it on resume.
 *
 * @property sourceType the [DownloadSource.type] that produced this state
 * @property data source-specific serialized resume data
 */
@Serializable
data class SourceResumeState(
  val sourceType: String,
  val data: String,
)
