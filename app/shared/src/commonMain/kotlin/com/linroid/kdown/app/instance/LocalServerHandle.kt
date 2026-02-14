package com.linroid.kdown.app.instance

/**
 * Represents a running local server. Defined in commonMain so
 * [InstanceManager] can manage its lifecycle without referencing
 * the JVM-only KDownServer directly.
 *
 * The server wraps an existing [com.linroid.kdown.api.KDownApi]
 * instance (typically the embedded one) and does NOT own it.
 * [stop] shuts down the server only; the underlying KDown
 * instance is managed by [InstanceManager].
 */
interface LocalServerHandle {
  /** Stop the server (does not close the underlying KDown). */
  fun stop()
}
