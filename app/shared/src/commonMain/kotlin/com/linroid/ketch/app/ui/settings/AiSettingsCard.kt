package com.linroid.ketch.app.ui.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.linroid.ketch.app.components.KetchButton
import com.linroid.ketch.app.components.KetchButtonSize
import com.linroid.ketch.app.components.KetchButtonVariant
import com.linroid.ketch.app.state.AiConnectionTest
import com.linroid.ketch.app.theme.KetchTheme
import com.linroid.ketch.config.AiSettings
import com.linroid.ketch.config.LlmProvider
import com.linroid.ketch.config.LlmSettings
import com.linroid.ketch.config.SearchProvider
import com.linroid.ketch.config.SearchSettings

/**
 * Editable copy of [AiSettings] so typing does not persist on every
 * keystroke.
 */
private class AiSettingsDraft(settings: AiSettings) {
  var enabled by mutableStateOf(settings.enabled)
  var provider by mutableStateOf(settings.llm.provider)
  var apiKey by mutableStateOf(settings.llm.apiKey)
  var model by mutableStateOf(settings.llm.model)
  var baseUrl by mutableStateOf(settings.llm.baseUrl)
  var searchProvider by mutableStateOf(settings.search.provider)
  var searchApiKey by mutableStateOf(settings.search.apiKey)
  var searchCx by mutableStateOf(settings.search.cx)

  fun toSettings(): AiSettings = AiSettings(
    enabled = enabled,
    llm = LlmSettings(
      provider = provider,
      apiKey = apiKey.trim(),
      model = model.trim(),
      baseUrl = baseUrl.trim(),
    ),
    search = SearchSettings(
      provider = searchProvider,
      apiKey = searchApiKey.trim(),
      cx = searchCx.trim(),
    ),
  )

  companion object {
    val Saver = listSaver<AiSettingsDraft, Any>(
      save = {
        listOf(
          it.enabled,
          it.provider.name,
          it.apiKey,
          it.model,
          it.baseUrl,
          it.searchProvider.name,
          it.searchApiKey,
          it.searchCx,
        )
      },
      restore = {
        AiSettingsDraft(
          AiSettings(
            enabled = it[0] as Boolean,
            llm = LlmSettings(
              provider = LlmProvider.valueOf(it[1] as String),
              apiKey = it[2] as String,
              model = it[3] as String,
              baseUrl = it[4] as String,
            ),
            search = SearchSettings(
              provider = SearchProvider.valueOf(it[5] as String),
              apiKey = it[6] as String,
              cx = it[7] as String,
            ),
          ),
        )
      },
    )
  }
}

/**
 * Provider picker and credential form for AI discovery.
 *
 * @param settings currently saved settings.
 * @param supported whether this platform can run discovery locally.
 * @param resolveCredentials fills the blank credentials the platform can
 *   supply (e.g. from the environment), so the form is judged the way
 *   the engine will see it.
 * @param connectionTest result of the last connection test.
 * @param onSave persist the edited settings.
 * @param onTest persist the edited settings and call the provider.
 * @param onUnsavedChange told whether the form differs from [settings].
 */
