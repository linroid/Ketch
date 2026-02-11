package com.linroid.kdown.api

import kotlinx.serialization.Serializable

/**
 * Version information for a KDown client-backend pair.
 *
 * @property client version of the client library (e.g., the app)
 * @property backend version of the backend (Core or Remote server)
 */
@Serializable
data class KDownVersion(
  val client: String,
  val backend: String,
) {
  companion object {
    const val DEFAULT = "1.0.0"
  }
}
