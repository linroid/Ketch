package com.linroid.ketch.config

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/**
 * Web search providers used by the discovery agent's search tools.
 *
 * Saved as [id]. An id this version does not know, such as `bing`
 * (the Bing Search APIs were retired in August 2025), loads as [None]
 * rather than failing the whole config file.
 *
 * @property id value stored in `config.toml`.
 * @property label human-readable provider name.
 * @property requiresApiKey whether the provider needs a key.
 * @property requiresCx whether the provider needs a search engine id.
 */
@Serializable(with = SearchProviderSerializer::class)
enum class SearchProvider(
  val id: String,
  val label: String,
  val requiresApiKey: Boolean,
  val requiresCx: Boolean,
) {
  None(id = "none", label = "None", requiresApiKey = false, requiresCx = false),
  Brave(id = "brave", label = "Brave", requiresApiKey = true, requiresCx = false),
  Google(id = "google", label = "Google", requiresApiKey = true, requiresCx = true),
}

internal object SearchProviderSerializer : KSerializer<SearchProvider> {
  override val descriptor: SerialDescriptor =
    PrimitiveSerialDescriptor("com.linroid.ketch.config.SearchProvider", PrimitiveKind.STRING)

  override fun serialize(encoder: Encoder, value: SearchProvider) {
    encoder.encodeString(value.id)
  }

  override fun deserialize(decoder: Decoder): SearchProvider {
    val id = decoder.decodeString()
    return SearchProvider.entries.firstOrNull { it.id == id } ?: SearchProvider.None
  }
}

/**
 * A saved LLM provider for AI discovery: the provider to call, with its key, endpoint and models.
 * The settings keep several under `[[ai.providers]]`, and discovery calls the one
 * [AiSettings.active] names.
 *
 * @property id unique among the saved providers, such as `openai` or `openai-2`; blank for the
 *   default provider while none is saved.
 * @property name what the apps call it; blank means [LlmProvider.label].
 * @property provider which LLM provider to call.
 * @property apiKey provider API key. Stored as plain text in the config
 *   file, like [ServerConfig.apiToken].
 * @property baseUrl endpoint override; blank means
 *   [LlmProvider.defaultBaseUrl].
 * @property model the model to call; blank means [LlmProvider.defaultModel].
 * @property models model ids the user added, which the apps offer next to [LlmProvider.models].
 */
@Serializable
data class LlmSettings(
  val id: String = "",
  val name: String = "",
  val provider: LlmProvider = LlmProvider.OpenAi,
  val apiKey: String = "",
  val baseUrl: String = "",
  val model: String = "",
  val models: List<String> = emptyList(),
) {
  /** What the apps call it: its [name], or the provider's. */
  val displayName: String
    get() = name.ifBlank { provider.label }

  /** Model id to call, falling back to the provider default. */
  val effectiveModel: String
    get() = model.ifBlank { provider.defaultModel }

  /** Endpoint to call, falling back to the provider default. */
  val effectiveBaseUrl: String
    get() = baseUrl.ifBlank { provider.defaultBaseUrl }

  /** `true` when every field the provider needs has a value. */
  val isComplete: Boolean
    get() = (!provider.requiresApiKey || apiKey.isNotBlank()) &&
      effectiveBaseUrl.isNotBlank() &&
      effectiveModel.isNotBlank()

  /**
   * The models to choose from, each once: the ones the user added, then the provider's, with
   * [effectiveModel] first when it is in neither.
   */
  val modelChoices: List<String>
    get() {
      val listed = (models + provider.models).filter { it.isNotBlank() }.distinct()
      val current = effectiveModel
      return if (current.isBlank() || current in listed) listed else listOf(current) + listed
    }

  /** Calls [model] from now on, adding it to [models] when neither list has it. */
  fun withModel(model: String): LlmSettings {
    val id = model.trim()
    if (id.isEmpty()) return copy(model = "")
    val known = id in models || id in provider.models
    return copy(model = id, models = if (known) models else models + id)
  }

  /** Forgets [model]: it leaves [models], and stops being the one called. */
  fun withoutModel(model: String): LlmSettings = copy(
    model = if (this.model == model) "" else this.model,
    models = models - model,
  )
}

