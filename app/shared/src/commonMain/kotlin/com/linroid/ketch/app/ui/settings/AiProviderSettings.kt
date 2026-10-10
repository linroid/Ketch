package com.linroid.ketch.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import com.linroid.ketch.app.components.KetchButton
import com.linroid.ketch.app.components.KetchButtonSize
import com.linroid.ketch.app.components.KetchButtonVariant
import com.linroid.ketch.app.components.KetchChip
import com.linroid.ketch.app.components.KetchEyebrow
import com.linroid.ketch.app.components.KetchSpinner
import com.linroid.ketch.app.i18n.UiText
import com.linroid.ketch.app.i18n.joinText
import com.linroid.ketch.app.i18n.resolve
import com.linroid.ketch.app.i18n.text
import com.linroid.ketch.app.i18n.verbatim
import com.linroid.ketch.app.icons.KetchIcon
import com.linroid.ketch.app.icons.KetchIconImage
import com.linroid.ketch.app.state.AiConnectionTest
import com.linroid.ketch.app.state.AiModelList
import com.linroid.ketch.app.state.AppState
import com.linroid.ketch.app.theme.KetchTheme
import com.linroid.ketch.app.ui.common.AdaptiveModal
import com.linroid.ketch.config.AiSettings
import com.linroid.ketch.config.LlmProvider
import com.linroid.ketch.config.LlmProviderGroup
import com.linroid.ketch.config.LlmSettings
import ketch.app.shared.generated.resources.Res
import ketch.app.shared.generated.resources.action_add
import ketch.app.shared.generated.resources.action_cancel
import ketch.app.shared.generated.resources.action_remove
import ketch.app.shared.generated.resources.action_show
import ketch.app.shared.generated.resources.settings_action_hide
import ketch.app.shared.generated.resources.settings_ai_api_key
import ketch.app.shared.generated.resources.settings_ai_api_key_env
import ketch.app.shared.generated.resources.settings_ai_api_key_plain
import ketch.app.shared.generated.resources.settings_ai_endpoint
import ketch.app.shared.generated.resources.settings_ai_endpoint_compatible_hint
import ketch.app.shared.generated.resources.settings_ai_endpoint_optional
import ketch.app.shared.generated.resources.settings_ai_hint_compatible
import ketch.app.shared.generated.resources.settings_ai_hint_key
import ketch.app.shared.generated.resources.settings_ai_hint_ollama
import ketch.app.shared.generated.resources.settings_ai_models
import ketch.app.shared.generated.resources.settings_ai_models_add_placeholder
import ketch.app.shared.generated.resources.settings_ai_models_available
import ketch.app.shared.generated.resources.settings_ai_models_fetch
import ketch.app.shared.generated.resources.settings_ai_models_hint
import ketch.app.shared.generated.resources.settings_ai_models_loading
import ketch.app.shared.generated.resources.settings_ai_models_no_match
import ketch.app.shared.generated.resources.settings_ai_provider
import ketch.app.shared.generated.resources.settings_ai_provider_add
import ketch.app.shared.generated.resources.settings_ai_provider_choose
import ketch.app.shared.generated.resources.settings_ai_provider_compatible
import ketch.app.shared.generated.resources.settings_ai_provider_edit
import ketch.app.shared.generated.resources.settings_ai_provider_edit_title
import ketch.app.shared.generated.resources.settings_ai_provider_env
import ketch.app.shared.generated.resources.settings_ai_provider_group_china
import ketch.app.shared.generated.resources.settings_ai_provider_group_custom
import ketch.app.shared.generated.resources.settings_ai_provider_group_hosted
import ketch.app.shared.generated.resources.settings_ai_provider_group_local
import ketch.app.shared.generated.resources.settings_ai_provider_in_use
import ketch.app.shared.generated.resources.settings_ai_provider_incomplete
import ketch.app.shared.generated.resources.settings_ai_provider_name
import ketch.app.shared.generated.resources.settings_ai_provider_name_hint
import ketch.app.shared.generated.resources.settings_ai_provider_ollama
import ketch.app.shared.generated.resources.settings_ai_provider_remove_confirm
import ketch.app.shared.generated.resources.settings_ai_provider_save
import ketch.app.shared.generated.resources.settings_ai_provider_use
import ketch.app.shared.generated.resources.settings_ai_providers
import ketch.app.shared.generated.resources.settings_ai_providers_footer
import ketch.app.shared.generated.resources.settings_ai_providers_none
import ketch.app.shared.generated.resources.settings_ai_providers_none_hint
import ketch.app.shared.generated.resources.settings_ai_test
import ketch.app.shared.generated.resources.settings_ai_test_button
import ketch.app.shared.generated.resources.settings_ai_test_running
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import kotlin.time.Duration
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/** Which provider the editor shows: a new one, or the saved one with [Edit.id]. */
private sealed interface ProviderEdit {
  data object Add : ProviderEdit

