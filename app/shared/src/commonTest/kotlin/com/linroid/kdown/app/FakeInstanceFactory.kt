package com.linroid.kdown.app

import com.linroid.kdown.api.KDownApi
import com.linroid.kdown.app.instance.EmbeddedInstance
import com.linroid.kdown.app.instance.RemoteInstance
import com.linroid.kdown.core.KDown
import com.linroid.kdown.remote.RemoteKDown

/**
 * A fake [com.linroid.kdown.app.instance.InstanceFactory] for testing
 * [com.linroid.kdown.app.instance.InstanceManager].
 *
 * Usage:
 * ```
 * val factory = FakeInstanceFactory(
 *   embeddedFactory = { FakeKDownApi("Core") }
 * )
 * ```
 */
class FakeInstanceFactory(
  private val embeddedFactory: () -> KDownApi =
    { FakeKDownApi("Core") },
) {
  /**
   * Override to control what [createRemote] returns.
   * By default, creates a [FakeKDownApi] with the remote label.
   */
  var remoteFactory: ((String, Int, String?) -> KDownApi)? = null

  /**
   * When set to true, the next create call will throw.
   * Resets to false after throwing.
   */
  var failOnNextCreate = false

  var closeResourcesCallCount = 0
    private set
  var createCallCount = 0
    private set

  fun createEmbedded(
    label: String = "Embedded",
  ): EmbeddedInstance {
    createCallCount++
    if (failOnNextCreate) {
      failOnNextCreate = false
      throw RuntimeException(
        "Simulated connection failure for embedded"
      )
    }
    val api = embeddedFactory()
    // In tests we use FakeKDownApi which is not a real KDown,
    // so we cast unsafely. Real tests needing KDown type
    // should provide a real KDown instance.
    @Suppress("UNCHECKED_CAST")
    return EmbeddedInstance(
      instance = api as KDown,
      label = label,
    )
  }

  fun createRemote(
    host: String,
    port: Int = 8642,
    token: String? = null,
  ): RemoteInstance {
    createCallCount++
    if (failOnNextCreate) {
      failOnNextCreate = false
      throw RuntimeException(
        "Simulated connection failure for $host:$port"
      )
    }
    val api = remoteFactory?.invoke(host, port, token)
      ?: FakeKDownApi("Remote · $host:$port")
    @Suppress("UNCHECKED_CAST")
    return RemoteInstance(
      instance = api as RemoteKDown,
      label = "$host:$port",
    )
  }

  fun closeResources() {
    closeResourcesCallCount++
  }
}