/**
 * Web search settings for AI discovery.
 *
 * Without a search provider the agent can still fetch pages it is
 * pointed at, but it cannot search the web.
 *
 * @property provider which search API to use.
 * @property apiKey Brave subscription token or Google API key.
 * @property cx Google Programmable Search engine id.
 */
@Serializable
data class SearchSettings(
  val provider: SearchProvider = SearchProvider.None,
  val apiKey: String = "",
  val cx: String = "",
) {
  /** `true` when every field the provider needs has a value. */
  val isComplete: Boolean
    get() = provider == SearchProvider.None ||
      ((!provider.requiresApiKey || apiKey.isNotBlank()) &&
        (!provider.requiresCx || cx.isNotBlank()))
}

/**
 * User-facing AI discovery settings, persisted under `[ai]`.
 *
 * A config written before several providers could be saved has one provider under `[ai.llm]`:
 * [ConfigStore] implementations load it as the one saved, active provider, and saving writes it
 * under `[[ai.providers]]`.
 *
 * @property enabled master switch for the feature, on by default: discovery runs once [llm] and
 *   [search] are complete, and the apps hide it until then.
 * @property providers the saved LLM providers, each with a unique [LlmSettings.id], under
 *   `[[ai.providers]]`; empty until one is saved, when discovery calls a default OpenAI one.
 * @property active id of the provider discovery calls; the first one when no provider has it.
 * @property search web search settings.
 * @property access when discovery may open websites, under `[ai.access]`.
 * @property contentFilter whether discovery hides results that look unsafe, such as links
 *   through URL shorteners or download aggregators, piracy, look-alike websites and installers
 *   over plain HTTP. Private and local addresses are refused either way.
 * @property legacyLlm the one provider of a config written before several could be saved, as
 *   read from `[ai.llm]`; `null` once loaded, and never written.
 */
