package com.linroid.ketch.app.ui.discover

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.linroid.ketch.app.components.KetchButton
import com.linroid.ketch.app.components.KetchButtonVariant
import com.linroid.ketch.app.components.KetchChip
import com.linroid.ketch.app.components.KetchEyebrow
import com.linroid.ketch.app.components.KetchHueTile
import com.linroid.ketch.app.components.KetchHueTileDefaults
import com.linroid.ketch.app.i18n.UiText
import com.linroid.ketch.app.i18n.resolve
import com.linroid.ketch.app.i18n.text
import com.linroid.ketch.app.i18n.verbatim
import com.linroid.ketch.app.icons.KetchIcon
import com.linroid.ketch.app.state.AppState
import com.linroid.ketch.app.state.SettingsTarget
import com.linroid.ketch.app.theme.FileTypeHue
import com.linroid.ketch.app.theme.KetchTheme
import com.linroid.ketch.config.LlmProvider
import com.linroid.ketch.config.LlmSettings
import ketch.app.shared.generated.resources.Res
import ketch.app.shared.generated.resources.discover_example_quoted
import ketch.app.shared.generated.resources.discover_hero_title
import ketch.app.shared.generated.resources.discover_provider_openai_compatible
import ketch.app.shared.generated.resources.discover_setup_body
import ketch.app.shared.generated.resources.discover_setup_choose_model
import ketch.app.shared.generated.resources.discover_setup_finish
import ketch.app.shared.generated.resources.discover_setup_in_settings
import ketch.app.shared.generated.resources.discover_setup_local_provider
import ketch.app.shared.generated.resources.discover_setup_pending
import ketch.app.shared.generated.resources.discover_setup_unsupported
import ketch.app.shared.generated.resources.discover_turn_off
import org.jetbrains.compose.resources.stringResource

/**
 * Discover before it is set up: what it does, searches it could run, and a button per model
 * provider that switches discovery on with that provider and opens its settings for the key.
 * Once one is picked, its button stands out and the settings link says to finish it. Beside it,
 * Turn off Discover hides the destination for users who do not want it. Where discovery cannot
 * run, it says so instead.
 */
@Composable
internal fun DiscoverSetup(state: AppState, phone: Boolean, modifier: Modifier = Modifier) {
  val supported = state.aiSettings.supported
  val pending = state.aiDiscover.pending
  // Picked but not usable yet: the provider still needs its key. Discovery is on by default, so
  // only a provider other than the untouched default counts as picked.
  val chosen = state.aiSettings.settings.llm.takeIf { it != LlmSettings() }?.provider
  val spacing = KetchTheme.spacing
  val colors = KetchTheme.colors
  DiscoverHero(
    title = stringResource(Res.string.discover_hero_title),
    body = if (supported) {
      stringResource(Res.string.discover_setup_body)
    } else {
      stringResource(Res.string.discover_setup_unsupported)
    },
    modifier = modifier,
  ) {
    if (pending != null) {
      Text(
        text = stringResource(Res.string.discover_setup_pending, pending.query),
        style = KetchTheme.typography.labelS,
        color = colors.accentText,
        textAlign = TextAlign.Center,
        modifier = Modifier
          .background(colors.accentSoft, KetchTheme.shapes.md)
          .padding(horizontal = spacing.s3, vertical = spacing.s2),
      )
    } else {
      DiscoverExamples(state.aiDiscover.examples, onClick = null)
    }
    if (!supported) return@DiscoverHero
    Spacer(Modifier.height(spacing.s8))
    KetchEyebrow(stringResource(Res.string.discover_setup_choose_model))
    Spacer(Modifier.height(spacing.s3))
    FlowRow(
      horizontalArrangement = Arrangement.spacedBy(spacing.s2, Alignment.CenterHorizontally),
      verticalArrangement = Arrangement.spacedBy(spacing.s2),
      modifier = Modifier.fillMaxWidth(),
    ) {
      for (provider in SetupProviders) {
        KetchButton(
          text = provider.setupLabel.resolve(),
          onClick = {
            state.aiSettings.chooseProvider(provider)
            state.openSettings(SettingsTarget(SettingsTarget.Page.Discover))
          },
          variant = if (provider == chosen) {
            KetchButtonVariant.Primary
          } else {
            KetchButtonVariant.Secondary
          },
          modifier = if (phone) Modifier.fillMaxWidth() else Modifier,
        )
      }
    }
    Spacer(Modifier.height(spacing.s2))
    FlowRow(
      horizontalArrangement = Arrangement.spacedBy(spacing.s1, Alignment.CenterHorizontally),
      modifier = Modifier.fillMaxWidth(),
    ) {
      KetchButton(
        text = if (chosen != null) {
          stringResource(Res.string.discover_setup_finish, chosen.shortLabel.resolve())
        } else {
          stringResource(Res.string.discover_setup_in_settings)
        },
        onClick = { state.openSettings(SettingsTarget(SettingsTarget.Page.Discover)) },
        variant = KetchButtonVariant.Ghost,
        leadingIcon = KetchIcon.Settings,
      )
      KetchButton(
        text = stringResource(Res.string.discover_turn_off),
        onClick = { state.turnOffDiscover() },
        variant = KetchButtonVariant.Ghost,
        leadingIcon = KetchIcon.Close,
      )
    }
  }
}

