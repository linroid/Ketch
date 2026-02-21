package com.linroid.ketch.app

import com.linroid.ketch.api.KetchApi
import com.linroid.ketch.api.config.RemoteConfig
import com.linroid.ketch.app.instance.EmbeddedInstance
import com.linroid.ketch.app.instance.RemoteInstance
import com.linroid.ketch.core.Ketch
import com.linroid.ketch.remote.RemoteKetch

/**
 * A fake [com.linroid.ketch.app.instance.InstanceFactory] for testing
 * [com.linroid.ketch.app.instance.InstanceManager].
 *
 * Usage:
 * ```
 * val factory = FakeInstanceFactory(
 *   embeddedFactory = { FakeKetchApi("Core") }
 * )
 * ```
 */
class FakeInstanceFactory(
  private val embeddedFactory: () -> KetchApi =
    { FakeKetchApi("Core") },
) {
  /**
   * Override to control what [createRemote] returns.
   * By default, creates a [FakeKetchApi] with the remote label.
   */
  var remoteFactory: ((String, Int, String?) -> KetchApi)? = null

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
    // In tests we use FakeKetchApi which is not a real Ketch,
    // so we cast unsafely. Real tests needing Ketch type
    // should provide a real Ketch instance.
    @Suppress("UNCHECKED_CAST")
    return EmbeddedInstance(
      instance = api as Ketch,
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
      ?: FakeKetchApi("Remote · $host:$port")
    @Suppress("UNCHECKED_CAST")
    return RemoteInstance(
      instance = api as RemoteKetch,
      label = "$host:$port",
      remoteConfig = RemoteConfig(
        host = host, port = port, apiToken = token,
      ),
    )
  }

  fun closeResources() {
    closeResourcesCallCount++
  }
}