@Serializable
data class AiSettings(
  val enabled: Boolean = true,
  val providers: List<LlmSettings> = emptyList(),
  val active: String = "",
  val search: SearchSettings = SearchSettings(),
  val access: PageAccessSettings = PageAccessSettings(),
  val contentFilter: Boolean = true,
  @SerialName("llm") val legacyLlm: LlmSettings? = null,
) {
  /**
   * Settings with [llm] as the one saved provider, active. It keeps its id, or takes its
   * provider's.
   */
  constructor(
    enabled: Boolean = true,
    llm: LlmSettings,
    search: SearchSettings = SearchSettings(),
    access: PageAccessSettings = PageAccessSettings(),
    contentFilter: Boolean = true,
  ) : this(
    enabled = enabled,
    providers = listOf(llm.copy(id = llm.id.ifBlank { llm.provider.id })),
    active = llm.id.ifBlank { llm.provider.id },
    search = search,
    access = access,
    contentFilter = contentFilter,
  )

  /** The providers to choose from: the saved ones, or the default one while none is saved. */
  val entries: List<LlmSettings>
    get() = providers.ifEmpty { listOf(LlmSettings()) }

  /** The provider discovery calls. */
  val llm: LlmSettings
    get() = providers.firstOrNull { it.id == active } ?: entries.first()

  /** `true` when discovery is switched on and fully configured. */
  val isUsable: Boolean
    get() = enabled && llm.isComplete && search.isComplete

  /**
   * What the discovery engine runs with: the active provider, without its name and the models
   * it is not calling, and the search. Two settings with the same engine settings build the same
   * engine; [access] and [contentFilter] are given to each search instead.
   */
  val engineSettings: AiSettings
    get() {
      val llm = llm
      return AiSettings(
        enabled = enabled,
        providers = providers.filter { it.id == llm.id }.take(1)
          .map { it.copy(name = "", models = emptyList()) },
        active = llm.id,
        search = search,
      )
    }

  /** The saved provider with [id], or the default one for a blank id while none is saved. */
  fun entry(id: String): LlmSettings? = entries.firstOrNull { it.id == id }

  /**
   * A new provider of [provider], not saved yet: its id is unused, and it is named after the
   * provider, with a number when another provider is called that already.
   */
  fun newEntry(provider: LlmProvider): LlmSettings {
    val ids = providers.mapTo(HashSet()) { it.id }
    val id = generateSequence(1) { it + 1 }
      .map { if (it == 1) provider.id else "${provider.id}-$it" }
      .first { it !in ids }
    val names = providers.mapTo(HashSet()) { it.displayName }
    val name = generateSequence(1) { it + 1 }
      .map { if (it == 1) provider.label else "${provider.label} $it" }
      .first { it !in names }
    // The first is called by the provider's name, which needs no saving.
    return LlmSettings(
      id = id,
      name = if (name == provider.label) "" else name,
      provider = provider,
    )
  }

  /**
   * Saves [entry] in place of the saved provider with its id, or adds it. One without an id,
   * such as the default provider being edited, gets one. While no provider is saved, the one
   * saved first becomes active.
   */
  fun withEntry(entry: LlmSettings): AiSettings {
    val saved = if (entry.id.isBlank()) {
      entry.copy(id = newEntry(entry.provider).id)
    } else {
      entry
    }
    val index = providers.indexOfFirst { it.id == saved.id }
    if (index >= 0) {
      return copy(providers = providers.toMutableList().also { it[index] = saved })
    }
    return copy(
      providers = providers + saved,
      active = if (providers.isEmpty()) saved.id else active,
    )
  }

  /**
   * Removes the saved provider with [id]. Removing the active one makes the first that is left
   * active; removing the last brings back the default provider.
   */
  fun withoutEntry(id: String): AiSettings {
    val left = providers.filterNot { it.id == id }
    if (left.size == providers.size) return this
    val active = if (llm.id == id) left.firstOrNull()?.id.orEmpty() else llm.id
    return copy(providers = left, active = active)
  }

  /**
   * Makes the provider with [id] the one discovery calls, with [model] when it is given. The
   * default provider, while none is saved, is saved by choosing a model for it.
   */
  fun withActive(id: String, model: String? = null): AiSettings {
    val entry = entry(id) ?: return this
    val chosen = if (model == null) this else withEntry(entry.withModel(model))
    val savedId = chosen.providers.firstOrNull { it.id == id }?.id
      ?: chosen.providers.singleOrNull()?.id
      ?: return chosen
    return chosen.copy(active = savedId)
  }

  /**
   * These settings as loaded from a file: the provider of a config written before several could
   * be saved becomes the one saved, active provider, unless it was left untouched, and every saved
   * provider has an id the others do not, which a hand-edited file may lack.
   */
  internal fun migrated(): AiSettings {
    val legacy = legacyLlm?.takeIf { it != LlmSettings() && providers.isEmpty() }
    if (legacy != null) {
      return copy(legacyLlm = null, providers = emptyList(), active = "").withEntry(legacy)
    }
    val ids = providers.map { it.id }
    val unique = ids.none { it.isBlank() } && ids.distinct().size == ids.size
    if (unique && legacyLlm == null) return this
    var settings = copy(legacyLlm = null, providers = emptyList())
    for (entry in providers) {
      val taken = entry.id.isBlank() || settings.providers.any { it.id == entry.id }
      settings = settings.withEntry(if (taken) entry.copy(id = "") else entry)
    }
    // The active provider keeps its place; one whose id was taken was the first with it.
    return settings.copy(active = active)
  }
}