  data class Edit(val id: String) : ProviderEdit
}

/**
 * The saved LLM providers, each with its key, endpoint and models: the one discovery calls is
 * checked, Use switches to another and Edit opens it in the [ProviderEditor], as Add provider
 * does for a new one. While none is saved, a default provider the environment supplies a key for
 * is listed, and saved once edited.
 */
@Composable
internal fun ProvidersGroup(state: AppState, enabled: Boolean) {
  val ai = state.aiSettings
  val settings = ai.settings
  val effective = ai.withPlatformCredentials(settings)
  var editing by remember { mutableStateOf<ProviderEdit?>(null) }
  val listed = settings.providers.ifEmpty { effective.entries.filter { it.isComplete } }
  val add = { editing = ProviderEdit.Add }
  SettingsGroup(
    title = stringResource(Res.string.settings_ai_providers),
    footer = stringResource(Res.string.settings_ai_providers_footer),
    action = if (listed.isEmpty()) {
      null
    } else {
      {
        KetchButton(
          text = stringResource(Res.string.settings_ai_provider_add),
          onClick = add,
          variant = KetchButtonVariant.Ghost,
          size = KetchButtonSize.Small,
          leadingIcon = KetchIcon.Plus,
          enabled = enabled,
        )
      }
    },
  ) {
    if (listed.isEmpty()) {
      SettingsRow(
        title = stringResource(Res.string.settings_ai_providers_none),
        description = stringResource(Res.string.settings_ai_providers_none_hint),
        enabled = enabled,
        trailing = {
          KetchButton(
            text = stringResource(Res.string.settings_ai_provider_add),
            onClick = add,
            size = KetchButtonSize.Small,
            leadingIcon = KetchIcon.Plus,
            enabled = enabled,
          )
        },
      )
    }
    listed.forEach { entry ->
      ProviderRow(
        entry = entry,
        active = entry.id == settings.llm.id,
        complete = effective.entry(entry.id)?.isComplete == true,
        fromEnvironment = entry.apiKey.isBlank() && entry.provider.requiresApiKey,
        enabled = enabled,
        onUse = { ai.use(entry.id) },
        onEdit = { editing = ProviderEdit.Edit(entry.id) },
      )
    }
  }

  editing?.let { edit ->
    ProviderEditor(
      state = state,
      original = (edit as? ProviderEdit.Edit)?.let { settings.entry(it.id) },
      onDismiss = { editing = null },
    )
  }
}

/**
 * One saved provider: its name, then its provider when it is named otherwise, the model it
 * calls and what it lacks, with Use, or In use, and Edit.
 */
