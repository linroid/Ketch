package com.linroid.ketch.app.state

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.linroid.ketch.app.i18n.UiText
import com.linroid.ketch.app.i18n.text
import com.linroid.ketch.app.i18n.verbatim
import com.linroid.ketch.config.AiSettings
import com.linroid.ketch.config.ConfigStore
import com.linroid.ketch.config.LlmProvider
import com.linroid.ketch.config.LlmSettings
import com.linroid.ketch.config.PageAccessSettings
import ketch.app.shared.generated.resources.Res
import ketch.app.shared.generated.resources.settings_ai_engine_failed
import ketch.app.shared.generated.resources.settings_ai_models_failed
import ketch.app.shared.generated.resources.settings_ai_test_connection_failed
import ketch.app.shared.generated.resources.settings_ai_test_incomplete
import ketch.app.shared.generated.resources.settings_ai_test_unsupported
import kotlinx.coroutines.CancellationException

/** Outcome of a "test connection" attempt on the settings page. */
sealed interface AiConnectionTest {
  data object Idle : AiConnectionTest
  data object Running : AiConnectionTest
  data class Success(val reply: String) : AiConnectionTest

  /** The test failed, for the reason [message]: the provider's own words, or ours. */
  data class Failure(val message: UiText) : AiConnectionTest
}

/** The models a provider listed, as the settings page asked for them. */
sealed interface AiModelList {
  /** Not asked for yet. */
  data object Idle : AiModelList

  /** Waiting for the provider. */
  data object Loading : AiModelList

  /** The provider listed [models], sorted. */
  data class Loaded(val models: List<String>) : AiModelList

  /** The provider could not list them, for the reason [message]. */
  data class Failed(val message: UiText) : AiModelList
}

/**
 * The provider and model a Discover turn searched with.
 *
 * @property provider the saved provider's name at the time, such as "OpenAI" or "Work".
 * @property model the id of the model it called.
 */
data class TurnModel(val provider: String, val model: String)

