package com.linroid.ketch.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.unit.dp
import com.linroid.ketch.app.components.KetchButton
import com.linroid.ketch.app.components.KetchButtonSize
import com.linroid.ketch.app.components.KetchButtonVariant
import com.linroid.ketch.app.state.AiConnectionTest
import com.linroid.ketch.app.theme.KetchTheme
import com.linroid.ketch.config.AiSettings
import com.linroid.ketch.config.LlmProvider
import com.linroid.ketch.config.SearchProvider

/**
 * Provider, credentials and web search for AI discovery. Every change
 * is saved as it is made and rebuilds the discovery engine.
 *
 * @param settings currently saved settings.
 * @param supported whether this platform can run discovery locally.
 * @param resolveCredentials fills the blank credentials the platform can
 *   supply (e.g. from the environment), so the form is judged the way
 *   the engine will see it.
 * @param connectionTest result of the last connection test.
 * @param onChange persist edited settings.
 * @param onTest call the provider with the saved settings.
 */
@Composable
fun AiDiscoverySettings(
  settings: AiSettings,
  supported: Boolean,
  resolveCredentials: (AiSettings) -> AiSettings,
  connectionTest: AiConnectionTest,
  onChange: (AiSettings) -> Unit,
  onTest: () -> Unit,
) {
  val focusManager = LocalFocusManager.current
  // What the engine will actually run with: a blank token may still be
  // supplied by the environment.
  val effective = resolveCredentials(settings)
  val llm = settings.llm
  val search = settings.search
  val tokenFromEnvironment = llm.apiKey.isBlank() && effective.llm.apiKey.isNotBlank()
  val testing = connectionTest is AiConnectionTest.Running

  Column(verticalArrangement = Arrangement.spacedBy(24.dp)) {
    SettingsGroup {
      val (status, statusColor) = discoveryStatus(settings, effective, supported)
      SettingsSwitchRow(
        title = "AI discovery",
        description = status,
        descriptionColor = statusColor,
        checked = settings.enabled,
        enabled = supported,
        onCheckedChange = { onChange(settings.copy(enabled = it)) },
      )
    }

    SettingsGroup(title = "Model") {
      SettingsSelectRow(
        title = "Provider",
        description = providerHint(llm.provider),
        value = llm.provider,
        options = LlmProvider.entries,
        label = { it.label },
        enabled = supported,
        // Model and endpoint are provider-specific, so switching falls
        // back to the new provider's defaults. The token is kept —
        // clearing a secret on a stray tap is worse than a token the
        // connection test will reject.
        onSelect = { provider ->
          onChange(settings.copy(llm = llm.copy(provider = provider, model = "", baseUrl = "")))
        },
      )
      if (llm.provider.requiresApiKey) {
        SettingsRow(
          title = "API key",
          description = if (tokenFromEnvironment) {
            "Using the key from the environment. Enter one here to override it."
          } else {
            "Stored as plain text in this device's config file."
          },
          enabled = supported,
        ) {
          SettingsTextInput(
            value = llm.apiKey,
            onCommit = { onChange(settings.copy(llm = llm.copy(apiKey = it))) },
            placeholder = tokenPlaceholder(llm.provider),
            secret = true,
            mono = true,
            enabled = supported,
          )
        }
      }
      SettingsRow(
        title = "Model",
        description = if (llm.provider.defaultModel.isBlank()) {
          "Required. Use the id your endpoint expects; it must support tools."
        } else {
          "Leave empty for ${llm.provider.defaultModel}. Any model with tool support works."
        },
        enabled = supported,
      ) {
        SettingsTextInput(
          value = llm.model,
          onCommit = { onChange(settings.copy(llm = llm.copy(model = it))) },
          placeholder = llm.provider.defaultModel.ifBlank { "Model id" },
          mono = true,
          enabled = supported,
        )
        val suggestions = modelSuggestions(llm.provider)
        if (suggestions.isNotEmpty()) {
          FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
          ) {
            suggestions.forEach { suggestion ->
              KetchButton(
                text = suggestion,
                onClick = { onChange(settings.copy(llm = llm.copy(model = suggestion))) },
                variant = if (suggestion == llm.effectiveModel) {
                  KetchButtonVariant.Secondary
                } else {
                  KetchButtonVariant.Ghost
                },
                size = KetchButtonSize.Small,
                enabled = supported,
              )
            }
          }
        }
      }
      SettingsRow(
        title = if (llm.provider.requiresBaseUrl) "Endpoint" else "Endpoint (optional)",
        description = if (llm.provider.requiresBaseUrl) {
          "Any OpenAI-compatible endpoint, with or without /v1."
        } else {
          "Leave empty for ${llm.provider.defaultBaseUrl}."
        },
        enabled = supported,
      ) {
        SettingsTextInput(
          value = llm.baseUrl,
          onCommit = { onChange(settings.copy(llm = llm.copy(baseUrl = it))) },
          placeholder = llm.provider.defaultBaseUrl.ifBlank { "https://openrouter.ai/api/v1" },
          mono = true,
          enabled = supported,
        )
      }
      val (testMessage, testColor) = testStatus(connectionTest)
      SettingsRow(
        title = "Test connection",
        description = testMessage,
        descriptionColor = testColor,
        enabled = supported,
        trailing = {
          KetchButton(
            text = if (testing) "Testing…" else "Test",
            onClick = {
              // Leaving the field saves what was just typed.
              focusManager.clearFocus()
              onTest()
            },
            variant = KetchButtonVariant.Secondary,
            size = KetchButtonSize.Small,
            enabled = supported && effective.llm.isComplete && !testing,
          )
        },
      )
    }

    SettingsGroup(
      title = "Web search",
      footer = "Without a search provider the agent can only read pages you point it " +
        "at, so results stay thin.",
    ) {
      SettingsSelectRow(
        title = "Search provider",
        value = search.provider,
        options = SearchProvider.entries,
        label = { it.label },
        enabled = supported,
        onSelect = { onChange(settings.copy(search = search.copy(provider = it))) },
      )
      if (search.provider.requiresApiKey) {
        SettingsRow(title = "Search API key", enabled = supported) {
          SettingsTextInput(
            value = search.apiKey,
            onCommit = { onChange(settings.copy(search = search.copy(apiKey = it))) },
            placeholder = "API key",
            secret = true,
            mono = true,
            enabled = supported,
          )
        }
      }
      if (search.provider.requiresCx) {
        SettingsRow(
          title = "Search engine ID",
          description = "The Programmable Search engine to query.",
          enabled = supported,
        ) {
          SettingsTextInput(
            value = search.cx,
            onCommit = { onChange(settings.copy(search = search.copy(cx = it))) },
            placeholder = "Engine ID",
            mono = true,
            enabled = supported,
          )
        }
      }
    }
  }
}

