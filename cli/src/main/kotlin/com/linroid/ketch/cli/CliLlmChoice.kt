package com.linroid.ketch.cli

import com.linroid.ketch.config.AiSettings
import com.linroid.ketch.config.LlmProvider

/** The provider `ketch ai-discover` searches with, or why it cannot use it. */
internal sealed interface CliLlmChoice {
  /** [settings] with the chosen provider active. */
  data class Chosen(val settings: AiSettings) : CliLlmChoice

  /** `--provider` names nothing; [message] says what can be named. */
  data class Unknown(val message: String) : CliLlmChoice
}

/**
 * [settings], as loaded from `config.toml`, with the provider [provider] names active for one
 * run; nothing is saved. `null` keeps them as they are.
 *
 * [provider] is matched against the saved providers' ids, then their names, ignoring case, then
 * the ids and names of the providers Ketch knows, such as `deepseek`: the first saved one of that
 * provider, or a new one, whose API key the environment fills.
 */
internal fun chooseLlm(settings: AiSettings, provider: String?): CliLlmChoice {
  if (provider == null) return CliLlmChoice.Chosen(settings)
  val saved = settings.providers
  val entry = saved.firstOrNull { it.id == provider }
    ?: saved.firstOrNull { it.displayName.equals(provider, ignoreCase = true) }
    ?: knownProvider(provider)?.let { kind ->
      saved.firstOrNull { it.provider == kind } ?: settings.newEntry(kind)
    }
    ?: return CliLlmChoice.Unknown(unknownMessage(settings, provider))
  val withEntry = if (entry in saved) settings else settings.withEntry(entry)
  return CliLlmChoice.Chosen(withEntry.withActive(entry.id))
}

private fun knownProvider(name: String): LlmProvider? = LlmProvider.entries.firstOrNull {
  it.id.equals(name, ignoreCase = true) || it.label.equals(name, ignoreCase = true)
}

private fun unknownMessage(settings: AiSettings, name: String): String = buildString {
  append("No provider is called '$name'.")
  if (settings.providers.isNotEmpty()) {
    append(" Saved providers: ")
    append(settings.providers.joinToString { "${it.id} (${it.displayName})" })
    append('.')
  }
  append(" Others: ")
  append(LlmProvider.entries.filterNot { it.requiresBaseUrl }.joinToString { it.id })
  append('.')
}
