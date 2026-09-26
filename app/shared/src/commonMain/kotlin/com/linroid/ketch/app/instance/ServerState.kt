package com.linroid.ketch.app.instance

import com.linroid.ketch.config.ServerConfig

/** State of the optional HTTP server exposing the embedded instance. */
sealed class ServerState {
  data object Stopped : ServerState()

  /**
   * @property config settings the server was started with; compare them
   *   with the saved ones to tell whether a restart is needed.
   */
  data class Running(val config: ServerConfig) : ServerState() {
    val port: Int get() = config.port
  }

  /** The last start attempt failed, e.g. because the port is in use. */
  data class Failed(val message: String) : ServerState()
}
