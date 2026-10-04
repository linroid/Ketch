package com.linroid.ketch.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.KeyboardType
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
import com.linroid.ketch.app.state.AppState
import com.linroid.ketch.app.state.parseHostList
import com.linroid.ketch.app.theme.KetchTheme
import com.linroid.ketch.config.AiSettings
import com.linroid.ketch.config.LlmProvider
import com.linroid.ketch.config.PageAccessMode
import com.linroid.ketch.config.PageAccessSettings
import com.linroid.ketch.config.SearchProvider
import com.linroid.ketch.config.SiteNames
import ketch.app.shared.generated.resources.Res
import ketch.app.shared.generated.resources.action_add
import ketch.app.shared.generated.resources.settings_ai_access_add
import ketch.app.shared.generated.resources.settings_ai_access_add_invalid
import ketch.app.shared.generated.resources.settings_ai_access_allow
import ketch.app.shared.generated.resources.settings_ai_access_allow_hint
import ketch.app.shared.generated.resources.settings_ai_access_ask
import ketch.app.shared.generated.resources.settings_ai_access_ask_hint
import ketch.app.shared.generated.resources.settings_ai_access_ask_site
import ketch.app.shared.generated.resources.settings_ai_access_ask_site_hint
import ketch.app.shared.generated.resources.settings_ai_access_footer
import ketch.app.shared.generated.resources.settings_ai_access_group
import ketch.app.shared.generated.resources.settings_ai_access_mode
import ketch.app.shared.generated.resources.settings_ai_access_trusted
import ketch.app.shared.generated.resources.settings_ai_access_trusted_hint
import ketch.app.shared.generated.resources.settings_ai_access_trusted_none
import ketch.app.shared.generated.resources.settings_ai_api_key
import ketch.app.shared.generated.resources.settings_ai_api_key_env
import ketch.app.shared.generated.resources.settings_ai_api_key_plain
import ketch.app.shared.generated.resources.settings_ai_connected
import ketch.app.shared.generated.resources.settings_ai_connected_in
import ketch.app.shared.generated.resources.settings_ai_content_filter
import ketch.app.shared.generated.resources.settings_ai_content_filter_hint
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
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import kotlin.time.Duration
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * The content filter, page access, provider, credentials and web search for AI discovery, from
 * [state]'s AI settings. Every change is saved as it is made. Changes to the model or search
 * rebuild the discovery engine; page access and content filter changes keep it, so running
 * searches carry on, asking by the new rules, and the next search filters by the new setting.
 * Test calls the provider with the saved settings. While discovery is switched off, everything
 * but its switch is disabled, keeping what was entered for when it is switched back on.
 */