@Composable
private fun ProviderRow(
  entry: LlmSettings,
  active: Boolean,
  complete: Boolean,
  fromEnvironment: Boolean,
  enabled: Boolean,
  onUse: () -> Unit,
  onEdit: () -> Unit,
) {
  val colors = KetchTheme.colors
  val spacing = KetchTheme.spacing
  val glyph = KetchTheme.density.controlGlyph
  val description = listOfNotNull(
    entry.provider.displayName.takeIf { entry.name.isNotBlank() },
    entry.effectiveModel.takeIf { it.isNotBlank() }?.let(::verbatim),
    when {
      !complete -> Res.string.settings_ai_provider_incomplete.text()
      fromEnvironment -> Res.string.settings_ai_provider_env.text()
      else -> null
    },
  ).joinText()
  SettingsRow(
    title = entry.displayName,
    description = description.resolve(),
    descriptionColor = if (complete) colors.textSecondary else colors.status.paused.color,
    enabled = enabled,
    leading = {
      Box(Modifier.size(glyph)) {
        if (active) {
          KetchIconImage(
            icon = KetchIcon.CheckCircle,
            size = glyph,
            tint = if (enabled) colors.accent else colors.textDisabled,
          )
        }
      }
    },
    trailing = {
      Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(spacing.s1),
      ) {
        if (active) {
          SettingsValue(stringResource(Res.string.settings_ai_provider_in_use))
        } else {
          KetchButton(
            text = stringResource(Res.string.settings_ai_provider_use),
            onClick = onUse,
            variant = KetchButtonVariant.Ghost,
            size = KetchButtonSize.Small,
            enabled = enabled && complete,
          )
        }
        KetchButton(
          text = stringResource(Res.string.settings_ai_provider_edit),
          onClick = onEdit,
          variant = KetchButtonVariant.Secondary,
          size = KetchButtonSize.Small,
          enabled = enabled,
        )
      }
    },
  )
}

/**
 * Adds a provider, or edits [original]: the provider first, when adding, then its name, key,
 * endpoint and the models to choose from, with the one discovery calls selected. Models can be
 * typed or picked from those the provider lists, and Test calls it as it is filled in. Nothing
 * is saved until Add or Save; the editor only closes on its own while nothing was changed.
 */
@Composable
private fun ProviderEditor(state: AppState, original: LlmSettings?, onDismiss: () -> Unit) {
  val ai = state.aiSettings
  val spacing = KetchTheme.spacing
  var draft by remember { mutableStateOf(original) }
  var confirmRemove by remember { mutableStateOf(false) }
  val current = draft
  val complete = current != null && ai.withPlatformCredentials(AiSettings(llm = current))
    .llm.isComplete
  AdaptiveModal(
    onDismissRequest = onDismiss,
    dismissible = draft == original,
    title = {
      Text(
        if (original == null) {
          stringResource(Res.string.settings_ai_provider_add)
        } else {
          stringResource(Res.string.settings_ai_provider_edit_title, original.displayName)
        },
      )
    },
    contentSpacing = spacing.s5,
    dismissButton = {
      // Only a saved provider can be removed; the default one has nothing to remove.
      if (original != null && original.id in ai.settings.providers.map { it.id }) {
        KetchButton(
          text = if (confirmRemove) {
            stringResource(Res.string.settings_ai_provider_remove_confirm)
          } else {
            stringResource(Res.string.action_remove)
          },
          onClick = {
            if (confirmRemove) {
              ai.removeProvider(original.id)
              onDismiss()
            } else {
              confirmRemove = true
            }
          },
          variant = if (confirmRemove) KetchButtonVariant.Danger else KetchButtonVariant.Ghost,
        )
      }
      KetchButton(
        text = stringResource(Res.string.action_cancel),
        onClick = onDismiss,
        variant = KetchButtonVariant.Secondary,
      )
    },
    confirmButton = {
      KetchButton(
        text = if (original == null) {
          stringResource(Res.string.action_add)
        } else {
          stringResource(Res.string.settings_ai_provider_save)
        },
        onClick = {
          val entry = draft ?: return@KetchButton
          if (original == null) ai.addProvider(entry) else ai.saveProvider(entry)
          onDismiss()
        },
        enabled = complete,
      )
    },
  ) {
    if (original == null) {
      ProviderChoice(
        selected = current?.provider,
        onSelect = { provider ->
          if (provider != current?.provider) draft = ai.settings.newEntry(provider)
        },
      )
    }
    if (current != null) {
      ProviderFields(state, current, onChange = { draft = it })
    }
  }
}

