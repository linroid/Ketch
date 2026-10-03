package com.linroid.ketch.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import com.linroid.ketch.app.components.KetchButton
import com.linroid.ketch.app.components.KetchButtonSize
import com.linroid.ketch.app.components.KetchButtonVariant
import com.linroid.ketch.app.components.KetchChip
import com.linroid.ketch.app.state.AiConnectionTest
import com.linroid.ketch.app.state.AppState
import com.linroid.ketch.app.theme.KetchTheme
import com.linroid.ketch.config.AiSettings
import com.linroid.ketch.config.LlmProvider
import com.linroid.ketch.config.SearchProvider
import kotlin.time.Duration
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * Provider, credentials and web search for AI discovery, from [state]'s AI settings. Every
 * change is saved as it is made and rebuilds the discovery engine; Test calls the provider with
 * the saved settings.
 */
@Composable
fun AiDiscoverySettings(state: AppState) {
  val ai = state.aiSettings
  val settings = ai.settings
  val supported = ai.supported
  val connectionTest = ai.connectionTest
  val onChange = { changed: AiSettings -> ai.save(changed) }
  val focusManager = LocalFocusManager.current
  // What the engine will actually run with: a blank token may still be
  // supplied by the environment, which the form is judged by.
  val effective = ai.withPlatformCredentials(settings)
  val llm = settings.llm
  val search = settings.search
  val tokenFromEnvironment = llm.apiKey.isBlank() && effective.llm.apiKey.isNotBlank()
  val testing = connectionTest is AiConnectionTest.Running

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
    SettingsRow(
      title = "Provider",
      description = providerHint(llm.provider),
      enabled = supported,
    ) {
      FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(KetchTheme.spacing.s2),
        verticalArrangement = Arrangement.spacedBy(KetchTheme.spacing.s2),
      ) {
        LlmProvider.entries.forEach { provider ->
          KetchChip(
            label = provider.buttonLabel,
            selected = provider == llm.provider,
            enabled = supported,
            // Model and endpoint are provider-specific, so switching falls back to the new
            // provider's defaults. The token is kept: clearing a secret on a stray tap is
            // worse than a token the connection test will reject.
            onClick = {
              if (provider != llm.provider) {
                onChange(
                  settings.copy(llm = llm.copy(provider = provider, model = "", baseUrl = "")),
                )
              }
            },
          )
        }
      }
    }
    if (llm.provider.requiresApiKey) {
      SettingsRow(
        title = "API key",
        description = if (tokenFromEnvironment) {
          "Using the key from the environment. One here overrides it."
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
        "Required. Use a model with tool support."
      } else {
        "Any model with tool support works."
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
          horizontalArrangement = Arrangement.spacedBy(KetchTheme.spacing.s2),
          verticalArrangement = Arrangement.spacedBy(KetchTheme.spacing.s2),
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
      // The field shows the default endpoint while it is empty.
      description = if (llm.provider.requiresBaseUrl) {
        "Any OpenAI-compatible endpoint, with or without /v1."
      } else {
        null
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
    // How long the last test took, measured from the click.
    var testStarted by remember { mutableStateOf<TimeMark?>(null) }
    var testTook by remember { mutableStateOf<Duration?>(null) }
    LaunchedEffect(connectionTest) {
      if (connectionTest is AiConnectionTest.Success) {
        testTook = testStarted?.elapsedNow()
        testStarted = null
      }
    }
    val model = effective.llm.effectiveModel
    val (testMessage, testColor) = testStatus(connectionTest, model, testTook)
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
            testStarted = TimeSource.Monotonic.markNow()
            testTook = null
            state.launchCommand { ai.testConnection(ai.settings) }
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
    footer = "Without a search provider, Discover only reads the pages you give it.",
  ) {
    SettingsSelectRow(
      title = "Search provider",
      description = searchProviderHint(search.provider),
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
    !supported -> "Runs in the desktop and Android apps." to colors.textTertiary
    !effective.llm.isComplete ->
      "Choose a provider and add its key below." to colors.status.paused.color
    !effective.search.isComplete ->
      "Add the missing web search credentials." to colors.status.paused.color
    !settings.enabled -> "Lets Discover find downloads for you." to colors.textSecondary
    else -> "Ready · ${effective.llm.provider.label} · ${effective.llm.effectiveModel}" to
      colors.status.completed.color
  }
}

/**
 * @param model the model the test called.
 * @param took how long a successful test took, or `null` when it was not timed.
 */
@Composable
private fun testStatus(
  test: AiConnectionTest,
  model: String,
  took: Duration?,
): Pair<String, Color> {
  val colors = KetchTheme.colors
  return when (test) {
    AiConnectionTest.Idle -> "Sends a short prompt to check the key and model." to
      colors.textSecondary
    AiConnectionTest.Running -> "Waiting for the model…" to colors.textSecondary
    is AiConnectionTest.Success -> connectedCopy(model, took) to colors.status.completed.color
    is AiConnectionTest.Failure -> "Failed: ${test.message}" to colors.status.failed.color
  }
}

/** "Connected · claude-sonnet-5 responded in 1.2 s", or without the time when it is unknown. */
internal fun connectedCopy(model: String, took: Duration?): String {
  val who = model.ifBlank { "the model" }
  if (took == null) return "Connected · $who responded"
  val tenths = took.inWholeMilliseconds / MILLIS_PER_TENTH
  return "Connected · $who responded in ${tenths / 10}.${tenths % 10} s"
}

private const val MILLIS_PER_TENTH = 100

/** How a provider reads on its button. */
private val LlmProvider.buttonLabel: String
  get() = when (this) {
    LlmProvider.OpenAi -> "OpenAI"
    LlmProvider.Anthropic -> "Anthropic"
    LlmProvider.Google -> "Gemini"
    LlmProvider.Ollama -> "Ollama · runs locally, no key"
    LlmProvider.OpenAiCompatible -> "Custom (OpenAI-compatible)"
  }

private fun tokenPlaceholder(provider: LlmProvider): String =
  when (provider) {
    LlmProvider.OpenAi, LlmProvider.OpenAiCompatible -> "sk-…"
    LlmProvider.Anthropic -> "sk-ant-…"
    LlmProvider.Google -> "AIza…"
    LlmProvider.Ollama -> ""
  }

private fun providerHint(provider: LlmProvider): String = when (provider) {
  LlmProvider.OpenAi -> "Get a key at platform.openai.com."
  LlmProvider.Anthropic -> "Get a key at console.anthropic.com."
  LlmProvider.Google -> "Get a key at aistudio.google.com/apikey."
  LlmProvider.Ollama -> "Pull a model first: ollama pull ${LlmProvider.Ollama.defaultModel}"
  LlmProvider.OpenAiCompatible -> "OpenRouter, DeepSeek, LM Studio, vLLM and similar servers."
}

private fun searchProviderHint(provider: SearchProvider): String? = when (provider) {
  SearchProvider.None -> null
  SearchProvider.Brave -> "Get a token at api-dashboard.search.brave.com."
  SearchProvider.Google -> "Closed to new customers; it stops working on January 1, 2027."
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
