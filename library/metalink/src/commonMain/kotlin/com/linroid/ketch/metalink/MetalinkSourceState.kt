package com.linroid.ketch.metalink

import kotlinx.serialization.Serializable

/**
 * What [MetalinkDownloadSource] saves to resume a download; opaque outside this module. The
 * mirrors and hashes stay in the resolution the task keeps.
 *
 * @property fileId the Metalink file the saved bytes belong to
 */
@Serializable
@ConsistentCopyVisibility
data class MetalinkSourceState internal constructor(
  internal val fileId: String,
)
