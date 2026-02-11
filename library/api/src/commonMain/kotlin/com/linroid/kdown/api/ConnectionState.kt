package com.linroid.kdown.api

/**
 * Represents the connection health of a [KDownService] backend.
 *
 * - Local backends are always [Connected].
 * - Remote backends transition between states based on SSE health.
 */
sealed class ConnectionState {
  data object Connected : ConnectionState()
  data object Connecting : ConnectionState()
  data class Disconnected(val reason: String? = null) : ConnectionState()
}