@Composable
fun AiSettingsCard(
  settings: AiSettings,
  supported: Boolean,
  resolveCredentials: (AiSettings) -> AiSettings,
  connectionTest: AiConnectionTest,
  compact: Boolean,
  onSave: (AiSettings) -> Unit,
  onTest: (AiSettings) -> Unit,
  modifier: Modifier = Modifier,
  onUnsavedChange: (Boolean) -> Unit = {},
) {
  val colors = KetchTheme.colors
  val draft = rememberSaveable(settings, saver = AiSettingsDraft.Saver) {
    AiSettingsDraft(settings)
  }
  var revealKey by remember { mutableStateOf(false) }
  val edited = draft.toSettings()
  // What the engine will actually run with: a blank token may still be
  // supplied by the environment.
  val effective = resolveCredentials(edited)
  val tokenFromEnvironment = edited.llm.apiKey.isBlank() &&
    effective.llm.apiKey.isNotBlank()
  val testing = connectionTest is AiConnectionTest.Running
  ReportUnsaved(supported && edited != settings, onUnsavedChange)

  SettingsCard(
    title = "AI discovery",
    description = "Describe what you need and let a model find the links. " +
      "The Discover tab appears once this works.",
    compact = compact,
    modifier = modifier,
  ) {
    Row(
      modifier = Modifier.fillMaxWidth(),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      Text(
        text = "Enable AI discovery",
        style = KetchTheme.typography.bodyMedium,
        color = colors.onBackground,
        modifier = Modifier.weight(1f),
      )
      Switch(
        checked = draft.enabled,
        onCheckedChange = { draft.enabled = it },
        enabled = supported,
        modifier = Modifier.semantics {
          contentDescription = "Enable AI discovery"
        },
      )
    }

    SettingsFieldLabel("Provider")
    FlowRow(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.spacedBy(8.dp),
      verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      LlmProvider.entries.forEach { provider ->
        FilterChip(
          selected = draft.provider == provider,
          // Model and endpoint are provider-specific, so switching
          // providers falls back to the new provider's defaults. The
          // token is kept — clearing a secret on a stray tap is worse
          // than a token the connection test will reject.
          onClick = {
            if (draft.provider != provider) {
              draft.provider = provider
              draft.model = ""
              draft.baseUrl = ""
            }
          },
          enabled = supported,
          label = {
            Text(provider.label, style = KetchTheme.typography.labelSmall)
          },
        )
      }
    }
    SettingsHint(providerHint(draft.provider))

    if (draft.provider.requiresApiKey) {
      OutlinedTextField(
        value = draft.apiKey,
        onValueChange = { draft.apiKey = it },
        label = { Text("API token") },
        placeholder = { Text(tokenPlaceholder(draft.provider)) },
        singleLine = true,
        enabled = supported,
        visualTransformation = if (revealKey) VisualTransformation.None
          else PasswordVisualTransformation(),
        trailingIcon = {
          KetchButton(
            text = if (revealKey) "Hide" else "Show",
            onClick = { revealKey = !revealKey },
            variant = KetchButtonVariant.Ghost,
            size = KetchButtonSize.Small,
          )
        },
        supportingText = {
          Text(
            if (tokenFromEnvironment) {
              "Leave empty to keep using the key from the environment."
            } else {
              "Stored as plain text in this device's config file."
            },
          )
        },
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth(),
      )
    }

    SettingsTextField(
      value = draft.model,
      onValueChange = { draft.model = it },
      label = "Model",
      placeholder = draft.provider.defaultModel.ifBlank { "Model name" },
      supportingText = if (draft.provider.defaultModel.isBlank()) {
        "Required — use the id your endpoint expects."
      } else {
        "Leave empty to use ${draft.provider.defaultModel}. " +
          "Any id your provider accepts works, and it must support tools."
      },
      enabled = supported,
    )
    val suggestions = modelSuggestions(draft.provider)
    if (suggestions.isNotEmpty()) {
      FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
      ) {
        suggestions.forEach { suggestion ->
          KetchButton(
            text = suggestion,
            onClick = { draft.model = suggestion },
            variant = KetchButtonVariant.Secondary,
            size = KetchButtonSize.Small,
            enabled = supported,
          )
        }
      }
    }

    SettingsTextField(
      value = draft.baseUrl,
      onValueChange = { draft.baseUrl = it },
      label = if (draft.provider.requiresBaseUrl) "Endpoint"
        else "Endpoint (optional)",
      placeholder = draft.provider.defaultBaseUrl
        .ifBlank { "https://openrouter.ai/api/v1" },
      supportingText = if (draft.provider.requiresBaseUrl) {
        "Any OpenAI-compatible endpoint, with or without /v1."
      } else {
        "Leave empty for ${draft.provider.defaultBaseUrl}."
      },
      enabled = supported,
    )

    HorizontalDivider(color = colors.outlineVariant)

    SettingsFieldLabel("Web search")
    SettingsHint(
      "Without a search provider the agent can only read pages it is " +
        "pointed at, so results stay thin.",
    )
    FlowRow(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.spacedBy(8.dp),
      verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      SearchProvider.entries.forEach { provider ->
        FilterChip(
          selected = draft.searchProvider == provider,
          onClick = { draft.searchProvider = provider },
          enabled = supported,
          label = {
            Text(provider.label, style = KetchTheme.typography.labelSmall)
          },
        )
      }
    }
    AnimatedVisibility(draft.searchProvider.requiresApiKey) {
      Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedTextField(
          value = draft.searchApiKey,
          onValueChange = { draft.searchApiKey = it },
          label = { Text("Search API key") },
          singleLine = true,
          enabled = supported,
          visualTransformation = PasswordVisualTransformation(),
          shape = RoundedCornerShape(12.dp),
          modifier = Modifier.fillMaxWidth(),
        )
        if (draft.searchProvider.requiresCx) {
          SettingsTextField(
            value = draft.searchCx,
            onValueChange = { draft.searchCx = it },
            label = "Search engine id",
            placeholder = "Programmable Search engine id",
            enabled = supported,
          )
        }
      }
    }

    AiSettingsStatus(
      edited = edited,
      effective = effective,
      supported = supported,
      connectionTest = connectionTest,
    )

    FlowRow(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
      verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      KetchButton(
        text = if (testing) "Testing…" else "Test connection",
        onClick = { onTest(edited) },
        variant = KetchButtonVariant.Secondary,
        enabled = supported && effective.llm.isComplete && !testing,
      )
      KetchButton(
        text = "Save",
        onClick = { onSave(edited) },
        enabled = supported && edited != settings,
      )
    }
  }
}

/**
 * @param edited the form as typed.
 * @param effective [edited] with platform-supplied credentials filled in.
 */
@Composable
private fun AiSettingsStatus(
  edited: AiSettings,
  effective: AiSettings,
  supported: Boolean,
  connectionTest: AiConnectionTest,
) {
  val colors = KetchTheme.colors
  val (message, color) = when {
    !supported -> "AI discovery runs in the desktop and Android apps." to
      colors.onSurfaceVariant
    !effective.llm.isComplete ->
      "Fill in the fields above to turn discovery on." to colors.warning
    !effective.search.isComplete ->
      "Add the missing web search credentials." to colors.warning
    !edited.enabled -> "Discovery is switched off." to colors.onSurfaceVariant
    else -> "Ready — ${effective.llm.provider.label} · " +
      "${effective.llm.effectiveModel}" to colors.success
  }
  Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
    Text(
      text = message,
      style = KetchTheme.typography.bodySmall,
      color = color,
    )
    if (effective != edited) {
      Text(
        text = "Blank credentials are filled from the environment.",
        style = KetchTheme.typography.bodySmall,
        color = colors.onSurfaceDim,
      )
    }
    when (connectionTest) {
      AiConnectionTest.Idle, AiConnectionTest.Running -> Unit
      is AiConnectionTest.Success -> Text(
        text = "Connection works. The model replied: " +
          connectionTest.reply.take(80),
        style = KetchTheme.typography.bodySmall,
        color = colors.success,
      )
      is AiConnectionTest.Failure -> Text(
        text = "Connection failed: ${connectionTest.message}",
        style = KetchTheme.typography.bodySmall,
        color = colors.error,
      )
    }
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
