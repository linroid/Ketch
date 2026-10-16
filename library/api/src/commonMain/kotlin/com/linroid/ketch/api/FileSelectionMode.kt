package com.linroid.ketch.api

import kotlinx.serialization.Serializable

/** How [ResolvedSource.files] are chosen: any subset (torrents) or exactly one variant. */
@Serializable
enum class FileSelectionMode {
  MULTIPLE,
  SINGLE,
}