/**
 * The centered top of an empty Discover page: the Discover tile, [title] and [body], then
 * [content]. It scrolls when the page is too short, and sits a little above the middle when
 * it is not.
 */
@Composable
internal fun DiscoverHero(
  title: String,
  body: String,
  modifier: Modifier = Modifier,
  content: @Composable ColumnScope.() -> Unit,
) {
  val spacing = KetchTheme.spacing
  val colors = KetchTheme.colors
  BoxWithConstraints(modifier.fillMaxWidth()) {
    val height = maxHeight
    Box(
      contentAlignment = Alignment.TopCenter,
      modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
    ) {
      Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier
          .heightIn(min = height * HERO_HEIGHT_SHARE)
          .widthIn(max = HeroWidth)
          .fillMaxWidth()
          .padding(horizontal = spacing.s4, vertical = spacing.s8),
      ) {
        KetchHueTile(
          icon = KetchIcon.Discover,
          hue = FileTypeHue.Violet,
          size = KetchHueTileDefaults.Large,
        )
        Spacer(Modifier.height(spacing.s5))
        Text(
          text = title,
          style = KetchTheme.typography.titleL,
          color = colors.textPrimary,
          textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(spacing.s2))
        Text(
          text = body,
          style = KetchTheme.typography.body,
          color = colors.textSecondary,
          textAlign = TextAlign.Center,
          modifier = Modifier.widthIn(max = BodyWidth),
        )
        Spacer(Modifier.height(spacing.s6))
        content()
      }
    }
  }
}

/**
 * Searches to try, the [examples] a new session offers. With [onClick] each one runs; without it
 * they only show what a search can look like.
 */
@Composable
internal fun DiscoverExamples(examples: List<UiText>, onClick: ((String) -> Unit)?) {
  val spacing = KetchTheme.spacing
  FlowRow(
    horizontalArrangement = Arrangement.spacedBy(spacing.s2, Alignment.CenterHorizontally),
    verticalArrangement = Arrangement.spacedBy(spacing.s2),
    modifier = Modifier.fillMaxWidth(),
  ) {
    for (text in examples) {
      val example = text.resolve()
      if (onClick != null) {
        KetchChip(
          label = example,
          selected = false,
          onClick = { onClick(example) },
          leadingIcon = KetchIcon.Search,
        )
      } else {
        ExampleQuote(example)
      }
    }
  }
}

/** An example search that cannot run yet, quoted on a sunken pill. */
@Composable
private fun ExampleQuote(text: String) {
  val colors = KetchTheme.colors
  Box(
    contentAlignment = Alignment.Center,
    modifier = Modifier
      .heightIn(min = KetchTheme.density.chip)
      .background(colors.surfaceSunken, KetchTheme.shapes.full)
      .padding(horizontal = KetchTheme.spacing.s3),
  ) {
    Text(
      text = stringResource(Res.string.discover_example_quoted, text),
      style = KetchTheme.typography.labelS,
      color = colors.textSecondary,
      maxLines = 1,
    )
  }
}

/** The provider's name as Discover shows it: "Gemini" rather than "Google Gemini". */
internal val LlmProvider.shortLabel: UiText
  get() = when (this) {
    LlmProvider.Google -> verbatim("Gemini")
    LlmProvider.OpenAiCompatible -> Res.string.discover_provider_openai_compatible.text()
    else -> verbatim(label)
  }

/** What the provider's setup button says. */
private val LlmProvider.setupLabel: UiText
  get() = when (this) {
    LlmProvider.Ollama -> Res.string.discover_setup_local_provider.text(shortLabel)
    else -> shortLabel
  }

/** The providers the setup page offers; an OpenAI-compatible endpoint is set up in Settings. */
private val SetupProviders = listOf(
  LlmProvider.OpenAi,
  LlmProvider.Anthropic,
  LlmProvider.Google,
  LlmProvider.Ollama,
)

private val HeroWidth: Dp = 640.dp
private val BodyWidth: Dp = 520.dp

/** Share of the page's height the hero centers its content in. */
private const val HERO_HEIGHT_SHARE = 0.85f
