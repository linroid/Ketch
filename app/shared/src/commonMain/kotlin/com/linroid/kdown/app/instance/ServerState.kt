package com.linroid.kdown.app.instance

/** State of the optional HTTP server exposing the embedded instance. */
sealed class ServerState {
  data object Stopped : ServerState()
  data class Running(val port: Int) : ServerState()
}
