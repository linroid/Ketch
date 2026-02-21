package com.linroid.ketch.app.instance

/**
 * Represents a running local server. Defined in commonMain so
 * [InstanceManager] can manage its lifecycle without referencing
 * the JVM-only KetchServer directly.
 *
 * The server wraps an existing [com.linroid.ketch.api.KetchApi]
 * instance (typically the embedded one) and does NOT own it.
 * [stop] shuts down the server only; the underlying Ketch
 * instance is managed by [InstanceManager].
 */
interface LocalServerHandle {
  /** Stop the server (does not close the underlying Ketch). */
  fun stop()
}