/**
 * Owns the persisted [AiSettings] and the discovery provider built from
 * them.
 *
 * Saving settings rebuilds the provider, so a newly entered token takes
 * effect without restarting the app. Searches that run keep the provider they started with, which
 * is released once they end, so switching the provider or model applies to the next search.
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

  /** Result of the most recent connection test, of the provider [testedId] names. */
  var connectionTest by mutableStateOf<AiConnectionTest>(
    AiConnectionTest.Idle,
  )
    private set

  /** Id of the provider [connectionTest] is about; `null` before any test. */
  var testedId by mutableStateOf<String?>(null)
    private set

  /** The models each provider listed, by provider id, while the app runs. */
  var modelLists by mutableStateOf<Map<String, AiModelList>>(emptyMap())
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

  /** Whether discovery is ready to use: switched on, with the keys it needs. */
  val available: Boolean get() = provider != null

  /**
   * Whether the apps offer Discover: this platform can run it and it is switched on, which it is
   * by default. Until its keys are set, Discover shows how to set it up, or to turn it off.
   */
  val offered: Boolean get() = supported && settings.enabled

  /** The provider and model the next search uses, as a turn records them. */
  val activeModel: TurnModel
    get() {
      val llm = withPlatformCredentials(settings).llm
      return TurnModel(provider = llm.displayName, model = llm.effectiveModel)
    }

  /** Switches discovery off, or back [on], keeping everything else. */
  fun setEnabled(on: Boolean) {
    if (settings.enabled != on) save(settings.copy(enabled = on))
  }

  /**
   * [settings] as the engine would see them, with the blank credentials
   * this platform can supply (e.g. from the environment) filled in.
   */
  fun withPlatformCredentials(settings: AiSettings): AiSettings =
    factory?.withPlatformCredentials(settings) ?: settings

  /**
   * Whether the saved provider with [id] has what it needs, credentials this platform supplies
   * included, so that discovery could use it.
   */
  fun isComplete(id: String): Boolean =
    withPlatformCredentials(settings).entry(id)?.isComplete == true

  /**
   * Switches discovery on with [provider], as the Discover setup page's provider buttons do: the
   * first saved provider of that kind, or a new one with its defaults.
   */
  fun chooseProvider(provider: LlmProvider) {
    val existing = settings.entries.firstOrNull { it.provider == provider }
    val chosen = if (existing != null) {
      settings
    } else {
      settings.withEntry(settings.newEntry(provider))
    }
    val id = existing?.id ?: chosen.providers.last().id
    save(chosen.copy(enabled = true).withActive(id))
  }

  /**
   * Saves a new provider of [provider] with its defaults, and returns its id. It becomes the one
   * discovery uses only when no provider was saved before.
   */
  fun addProvider(provider: LlmProvider): String {
    val entry = settings.newEntry(provider)
    save(settings.withEntry(entry))
    return entry.id
  }

  /** Saves [entry] in place of the provider with its id, or as the default one's first save. */
  fun saveProvider(entry: LlmSettings) {
    save(settings.withEntry(entry))
  }

  /** Removes the saved provider with [id]; the first one left takes over if it was in use. */
  fun removeProvider(id: String) {
    modelLists = modelLists - id
    save(settings.withoutEntry(id))
  }

  /**
   * Makes the saved provider with [id] the one the next search uses, calling [model] when it is
   * given. Searches that run finish with what they started with.
   */
  fun use(id: String, model: String? = null) {
    save(settings.withActive(id, model))
  }

  /**
   * Persists [settings] and rebuilds the provider when they change what the engine runs with.
   * Saving the same engine settings, or a change to [AiSettings.access] alone, keeps the provider
   * and the last connection test, so searches that are running go on: discovery reads page
   * access when it asks.
   */
  fun save(settings: AiSettings) {
    val sameEngine = settings.engineSettings == this.settings.engineSettings &&
      (provider != null || !settings.enabled)
    this.settings = settings
    configStore?.let { store ->
      store.save(store.load().copy(ai = settings))
    }
    if (sameEngine) return
    connectionTest = AiConnectionTest.Idle
    val previous = provider
    provider = build(settings)
    if (previous !== provider) previous?.close()
  }

  /** Persists the page access [transform] makes of the current one, keeping the provider. */
  fun saveAccess(transform: (PageAccessSettings) -> PageAccessSettings) {
    val access = transform(settings.access)
    if (access != settings.access) save(settings.copy(access = access))
  }

  /**
   * Calls the saved provider with [id], by default the one in use, with its model to confirm the
   * credentials work.
   *
   * Works for a provider that is not in use and with discovery switched off too — checking a key
   * before using it is the natural order — by testing against a temporary provider that is
   * released afterwards.
   */
  suspend fun testConnection(id: String = settings.llm.id) {
    testedId = id
    val inUse = id == settings.llm.id && settings.enabled && provider != null
    val temporary = if (inUse) {
      null
    } else {
      val created = runCatching { factory?.create(settings.copy(enabled = true).withActive(id)) }
      created.exceptionOrNull()?.let { e ->
        if (e is CancellationException) throw e
        connectionTest = AiConnectionTest.Failure(
          e.message?.let(::verbatim) ?: Res.string.settings_ai_engine_failed.text(),
        )
        return
      }
      created.getOrNull()
    }
    val target = if (inUse) provider else temporary
    if (target == null) {
      connectionTest = AiConnectionTest.Failure(
        if (supported) {
          Res.string.settings_ai_test_incomplete.text()
        } else {
          Res.string.settings_ai_test_unsupported.text()
        },
      )
      return
    }
    connectionTest = AiConnectionTest.Running
    try {
      val result = runCatching { target.verify() }
      // Another test, or a change, may have started meanwhile.
      if (testedId != id || connectionTest != AiConnectionTest.Running) return
      result
        .onSuccess { reply -> connectionTest = AiConnectionTest.Success(reply) }
        .onFailure { e ->
          if (e is CancellationException) throw e
          connectionTest = AiConnectionTest.Failure(
            e.message?.let(::verbatim) ?: Res.string.settings_ai_test_connection_failed.text(),
          )
        }
    } finally {
      temporary?.close()
    }
  }

  /**
   * Asks the saved provider with [id] for its models, for [modelLists]. A provider that cannot
   * list them, or that lacks the key it needs, reports why.
   */
  suspend fun loadModels(id: String) {
    val entry = settings.entry(id) ?: return
    val lister = factory ?: return
    modelLists = modelLists + (id to AiModelList.Loading)
    val result = try {
      AiModelList.Loaded(lister.listModels(entry))
    } catch (e: CancellationException) {
      modelLists = modelLists - id
      throw e
    } catch (e: Exception) {
      AiModelList.Failed(
        e.message?.takeIf { e !is UnsupportedOperationException }?.let(::verbatim)
          ?: Res.string.settings_ai_models_failed.text(),
      )
    }
    modelLists = modelLists + (id to result)
  }

  /**
   * Releases the current provider. Call it when the controller is
   * discarded — e.g. an Android activity recreation that keeps the
   * service alive — or the engine's HTTP clients and their thread pools
   * leak.
   */
  fun close() {
    provider?.close()
    provider = null
  }

  /**
   * Builds the provider, reporting engine setup failures on the
   * settings page instead of taking the app down with them.
   */
  private fun build(settings: AiSettings): AiDiscoveryProvider? {
    // The switch is authoritative: an API key in the environment may fill a blank token, but
    // never turns the feature (and its tab) back on.
    if (!settings.enabled) return null
    return runCatching { factory?.create(settings) }
      .onFailure { e ->
        if (e is CancellationException) throw e
        connectionTest = AiConnectionTest.Failure(
          e.message?.let(::verbatim) ?: Res.string.settings_ai_engine_failed.text(),
        )
      }
      .getOrNull()
  }
}
