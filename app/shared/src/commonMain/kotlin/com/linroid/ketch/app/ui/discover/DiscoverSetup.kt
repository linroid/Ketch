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
import com.linroid.ketch.app.components.KetchHueTile
import com.linroid.ketch.app.components.KetchHueTileDefaults
import com.linroid.ketch.app.icons.KetchIcon
import com.linroid.ketch.app.state.AppState
import com.linroid.ketch.app.state.SettingsTarget
import com.linroid.ketch.app.theme.FileTypeHue
import com.linroid.ketch.app.theme.KetchTheme
import com.linroid.ketch.app.theme.eyebrowText
import com.linroid.ketch.config.LlmProvider

/**
 * Discover before it is set up: what it does, searches it could run, and a button per model
 * provider that switches discovery on with that provider and opens its settings for the key.
 * Where discovery cannot run, it says so instead.
 */
@Composable
internal fun DiscoverSetup(state: AppState, phone: Boolean, modifier: Modifier = Modifier) {
  val supported = state.aiSettings.supported
  val pending = state.aiDiscover.pending
  val spacing = KetchTheme.spacing
  val colors = KetchTheme.colors
  DiscoverHero(
    title = "Describe it. Ketch finds the download.",
    body = if (supported) {
      "Discover asks an AI model to search the web and read the pages it finds, then lists " +
        "the files it trusts. Your searches go to the provider you choose."
    } else {
      "Discover runs in the Ketch app on a computer or an Android phone."
    },
    modifier = modifier,
  ) {
    if (pending != null) {
      Text(
        text = "Your search for “${pending.query}” runs as soon as Discover is set up.",
        style = KetchTheme.typography.labelS,
        color = colors.accentText,
        textAlign = TextAlign.Center,
        modifier = Modifier
          .background(colors.accentSoft, KetchTheme.shapes.md)
          .padding(horizontal = spacing.s3, vertical = spacing.s2),
      )
    } else {
      DiscoverExamples(onClick = null)
    }
    if (!supported) return@DiscoverHero
    Spacer(Modifier.height(spacing.s8))
    Text(
      text = eyebrowText("Choose a model"),
      style = KetchTheme.typography.eyebrow,
      color = colors.textTertiary,
    )
    Spacer(Modifier.height(spacing.s3))
    FlowRow(
      horizontalArrangement = Arrangement.spacedBy(spacing.s2, Alignment.CenterHorizontally),
      verticalArrangement = Arrangement.spacedBy(spacing.s2),
      modifier = Modifier.fillMaxWidth(),
    ) {
      for (provider in SetupProviders) {
        KetchButton(
          text = provider.setupLabel,
          onClick = {
            state.aiSettings.chooseProvider(provider)
            state.openSettings(SettingsTarget(SettingsTarget.Page.Discover))
          },
          variant = KetchButtonVariant.Secondary,
          modifier = if (phone) Modifier.fillMaxWidth() else Modifier,
        )
      }
    }
    Spacer(Modifier.height(spacing.s2))
    KetchButton(
      text = "Set up in Settings",
      onClick = { state.openSettings(SettingsTarget(SettingsTarget.Page.Discover)) },
      variant = KetchButtonVariant.Ghost,
      leadingIcon = KetchIcon.Settings,
    )
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
 * Searches to try. With [onClick] each one runs; without it they only show what a search can
 * look like.
 */
@Composable
internal fun DiscoverExamples(onClick: ((String) -> Unit)?) {
  val spacing = KetchTheme.spacing
  FlowRow(
    horizontalArrangement = Arrangement.spacedBy(spacing.s2, Alignment.CenterHorizontally),
    verticalArrangement = Arrangement.spacedBy(spacing.s2),
    modifier = Modifier.fillMaxWidth(),
  ) {
    for (example in Examples) {
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
      text = "“$text”",
      style = KetchTheme.typography.labelS,
      color = colors.textSecondary,
      maxLines = 1,
    )
  }
}

/** The provider's name as Discover shows it: "Gemini" rather than "Google Gemini". */
internal val LlmProvider.shortLabel: String
  get() = when (this) {
    LlmProvider.Google -> "Gemini"
    else -> label
  }

/** What the provider's setup button says. */
private val LlmProvider.setupLabel: String
  get() = when (this) {
    LlmProvider.Ollama -> "$shortLabel · runs locally, no key"
    else -> shortLabel
  }

/** The providers the setup page offers; an OpenAI-compatible endpoint is set up in Settings. */
private val SetupProviders = listOf(
  LlmProvider.OpenAi,
  LlmProvider.Anthropic,
  LlmProvider.Google,
  LlmProvider.Ollama,
)

private val Examples = listOf(
  "Blender for Apple silicon",
  "Ubuntu 24.04 server ISO",
  "Public-domain 4K nature footage",
)

private val HeroWidth: Dp = 640.dp
private val BodyWidth: Dp = 520.dp

/** Share of the page's height the hero centers its content in. */
private const val HERO_HEIGHT_SHARE = 0.85f