/** The providers to add, grouped by where their models run. */
@Composable
private fun ProviderChoice(selected: LlmProvider?, onSelect: (LlmProvider) -> Unit) {
  val spacing = KetchTheme.spacing
  EditorSection(
    title = stringResource(Res.string.settings_ai_provider),
    hint = if (selected == null) {
      stringResource(Res.string.settings_ai_provider_choose)
    } else {
      providerHint(selected)?.resolve()
    },
  ) {
    LlmProvider.entries.groupBy { it.group }.forEach { (group, providers) ->
      Column(verticalArrangement = Arrangement.spacedBy(spacing.s1)) {
        KetchEyebrow(text = group.label.resolve(), color = KetchTheme.colors.textTertiary)
        FlowRow(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.spacedBy(spacing.s2),
          verticalArrangement = Arrangement.spacedBy(spacing.s2),
        ) {
          providers.forEach { provider ->
            KetchChip(
              label = provider.buttonLabel.resolve(),
              selected = provider == selected,
              onClick = { onSelect(provider) },
            )
          }
        }
      }
    }
  }
}

/** The name, key, endpoint, models and test of [entry], as it is being edited. */
@Composable
private fun ProviderFields(state: AppState, entry: LlmSettings, onChange: (LlmSettings) -> Unit) {
  val ai = state.aiSettings
  val provider = entry.provider
  EditorSection(
    title = stringResource(Res.string.settings_ai_provider_name),
    hint = stringResource(Res.string.settings_ai_provider_name_hint),
  ) {
    SettingsTextField(
      value = entry.name,
      onValueChange = { onChange(entry.copy(name = it)) },
      placeholder = provider.label,
    )
  }
  if (provider.requiresApiKey) {
    val fromEnvironment = entry.apiKey.isBlank() &&
      ai.withPlatformCredentials(AiSettings(llm = entry)).llm.apiKey.isNotBlank()
    EditorSection(
      title = stringResource(Res.string.settings_ai_api_key),
      hint = if (fromEnvironment) {
        stringResource(Res.string.settings_ai_api_key_env)
      } else {
        stringResource(Res.string.settings_ai_api_key_plain)
      },
    ) {
      SecretField(
        value = entry.apiKey,
        onValueChange = { onChange(entry.copy(apiKey = it.trim())) },
        placeholder = tokenPlaceholder(provider),
      )
    }
  }
  EndpointSection(entry, onChange)
  ModelsSection(state, entry, onChange)
  TestSection(state, entry)
}

@Composable
private fun EndpointSection(entry: LlmSettings, onChange: (LlmSettings) -> Unit) {
  val spacing = KetchTheme.spacing
  val provider = entry.provider
  EditorSection(
    title = if (provider.requiresBaseUrl) {
      stringResource(Res.string.settings_ai_endpoint)
    } else {
      stringResource(Res.string.settings_ai_endpoint_optional)
    },
    hint = if (provider.requiresBaseUrl) {
      stringResource(Res.string.settings_ai_endpoint_compatible_hint)
    } else {
      null
    },
  ) {
    SettingsTextField(
      value = entry.baseUrl,
      onValueChange = { onChange(entry.copy(baseUrl = it.trim())) },
      placeholder = provider.defaultBaseUrl.ifBlank { "https://openrouter.ai/api/v1" },
      keyboardType = KeyboardType.Uri,
      mono = true,
    )
    // Providers with sites in several regions, such as mainland China and international.
    if (provider.baseUrls.size > 1) {
      FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(spacing.s2),
        verticalArrangement = Arrangement.spacedBy(spacing.s2),
      ) {
        provider.baseUrls.forEach { url ->
          KetchChip(
            label = url.substringAfter("://").substringBefore('/'),
            selected = entry.effectiveBaseUrl == url,
            onClick = {
              onChange(entry.copy(baseUrl = if (url == provider.defaultBaseUrl) "" else url))
            },
          )
        }
      }
    }
  }
}

/**
 * The models to choose from, the one discovery calls selected; those the user added can be
 * removed. The field adds a model id, and filters the models the provider listed once asked.
 */
