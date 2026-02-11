package com.linroid.kdown.api

import kotlinx.serialization.Serializable

@Serializable
data class KDownVersion(
  val client: String,
  val backend: String,
) {
  companion object {
    const val DEFAULT = "1.0.0"
  }
}