@Composable
fun AiDiscoverySettings(state: AppState) {
  val ai = state.aiSettings
  val settings = ai.settings
  val supported = ai.supported
  // Everything under the switch follows it.
  val editable = supported && settings.enabled
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
      title = stringResource(Res.string.settings_ai_discovery),
      description = status.resolve(),
      descriptionColor = statusColor,
      checked = settings.enabled,
      enabled = supported,
      onCheckedChange = { onChange(settings.copy(enabled = it)) },
    )
    // Each search reads it as it starts, so the engine carries on.
    SettingsSwitchRow(
      title = stringResource(Res.string.settings_ai_content_filter),
      description = stringResource(Res.string.settings_ai_content_filter_hint),
      checked = settings.contentFilter,
      enabled = editable,
      onCheckedChange = { onChange(settings.copy(contentFilter = it)) },
    )
  }

  PageAccessGroup(access = settings.access, enabled = editable, onChange = ai::saveAccess)

  SettingsGroup(title = stringResource(Res.string.settings_ai_model_group)) {
    SettingsRow(
      title = stringResource(Res.string.settings_ai_provider),
      description = providerHint(llm.provider).resolve(),
      enabled = editable,
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
            enabled = editable,
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
        enabled = editable,
      ) {
        SettingsTextInput(
          value = llm.apiKey,
          onCommit = { onChange(settings.copy(llm = llm.copy(apiKey = it))) },
          placeholder = tokenPlaceholder(llm.provider),
          secret = true,
          mono = true,
          enabled = editable,
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
      enabled = editable,
    ) {
      SettingsTextInput(
        value = llm.model,
        onCommit = { onChange(settings.copy(llm = llm.copy(model = it))) },
        placeholder = llm.provider.defaultModel
          .ifBlank { stringResource(Res.string.settings_ai_model_placeholder) },
        mono = true,
        enabled = editable,
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
              enabled = editable,
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
      enabled = editable,
    ) {
      SettingsTextInput(
        value = llm.baseUrl,
        onCommit = { onChange(settings.copy(llm = llm.copy(baseUrl = it))) },
        placeholder = llm.provider.defaultBaseUrl.ifBlank { "https://openrouter.ai/api/v1" },
        mono = true,
        enabled = editable,
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
      enabled = editable,
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
            state.launchCommand { ai.testConnection(ai.settings) }
          },
          variant = KetchButtonVariant.Secondary,
          size = KetchButtonSize.Small,
          enabled = editable && effective.llm.isComplete && !testing,
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
      enabled = editable,
      onSelect = { onChange(settings.copy(search = search.copy(provider = it))) },
    )
    if (search.provider.requiresApiKey) {
      SettingsRow(
        title = stringResource(Res.string.settings_ai_search_api_key),
        enabled = editable,
      ) {
        SettingsTextInput(
          value = search.apiKey,
          onCommit = { onChange(settings.copy(search = search.copy(apiKey = it))) },
          placeholder = stringResource(Res.string.settings_ai_api_key),
          secret = true,
          mono = true,
          enabled = editable,
        )
      }
    }
    if (search.provider.requiresCx) {
      SettingsRow(
        title = stringResource(Res.string.settings_ai_engine_id),
        description = stringResource(Res.string.settings_ai_engine_id_hint),
        enabled = editable,
      ) {
        SettingsTextInput(
          value = search.cx,
          onCommit = { onChange(settings.copy(search = search.copy(cx = it))) },
          placeholder = stringResource(Res.string.settings_ai_engine_id_placeholder),
          mono = true,
          enabled = editable,
        )
      }
    }
  }
}

/**
 * Whether discovery asks before it opens a website, and the sites it opens without asking.
 *
 * @param onChange saves what the transform it is given makes of the current page access, as
 *   `AiSettingsController.saveAccess` does, without rebuilding the discovery engine.
 */
@Composable
private fun PageAccessGroup(
  access: PageAccessSettings,
  enabled: Boolean,
  onChange: ((PageAccessSettings) -> PageAccessSettings) -> Unit,
) {
  SettingsGroup(
    title = stringResource(Res.string.settings_ai_access_group),
    footer = stringResource(Res.string.settings_ai_access_footer),
  ) {
    SettingsSelectRow(
      title = stringResource(Res.string.settings_ai_access_mode),
      description = stringResource(access.mode.hint),
      value = access.mode,
      options = PageAccessMode.entries,
      label = { it.label },
      enabled = enabled,
      onSelect = { mode -> onChange { it.copy(mode = mode) } },
    )
    TrustedSitesRow(sites = access.trustedSites, enabled = enabled, onChange = onChange)
  }
}

/**
 * The sites discovery opens without asking, as chips that remove them, over a field that adds
 * the sites typed in it. What does not name a site stays in the field, marked as an error.
 */
@Composable
private fun TrustedSitesRow(
  sites: List<String>,
  enabled: Boolean,
  onChange: ((PageAccessSettings) -> PageAccessSettings) -> Unit,
) {
  val spacing = KetchTheme.spacing
  var text by remember { mutableStateOf("") }
  var rejected by remember { mutableStateOf(false) }
  val add = {
    val typed = typedSites(text)
    if (typed.sites.isNotEmpty()) {
      onChange { access -> typed.sites.fold(access, PageAccessSettings::trusting) }
    }
    text = typed.rejected.joinToString(" ")
    rejected = typed.rejected.isNotEmpty()
  }
  SettingsRow(
    title = stringResource(Res.string.settings_ai_access_trusted),
    description = if (sites.isEmpty()) {
      stringResource(Res.string.settings_ai_access_trusted_none)
    } else {
      stringResource(Res.string.settings_ai_access_trusted_hint)
    },
    enabled = enabled,
  ) {
    if (sites.isNotEmpty()) {
      FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(spacing.s2),
        verticalArrangement = Arrangement.spacedBy(spacing.s2),
      ) {
        sites.forEach { site ->
          val remove = { onChange { it.distrusting(site) } }
          // A listed site reads as checked: unchecking it, like its ✕, stops trusting it.
          KetchChip(
            label = site,
            selected = true,
            onClick = remove,
            enabled = enabled,
            onRemove = remove,
          )
        }
      }
    }
    Row(
      verticalAlignment = Alignment.Top,
      horizontalArrangement = Arrangement.spacedBy(spacing.s2),
    ) {
      SettingsTextField(
        value = text,
        onValueChange = {
          text = it
          rejected = false
        },
        modifier = Modifier.weight(1f),
        placeholder = stringResource(Res.string.settings_ai_access_add),
        error = if (rejected) stringResource(Res.string.settings_ai_access_add_invalid) else null,
        onDone = add,
        keyboardType = KeyboardType.Uri,
        enabled = enabled,
      )
      KetchButton(
        text = stringResource(Res.string.action_add),
        onClick = add,
        variant = KetchButtonVariant.Secondary,
        enabled = enabled && text.isNotBlank(),
      )
    }
  }
}

