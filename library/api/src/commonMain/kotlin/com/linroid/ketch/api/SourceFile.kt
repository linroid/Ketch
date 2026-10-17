package com.linroid.ketch.api

import kotlinx.serialization.Serializable

/**
 * One file or variant of a source with several ([ResolvedSource.files]).
 *
 * @property id unique within the source
 * @property size size in bytes, or -1 if unknown
 * @property metadata source-specific values, such as `path` or `quality`, and [FILE_NAME_KEY]
 */
@Serializable
data class SourceFile(
  val id: String,
  val name: String,
  val size: Long = -1,
  val metadata: Map<String, String> = emptyMap(),
) {
  companion object {
    /**
     * [metadata] key of the name a file of a single choice ([FileSelectionMode.SINGLE]) is saved
     * under when it is chosen, in place of [ResolvedSource.suggestedFileName], as when a Metalink
     * lists several different files.
     */
    const val FILE_NAME_KEY: String = "fileName"
  }
}
