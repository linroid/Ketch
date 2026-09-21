package com.linroid.ketch.app.state

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.linroid.ketch.config.AiSettings
import com.linroid.ketch.config.ConfigStore
import kotlinx.coroutines.CancellationException

/** Outcome of a "test connection" attempt on the settings page. */
sealed interface AiConnectionTest {
  data object Idle : AiConnectionTest
  data object Running : AiConnectionTest
  data class Success(val reply: String) : AiConnectionTest
  data class Failure(val message: String) : AiConnectionTest
}

/**
 * Owns the persisted [AiSettings] and the discovery provider built from
 * them.
 *
 * Saving settings rebuilds the provider, so a newly entered token takes
 * effect without restarting the app.
 *
 * @param configStore store backing `config.toml`; `null` keeps the
 *   settings in memory only.
 * @param factory platform provider factory; `null` on platforms that
 *   cannot run discovery locally.
 */
class AiSettingsController(
  private val configStore: ConfigStore? = null,
  private val factory: AiDiscoveryProviderFactory? = null,
) {
  /** Settings as last loaded or saved. */
  var settings by mutableStateOf(configStore?.load()?.ai ?: AiSettings())
    private set

  /** Result of the most recent connection test. */
  var connectionTest by mutableStateOf<AiConnectionTest>(
    AiConnectionTest.Idle,
  )
    private set

  /**
   * Discovery provider for the current settings, or `null` — always
   * `null` while discovery is switched off, even when the environment
   * supplies credentials.
   */
  var provider by mutableStateOf(build(settings))
    private set

  /** Whether this platform can run AI discovery at all. */
  val supported: Boolean get() = factory != null

  /** Whether discovery is ready to use. */
  val available: Boolean get() = provider != null

  /**
   * `true` when discovery works although the saved settings are
   * incomplete — the credentials came from the environment.
   */
  val usingEnvironmentCredentials: Boolean
    get() = provider != null && !settings.isUsable

  /** Persists [settings] and rebuilds the provider. */
  fun save(settings: AiSettings) {
    this.settings = settings
    configStore?.let { store ->
      store.save(store.load().copy(ai = settings))
    }
    connectionTest = AiConnectionTest.Idle
    val previous = provider
    provider = build(settings)
    if (previous !== provider) previous?.close()
  }

  /**
   * Saves [settings], then calls the provider to confirm the
   * credentials work.
   *
   * Works with discovery switched off too — checking a key before
   * turning the feature on is the natural order — by testing against a
   * temporary provider that is released afterwards.
   */
  suspend fun testConnection(settings: AiSettings) {
    save(settings)
    val temporary = if (provider == null && !settings.enabled) {
      build(settings.copy(enabled = true))
    } else {
      null
    }
    val target = provider ?: temporary
    if (target == null) {
      connectionTest = AiConnectionTest.Failure(
        if (supported) "Fill in the fields above first."
        else "AI discovery isn't available on this platform.",
      )
      return
    }
    connectionTest = AiConnectionTest.Running
    try {
      runCatching { target.verify() }
        .onSuccess { reply ->
          connectionTest = AiConnectionTest.Success(reply)
        }
        .onFailure { e ->
          if (e is CancellationException) throw e
          connectionTest = AiConnectionTest.Failure(
            e.message ?: "Connection failed.",
          )
        }
    } finally {
      temporary?.close()
    }
  }

  /**
   * Builds the provider, reporting engine setup failures on the
   * settings page instead of taking the app down with them.
   */
  private fun build(settings: AiSettings): AiDiscoveryProvider? {
    // The switch is authoritative in the apps: an API key in the
    // environment may fill a blank token, but never turns the feature
    // (and its tab) on by itself.
    if (!settings.enabled) return null
    return runCatching { factory?.create(settings) }
      .onFailure { e ->
        if (e is CancellationException) throw e
        connectionTest = AiConnectionTest.Failure(
          e.message ?: "Could not start the discovery engine.",
        )
      }
      .getOrNull()
  }
}