/**
 * Sites typed in the Always allowed field.
 *
 * @property sites the ones that name a website, as [SiteNames.normalize] stores them, each once.
 * @property rejected the others, as they were typed.
 */
internal data class TypedSites(val sites: List<String>, val rejected: List<String>)

/**
 * Reads sites typed as "ubuntu.com, blender.org", as URLs or as `*.ubuntu.com`. A site is a
 * domain with a dot, such as ubuntu.com, or an IP address, so a stray word or number is rejected
 * rather than trusted.
 */
internal fun typedSites(text: String): TypedSites {
  val (sites, rejected) = parseHostList(text)
    .partition { SiteNames.normalize(it).matches(SiteName) }
  return TypedSites(sites.map(SiteNames::normalize).distinct(), rejected)
}

/**
 * A domain of two labels or more whose last label starts with a letter, such as ubuntu.com, an
 * IPv4 address, or an IPv6 address in brackets. Labels neither start nor end with a hyphen.
 */
private val SiteName = Regex(
  """([a-z0-9]([a-z0-9-]*[a-z0-9])?\.)+[a-z]([a-z0-9-]*[a-z0-9])?""" +
    """|\d{1,3}(\.\d{1,3}){3}|\[[0-9a-f.]*:[0-9a-f:.]*\]"""
)

/** How a page access mode reads in its menu. */
private val PageAccessMode.label: UiText
  get() = when (this) {
    PageAccessMode.Allow -> Res.string.settings_ai_access_allow
    PageAccessMode.AskPerSite -> Res.string.settings_ai_access_ask_site
    PageAccessMode.AskEveryTime -> Res.string.settings_ai_access_ask
  }.text()

/** What a page access mode does, under its row while it is chosen. */
private val PageAccessMode.hint: StringResource
  get() = when (this) {
    PageAccessMode.Allow -> Res.string.settings_ai_access_allow_hint
    PageAccessMode.AskPerSite -> Res.string.settings_ai_access_ask_site_hint
    PageAccessMode.AskEveryTime -> Res.string.settings_ai_access_ask_hint
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
    // Switched off, nothing below can be changed, so it says nothing of what is missing.
    !settings.enabled -> Res.string.settings_ai_status_off.text() to colors.textSecondary
    !effective.llm.isComplete ->
      Res.string.settings_ai_status_needs_provider.text() to colors.status.paused.color
    !effective.search.isComplete ->
      Res.string.settings_ai_status_needs_search.text() to colors.status.paused.color
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
