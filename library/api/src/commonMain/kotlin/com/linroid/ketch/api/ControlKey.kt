package com.linroid.ketch.api

/**
 * Names an optional controller, such as a protocol's controls, for [KetchApi.control]. Defined
 * with the controller, usually as its companion object; keys compare by identity.
 */
interface ControlKey<T : Any> {
  /** A short stable name, for logs and errors. */
  val name: String
}
