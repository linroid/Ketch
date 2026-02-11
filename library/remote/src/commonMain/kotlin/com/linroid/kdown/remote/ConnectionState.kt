package com.linroid.kdown.remote

/**
 * Connection health of the [RemoteKDown] backend.
 */
sealed class ConnectionState {
  data object Connected : ConnectionState()
  data object Connecting : ConnectionState()
  data class Disconnected(val reason: String? = null) :
    ConnectionState()
}
