package com.linroid.ketch.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import com.linroid.ketch.app.i18n.UiText
import com.linroid.ketch.app.i18n.decimal
import com.linroid.ketch.app.i18n.resolve
import com.linroid.ketch.app.i18n.text
import com.linroid.ketch.app.i18n.verbatim
import com.linroid.ketch.app.state.AiConnectionTest
import com.linroid.ketch.app.theme.KetchTheme
import com.linroid.ketch.config.AiSettings
import com.linroid.ketch.config.LlmProvider
import com.linroid.ketch.config.SearchProvider
import ketch.app.shared.generated.resources.Res
import ketch.app.shared.generated.resources.settings_ai_api_key
import ketch.app.shared.generated.resources.settings_ai_api_key_env
import ketch.app.shared.generated.resources.settings_ai_api_key_plain
import ketch.app.shared.generated.resources.settings_ai_connected
import ketch.app.shared.generated.resources.settings_ai_connected_in
import ketch.app.shared.generated.resources.settings_ai_discovery
import ketch.app.shared.generated.resources.settings_ai_endpoint
import ketch.app.shared.generated.resources.settings_ai_endpoint_compatible_hint
import ketch.app.shared.generated.resources.settings_ai_endpoint_optional
import ketch.app.shared.generated.resources.settings_ai_engine_id
import ketch.app.shared.generated.resources.settings_ai_engine_id_hint
import ketch.app.shared.generated.resources.settings_ai_engine_id_placeholder
import ketch.app.shared.generated.resources.settings_ai_hint_anthropic
import ketch.app.shared.generated.resources.settings_ai_hint_compatible
import ketch.app.shared.generated.resources.settings_ai_hint_gemini
import ketch.app.shared.generated.resources.settings_ai_hint_ollama
import ketch.app.shared.generated.resources.settings_ai_hint_openai
import ketch.app.shared.generated.resources.settings_ai_model
import ketch.app.shared.generated.resources.settings_ai_model_any
import ketch.app.shared.generated.resources.settings_ai_model_group
import ketch.app.shared.generated.resources.settings_ai_model_placeholder
import ketch.app.shared.generated.resources.settings_ai_model_required
import ketch.app.shared.generated.resources.settings_ai_provider
import ketch.app.shared.generated.resources.settings_ai_provider_compatible
import ketch.app.shared.generated.resources.settings_ai_provider_compatible_name
import ketch.app.shared.generated.resources.settings_ai_provider_ollama
import ketch.app.shared.generated.resources.settings_ai_search_api_key
import ketch.app.shared.generated.resources.settings_ai_search_footer
import ketch.app.shared.generated.resources.settings_ai_search_hint_brave
import ketch.app.shared.generated.resources.settings_ai_search_hint_google
import ketch.app.shared.generated.resources.settings_ai_search_none
import ketch.app.shared.generated.resources.settings_ai_search_provider
import ketch.app.shared.generated.resources.settings_ai_status_needs_provider
import ketch.app.shared.generated.resources.settings_ai_status_needs_search
import ketch.app.shared.generated.resources.settings_ai_status_off
import ketch.app.shared.generated.resources.settings_ai_status_ready
import ketch.app.shared.generated.resources.settings_ai_status_unsupported
import ketch.app.shared.generated.resources.settings_ai_test
import ketch.app.shared.generated.resources.settings_ai_test_button
import ketch.app.shared.generated.resources.settings_ai_test_failed
import ketch.app.shared.generated.resources.settings_ai_test_idle
import ketch.app.shared.generated.resources.settings_ai_test_running
import ketch.app.shared.generated.resources.settings_ai_test_waiting
import ketch.app.shared.generated.resources.settings_ai_the_model
import ketch.app.shared.generated.resources.settings_ai_web_search
import org.jetbrains.compose.resources.stringResource
import kotlin.time.Duration
import kotlin.time.TimeMark
import kotlin.time.TimeSource

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

  Column(verticalArrangement = Arrangement.spacedBy(KetchTheme.spacing.sectionGap)) {
    SettingsGroup {
      val (status, statusColor) = discoveryStatus(settings, effective, supported)
      SettingsSwitchRow(
        title = stringResource(Res.string.settings_ai_discovery),
        description = status.resolve(),
        descriptionColor = statusColor,
        checked = settings.enabled,
        enabled = supported,
        onCheckedChange = { onChange(settings.copy(enabled = it)) },
      )
    }

    SettingsGroup(title = stringResource(Res.string.settings_ai_model_group)) {
      SettingsRow(
        title = stringResource(Res.string.settings_ai_provider),
        description = providerHint(llm.provider).resolve(),
        enabled = supported,
      ) {
        FlowRow(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.spacedBy(KetchTheme.spacing.s2),
          verticalArrangement = Arrangement.spacedBy(KetchTheme.spacing.s2),
        ) {
          LlmProvider.entries.forEach { provider ->
            KetchChip(
              label = provider.buttonLabel.resolve(),
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
          title = stringResource(Res.string.settings_ai_api_key),
          description = if (tokenFromEnvironment) {
            stringResource(Res.string.settings_ai_api_key_env)
          } else {
            stringResource(Res.string.settings_ai_api_key_plain)
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
        title = stringResource(Res.string.settings_ai_model),
        description = if (llm.provider.defaultModel.isBlank()) {
          stringResource(Res.string.settings_ai_model_required)
        } else {
          stringResource(Res.string.settings_ai_model_any)
        },
        enabled = supported,
      ) {
        SettingsTextInput(
          value = llm.model,
          onCommit = { onChange(settings.copy(llm = llm.copy(model = it))) },
          placeholder = llm.provider.defaultModel
            .ifBlank { stringResource(Res.string.settings_ai_model_placeholder) },
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
        title = if (llm.provider.requiresBaseUrl) {
          stringResource(Res.string.settings_ai_endpoint)
        } else {
          stringResource(Res.string.settings_ai_endpoint_optional)
        },
        // The field shows the default endpoint while it is empty.
        description = if (llm.provider.requiresBaseUrl) {
          stringResource(Res.string.settings_ai_endpoint_compatible_hint)
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
        title = stringResource(Res.string.settings_ai_test),
        description = testMessage.resolve(),
        descriptionColor = testColor,
        enabled = supported,
        trailing = {
          KetchButton(
            text = if (testing) {
              stringResource(Res.string.settings_ai_test_running)
            } else {
              stringResource(Res.string.settings_ai_test_button)
            },
            onClick = {
              // Leaving the field saves what was just typed.
              focusManager.clearFocus()
              testStarted = TimeSource.Monotonic.markNow()
              testTook = null
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
      title = stringResource(Res.string.settings_ai_web_search),
      footer = stringResource(Res.string.settings_ai_search_footer),
    ) {
      SettingsSelectRow(
        title = stringResource(Res.string.settings_ai_search_provider),
        description = searchProviderHint(search.provider)?.let { stringResource(it) },
        value = search.provider,
        options = SearchProvider.entries,
        label = { it.displayName },
        enabled = supported,
        onSelect = { onChange(settings.copy(search = search.copy(provider = it))) },
      )
      if (search.provider.requiresApiKey) {
        SettingsRow(
          title = stringResource(Res.string.settings_ai_search_api_key),
          enabled = supported,
        ) {
          SettingsTextInput(
            value = search.apiKey,
            onCommit = { onChange(settings.copy(search = search.copy(apiKey = it))) },
            placeholder = stringResource(Res.string.settings_ai_api_key),
            secret = true,
            mono = true,
            enabled = supported,
          )
        }
      }
      if (search.provider.requiresCx) {
        SettingsRow(
          title = stringResource(Res.string.settings_ai_engine_id),
          description = stringResource(Res.string.settings_ai_engine_id_hint),
          enabled = supported,
        ) {
          SettingsTextInput(
            value = search.cx,
            onCommit = { onChange(settings.copy(search = search.copy(cx = it))) },
            placeholder = stringResource(Res.string.settings_ai_engine_id_placeholder),
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
): Pair<UiText, Color> {
  val colors = KetchTheme.colors
  return when {
    !supported -> Res.string.settings_ai_status_unsupported.text() to colors.textTertiary
    !effective.llm.isComplete ->
      Res.string.settings_ai_status_needs_provider.text() to colors.status.paused.color
    !effective.search.isComplete ->
      Res.string.settings_ai_status_needs_search.text() to colors.status.paused.color
    !settings.enabled -> Res.string.settings_ai_status_off.text() to colors.textSecondary
    else -> Res.string.settings_ai_status_ready.text(
      effective.llm.provider.displayName,
      effective.llm.effectiveModel,
    ) to colors.status.completed.color
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
): Pair<UiText, Color> {
  val colors = KetchTheme.colors
  return when (test) {
    AiConnectionTest.Idle -> Res.string.settings_ai_test_idle.text() to colors.textSecondary
    AiConnectionTest.Running -> Res.string.settings_ai_test_waiting.text() to colors.textSecondary
    is AiConnectionTest.Success -> connectedCopy(model, took) to colors.status.completed.color
    is AiConnectionTest.Failure ->
      Res.string.settings_ai_test_failed.text(test.message) to colors.status.failed.color
  }
}

/** "Connected · claude-sonnet-5 responded in 1.2 s", or without the time when it is unknown. */
internal fun connectedCopy(model: String, took: Duration?): UiText {
  val who = if (model.isBlank()) Res.string.settings_ai_the_model.text() else verbatim(model)
  if (took == null) return Res.string.settings_ai_connected.text(who)
  val tenths = took.inWholeMilliseconds / MILLIS_PER_TENTH
  return Res.string.settings_ai_connected_in.text(who, decimal(tenths / 10.0, 1))
}

private const val MILLIS_PER_TENTH = 100

/** How a provider reads in summaries and the status line: its name. */
internal val LlmProvider.displayName: UiText
  get() = when (this) {
    LlmProvider.OpenAiCompatible -> Res.string.settings_ai_provider_compatible_name.text()
    else -> verbatim(label)
  }

/** How a search provider reads in its menu and in summaries: its name, or "None". */
internal val SearchProvider.displayName: UiText
  get() = when (this) {
    SearchProvider.None -> Res.string.settings_ai_search_none.text()
    else -> verbatim(label)
  }

/** How a provider reads on its button. */
private val LlmProvider.buttonLabel: UiText
  get() = when (this) {
    LlmProvider.OpenAi -> verbatim("OpenAI")
    LlmProvider.Anthropic -> verbatim("Anthropic")
    LlmProvider.Google -> verbatim("Gemini")
    LlmProvider.Ollama -> Res.string.settings_ai_provider_ollama.text()
    LlmProvider.OpenAiCompatible -> Res.string.settings_ai_provider_compatible.text()
  }

private fun tokenPlaceholder(provider: LlmProvider): String =
  when (provider) {
    LlmProvider.OpenAi, LlmProvider.OpenAiCompatible -> "sk-…"
    LlmProvider.Anthropic -> "sk-ant-…"
    LlmProvider.Google -> "AIza…"
    LlmProvider.Ollama -> ""
  }

private fun providerHint(provider: LlmProvider): UiText = when (provider) {
  LlmProvider.OpenAi -> Res.string.settings_ai_hint_openai.text()
  LlmProvider.Anthropic -> Res.string.settings_ai_hint_anthropic.text()
  LlmProvider.Google -> Res.string.settings_ai_hint_gemini.text()
  LlmProvider.Ollama -> Res.string.settings_ai_hint_ollama.text(LlmProvider.Ollama.defaultModel)
  LlmProvider.OpenAiCompatible -> Res.string.settings_ai_hint_compatible.text()
}

private fun searchProviderHint(provider: SearchProvider) = when (provider) {
  SearchProvider.None -> null
  SearchProvider.Brave -> Res.string.settings_ai_search_hint_brave
  SearchProvider.Google -> Res.string.settings_ai_search_hint_google
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
