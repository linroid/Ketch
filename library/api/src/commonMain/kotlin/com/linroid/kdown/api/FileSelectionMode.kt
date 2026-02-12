package com.linroid.kdown.api

import kotlinx.serialization.Serializable

/**
 * Determines how the user selects files from a multi-file source.
 *
 * @property MULTIPLE pick any subset of files (e.g., torrent)
 * @property SINGLE pick exactly one variant (e.g., HLS quality, media format)
 */
@Serializable
enum class FileSelectionMode {
  MULTIPLE,
  SINGLE,
}