@Composable
private fun ModelsSection(state: AppState, entry: LlmSettings, onChange: (LlmSettings) -> Unit) {
  val ai = state.aiSettings
  val colors = KetchTheme.colors
  val spacing = KetchTheme.spacing
  var typed by remember { mutableStateOf("") }
  // Only lists asked for in this editor show, not one left from an earlier draft with its id.
  var asked by remember { mutableStateOf(false) }
  val choices = entry.modelChoices
  val add = {
    if (typed.isNotBlank()) {
      onChange(entry.withCandidate(typed))
      typed = ""
    }
  }
  EditorSection(
    title = stringResource(Res.string.settings_ai_models),
    hint = stringResource(Res.string.settings_ai_models_hint),
  ) {
    if (choices.isNotEmpty()) {
      FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(spacing.s2),
        verticalArrangement = Arrangement.spacedBy(spacing.s2),
      ) {
        choices.forEach { model ->
          KetchChip(
            label = model,
            selected = model == entry.effectiveModel,
            onClick = { onChange(entry.withModel(model)) },
            onRemove = if (model in entry.models) {
              { onChange(entry.withoutModel(model)) }
            } else {
              null
            },
          )
        }
      }
    }
    Row(
      verticalAlignment = Alignment.Top,
      horizontalArrangement = Arrangement.spacedBy(spacing.s2),
    ) {
      SettingsTextField(
        value = typed,
        onValueChange = { typed = it },
        modifier = Modifier.weight(1f),
        placeholder = stringResource(Res.string.settings_ai_models_add_placeholder),
        onDone = add,
        mono = true,
      )
      KetchButton(
        text = stringResource(Res.string.action_add),
        onClick = add,
        variant = KetchButtonVariant.Secondary,
        enabled = typed.isNotBlank(),
      )
    }
    val list = if (asked) ai.modelLists[entry.id] else null
    when (list) {
      null, AiModelList.Idle -> KetchButton(
        text = stringResource(Res.string.settings_ai_models_fetch),
        onClick = {
          asked = true
          state.launchCommand { ai.loadModels(entry) }
        },
        variant = KetchButtonVariant.Ghost,
        size = KetchButtonSize.Small,
        leadingIcon = KetchIcon.Search,
      )
      AiModelList.Loading -> Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(spacing.s2),
      ) {
        KetchSpinner()
        Caption(stringResource(Res.string.settings_ai_models_loading))
      }
      is AiModelList.Failed -> Caption(list.message.resolve(), colors.status.failed.color)
      is AiModelList.Loaded -> {
        val query = typed.trim()
        val offered = list.models.filter { it !in choices && it.contains(query, ignoreCase = true) }
        if (offered.isEmpty()) {
          Caption(stringResource(Res.string.settings_ai_models_no_match))
        } else {
          Caption(
            pluralStringResource(
              Res.plurals.settings_ai_models_available,
              offered.size,
              offered.size,
            ),
          )
          FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(spacing.s2),
            verticalArrangement = Arrangement.spacedBy(spacing.s2),
          ) {
            offered.take(MAX_OFFERED_MODELS).forEach { model ->
              KetchChip(
                label = model,
                selected = false,
                onClick = { onChange(entry.withCandidate(model)) },
                leadingIcon = KetchIcon.Plus,
              )
            }
          }
        }
      }
    }
  }
}

/** Test, which calls [entry] as it is, before it is saved. */
@Composable
private fun TestSection(state: AppState, entry: LlmSettings) {
  val ai = state.aiSettings
  val test = if (ai.testedId == entry.id) ai.connectionTest else AiConnectionTest.Idle
  // How long the last test took, measured from the click.
  var started by remember { mutableStateOf<TimeMark?>(null) }
  var took by remember { mutableStateOf<Duration?>(null) }
  LaunchedEffect(test) {
    if (test is AiConnectionTest.Success) {
      took = started?.elapsedNow()
      started = null
    }
  }
  val testing = test is AiConnectionTest.Running
  val complete = ai.withPlatformCredentials(AiSettings(llm = entry)).llm.isComplete
  val (message, color) = testStatus(test, entry.effectiveModel, took)
  Row(
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(KetchTheme.spacing.s3),
  ) {
    Column(Modifier.weight(1f)) {
      Text(
        text = stringResource(Res.string.settings_ai_test),
        style = KetchTheme.typography.body,
        color = KetchTheme.colors.textPrimary,
      )
      Caption(message.resolve(), color)
    }
    KetchButton(
      text = if (testing) {
        stringResource(Res.string.settings_ai_test_running)
      } else {
        stringResource(Res.string.settings_ai_test_button)
      },
      onClick = {
        started = TimeSource.Monotonic.markNow()
        took = null
        state.launchCommand { ai.testConnection(entry) }
      },
      variant = KetchButtonVariant.Secondary,
      size = KetchButtonSize.Small,
      enabled = complete && !testing,
    )
  }
}

