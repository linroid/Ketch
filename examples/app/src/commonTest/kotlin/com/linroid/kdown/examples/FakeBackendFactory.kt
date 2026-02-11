package com.linroid.kdown.examples

import com.linroid.kdown.api.KDownApi
import com.linroid.kdown.examples.backend.BackendConfig

/**
 * A fake [com.linroid.kdown.examples.backend.BackendFactory] for testing
 * [com.linroid.kdown.examples.backend.BackendManager].
 *
 * Usage:
 * ```
 * val factory = FakeBackendFactory(
 *   embeddedFactory = { FakeKDownApi("Core") }
 * )
 * ```
 */
class FakeBackendFactory(
  private val embeddedFactory: () -> KDownApi =
    { FakeKDownApi("Core") }
) {
  /**
   * Override to control what [create] returns for Remote configs.
   * By default, creates a [FakeKDownApi] with the remote label.
   */
  var remoteFactory: ((BackendConfig.Remote) -> KDownApi)? = null

  /**
   * When set to true, the next call to [create] will throw.
   * Resets to false after throwing.
   */
  var failOnNextCreate = false

  var closeResourcesCallCount = 0
    private set
  var createCallCount = 0
    private set
  var lastCreatedConfig: BackendConfig? = null
    private set

  fun create(config: BackendConfig): KDownApi {
    createCallCount++
    lastCreatedConfig = config

    if (failOnNextCreate) {
      failOnNextCreate = false
      throw RuntimeException(
        "Simulated connection failure for $config"
      )
    }

    return when (config) {
      is BackendConfig.Embedded -> embeddedFactory()
      is BackendConfig.Remote -> {
        remoteFactory?.invoke(config)
          ?: FakeKDownApi(
            "Remote · ${config.host}:${config.port}"
          )
      }
    }
  }

  fun closeResources() {
    closeResourcesCallCount++
  }
}
