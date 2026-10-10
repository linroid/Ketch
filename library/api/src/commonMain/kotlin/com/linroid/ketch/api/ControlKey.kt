package com.linroid.ketch.api

/**
 * Names an optional controller that a [KetchApi] backend may offer beyond the download API, such
 * as the typed controls of one download protocol. The module that defines a controller defines its
 * key, usually as the controller's companion object, and [KetchApi.control] returns a backend's
 * controller for it. Keys compare by identity.
 *
 * @param T the controller type
 */
interface ControlKey<T : Any> {
  /** A short stable name of the controller, for logs and error messages. */
  val name: String
}