/**
 * @param settings the saved settings.
 * @param effective [settings] with platform-supplied credentials filled in.
 */
@Composable
private fun discoveryStatus(
  settings: AiSettings,
  effective: AiSettings,
  supported: Boolean,
): Pair<String, Color> {
  val colors = KetchTheme.colors
  return when {
    !supported -> "Runs in the desktop and Android apps." to colors.onSurfaceDim
    !effective.llm.isComplete ->
      "Choose a provider and add its key below to use discovery." to colors.warning
    !effective.search.isComplete -> "Add the missing web search credentials." to colors.warning
    !settings.enabled -> "Off. The Discover tab appears when this is on." to
      colors.onSurfaceVariant
    else -> "Ready — ${effective.llm.provider.label} · ${effective.llm.effectiveModel}" to
      colors.success
  }
}

@Composable
private fun testStatus(test: AiConnectionTest): Pair<String, Color> {
  val colors = KetchTheme.colors
  return when (test) {
    AiConnectionTest.Idle -> "Sends a short prompt to check the key and model." to
      colors.onSurfaceVariant
    AiConnectionTest.Running -> "Waiting for the model…" to colors.onSurfaceVariant
    is AiConnectionTest.Success -> "It works. The model replied: ${test.reply.take(80)}" to
      colors.success
    is AiConnectionTest.Failure -> "Failed: ${test.message}" to colors.error
  }
}

private fun tokenPlaceholder(provider: LlmProvider): String =
  when (provider) {
    LlmProvider.OpenAi, LlmProvider.OpenAiCompatible -> "sk-…"
    LlmProvider.Anthropic -> "sk-ant-…"
    LlmProvider.Google -> "AIza…"
    LlmProvider.Ollama -> ""
  }

private fun providerHint(provider: LlmProvider): String = when (provider) {
  LlmProvider.OpenAi ->
    "Create a key under API keys at platform.openai.com."
  LlmProvider.Anthropic ->
    "Create a key under API keys at console.anthropic.com."
  LlmProvider.Google ->
    "Create a key in Google AI Studio at aistudio.google.com/apikey."
  LlmProvider.Ollama ->
    "No key needed. Run Ollama locally and pull a model first, " +
      "for example: ollama pull qwen2.5:7b."
  LlmProvider.OpenAiCompatible ->
    "For OpenRouter, DeepSeek, LM Studio, vLLM and similar servers — " +
      "use their endpoint and key."
}

/**
 * Current models per provider, most capable first; the field stays free
 * text so a model released after this list still works.
 */
private fun modelSuggestions(provider: LlmProvider): List<String> =
  when (provider) {
    LlmProvider.OpenAi ->
      listOf("gpt-6-astra", "gpt-5.6-sol", "gpt-5.6-terra", "gpt-5.6-luna")
    LlmProvider.Anthropic ->
      listOf("claude-opus-5", "claude-sonnet-5", "claude-haiku-4-5")
    LlmProvider.Google ->
      listOf("gemini-3.8-flash", "gemini-3.7-flash", "gemini-3.5-flash-lite")
    LlmProvider.Ollama -> listOf("qwen3", "llama3.1:8b", "gemma4")
    LlmProvider.OpenAiCompatible -> emptyList()
  }
