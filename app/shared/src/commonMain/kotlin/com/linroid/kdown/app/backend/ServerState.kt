package com.linroid.kdown.app.backend

/** State of the optional HTTP server exposing the embedded backend. */
sealed class ServerState {
  data object Stopped : ServerState()
  data class Running(val port: Int) : ServerState()
}