/** A part of the editor: its [title] and [hint] over its [content]. */
@Composable
private fun EditorSection(
  title: String,
  hint: String?,
  content: @Composable ColumnScope.() -> Unit,
) {
  val spacing = KetchTheme.spacing
  Column(
    modifier = Modifier.fillMaxWidth(),
    verticalArrangement = Arrangement.spacedBy(spacing.s2),
  ) {
    Column(verticalArrangement = Arrangement.spacedBy(spacing.s0_5)) {
      Text(
        text = title,
        style = KetchTheme.typography.bodyStrong,
        color = KetchTheme.colors.textPrimary,
      )
      if (!hint.isNullOrBlank()) Caption(hint)
    }
    content()
  }
}

@Composable
private fun Caption(text: String, color: Color = KetchTheme.colors.textSecondary) {
  Text(text = text, style = KetchTheme.typography.caption, color = color)
}

/** A key field, masked until Show. */
@Composable
private fun SecretField(value: String, onValueChange: (String) -> Unit, placeholder: String) {
  var revealed by remember { mutableStateOf(false) }
  SettingsTextField(
    value = value,
    onValueChange = onValueChange,
    placeholder = placeholder,
    keyboardType = KeyboardType.Password,
    visualTransformation = if (revealed) {
      VisualTransformation.None
    } else {
      PasswordVisualTransformation()
    },
    mono = true,
    trailing = if (value.isEmpty()) {
      null
    } else {
      {
        KetchButton(
          text = if (revealed) {
            stringResource(Res.string.settings_action_hide)
          } else {
            stringResource(Res.string.action_show)
          },
          onClick = { revealed = !revealed },
          variant = KetchButtonVariant.Ghost,
          size = KetchButtonSize.Small,
        )
      }
    },
  )
}

/** How a group of providers is headed in the editor. */
private val LlmProviderGroup.label: UiText
  get() = when (this) {
    LlmProviderGroup.Hosted -> Res.string.settings_ai_provider_group_hosted
    LlmProviderGroup.China -> Res.string.settings_ai_provider_group_china
    LlmProviderGroup.Local -> Res.string.settings_ai_provider_group_local
    LlmProviderGroup.Custom -> Res.string.settings_ai_provider_group_custom
  }.text()

/** How a provider reads on its button. */
private val LlmProvider.buttonLabel: UiText
  get() = when (this) {
    LlmProvider.Google -> verbatim("Gemini")
    LlmProvider.Ollama -> Res.string.settings_ai_provider_ollama.text()
    LlmProvider.OpenAiCompatible -> Res.string.settings_ai_provider_compatible.text()
    else -> verbatim(label)
  }

private fun tokenPlaceholder(provider: LlmProvider): String =
  when (provider) {
    LlmProvider.OpenAi, LlmProvider.OpenAiCompatible, LlmProvider.DeepSeek -> "sk-…"
    LlmProvider.Anthropic -> "sk-ant-…"
    LlmProvider.Google -> "AIza…"
    else -> ""
  }

/** Under Provider: where to get its key, or what else it needs. */
private fun providerHint(provider: LlmProvider): UiText? = when {
  provider == LlmProvider.Ollama ->
    Res.string.settings_ai_hint_ollama.text(LlmProvider.Ollama.defaultModel)
  provider == LlmProvider.OpenAiCompatible -> Res.string.settings_ai_hint_compatible.text()
  provider.keyUrl.isNotBlank() ->
    Res.string.settings_ai_hint_key.text(verbatim(provider.keyUrl.substringAfter("://")))
  else -> null
}

// Listed models offered at once; typing narrows them.
private const val MAX_OFFERED_MODELS = 12
