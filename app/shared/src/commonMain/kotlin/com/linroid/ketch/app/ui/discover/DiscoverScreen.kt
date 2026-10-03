package com.linroid.ketch.app.ui.discover

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.linroid.ketch.app.components.KetchButton
import com.linroid.ketch.app.components.KetchButtonSize
import com.linroid.ketch.app.components.KetchButtonVariant
import com.linroid.ketch.app.i18n.UiText
import com.linroid.ketch.app.i18n.joinText
import com.linroid.ketch.app.i18n.resolve
import com.linroid.ketch.app.i18n.verbatim
import com.linroid.ketch.app.icons.KetchIcon
import com.linroid.ketch.app.state.AiDiscoverState
import com.linroid.ketch.app.state.AiSettingsController
import com.linroid.ketch.app.state.AppState
import com.linroid.ketch.app.state.SettingsTarget
import com.linroid.ketch.app.theme.KetchDensity
import com.linroid.ketch.app.theme.KetchTheme
import com.linroid.ketch.app.ui.shell.LocalKetchLayout
import com.linroid.ketch.app.ui.shell.ShellNavigation
import ketch.app.shared.generated.resources.Res
import ketch.app.shared.generated.resources.discover_hero_title
import ketch.app.shared.generated.resources.discover_intro_body
import ketch.app.shared.generated.resources.discover_settings
import ketch.app.shared.generated.resources.discover_title
import org.jetbrains.compose.resources.stringResource

/**
 * The Discover destination: an AI search for downloads, in the user's own words.
 *
 * Until discovery is set up it shows how to set it up, with a button per model provider. Once
 * it is, it shows the search, the steps the agent takes while it works, and the downloads it
 * found, which can be added at once or reviewed in the add sheet first. A search asked for
 * before setup, from the add sheet or the phone's search, runs as soon as setup is done.
 */
@Composable
fun DiscoverScreen(state: AppState) {
  val ai = state.aiSettings
  val available = ai.available
  val phone = LocalKetchLayout.current.navigation == ShellNavigation.Phone
  LaunchedEffect(available) {
    if (available) state.aiDiscover.runPending()
  }
  Column(Modifier.fillMaxSize()) {
    // Phones name the page in their top bar already.
    if (!phone) DiscoverHeader(state, showModel = available)
    if (available) {
      DiscoverSearchPage(state, phone, Modifier.weight(1f))
    } else {
      DiscoverSetup(state, phone, Modifier.weight(1f))
    }
  }
}

/** "Discover", and once it is set up, the model it searches with, which opens its settings. */
@Composable
private fun DiscoverHeader(state: AppState, showModel: Boolean) {
  val spacing = KetchTheme.spacing
  Row(
    verticalAlignment = Alignment.CenterVertically,
    modifier = Modifier
      .fillMaxWidth()
      .height(spacing.pageHeaderHeight)
      .padding(horizontal = spacing.pageHeaderPadding),
  ) {
    Text(
      text = stringResource(Res.string.discover_title),
      style = KetchTheme.typography.pageTitle,
      color = KetchTheme.colors.textPrimary,
    )
    Spacer(Modifier.weight(1f))
    if (showModel) {
      KetchButton(
        text = modelLabel(state.aiSettings).resolve(),
        onClick = { state.openSettings(SettingsTarget(SettingsTarget.Page.Discover)) },
        variant = KetchButtonVariant.Ghost,
        size = KetchButtonSize.Small,
        leadingIcon = KetchIcon.Ai,
        tooltip = stringResource(Res.string.discover_settings),
      )
    }
  }
}

/** The search, and below it what it found or what to try. */
@Composable
private fun DiscoverSearchPage(state: AppState, phone: Boolean, modifier: Modifier) {
  val controller = state.aiDiscover
  val draft = controller.draft
  val discover = controller.state
  val spacing = KetchTheme.spacing
  val inset = if (phone) KetchTheme.density.pagePadding else spacing.pageHeaderPadding
  val focus = remember { FocusRequester() }
  LaunchedEffect(Unit) {
    // A pointer user lands in the field, ready to type.
    if (!phone && discover == AiDiscoverState.Idle) focus.requestFocus()
  }
  BoxWithConstraints(modifier.fillMaxWidth()) {
    val wide = maxWidth >= WideWidth && KetchTheme.density == KetchDensity.Compact
    Column(Modifier.fillMaxSize()) {
      DiscoverQueryBar(
        draft = draft,
        searching = discover == AiDiscoverState.Loading,
        wide = wide,
        focusRequester = focus,
        onSearch = { controller.search() },
        onStop = { controller.stop() },
        modifier = Modifier
          .fillMaxWidth()
          .padding(horizontal = inset)
          .padding(top = if (phone) inset else spacing.s1, bottom = spacing.s3),
      )
      when (discover) {
        AiDiscoverState.Idle -> DiscoverIntro(
          state = state,
          onExample = { example ->
            draft.query = example
            controller.search()
          },
          modifier = Modifier.weight(1f),
        )
        else -> DiscoverResults(
          state = state,
          discover = discover,
          inset = inset,
          narrow = !wide,
          modifier = Modifier.weight(1f),
        )
      }
      val results = discover as? AiDiscoverState.Results
      if (results != null && results.candidates.isNotEmpty()) {
        DiscoverFooter(state, results, stacked = !wide, inset = inset)
      }
    }
  }
}

/** Before the first search: what Discover does, and searches to try. */
@Composable
private fun DiscoverIntro(
  state: AppState,
  onExample: (String) -> Unit,
  modifier: Modifier,
) {
  val provider = state.aiSettings.settings.llm.provider
  DiscoverHero(
    title = stringResource(Res.string.discover_hero_title),
    body = stringResource(Res.string.discover_intro_body, provider.shortLabel.resolve()),
    modifier = modifier,
  ) {
    DiscoverExamples(onClick = onExample)
  }
}

/** The model discovery runs on, such as "Anthropic · claude-opus-5". */
internal fun modelLabel(ai: AiSettingsController): UiText {
  val llm = ai.withPlatformCredentials(ai.settings).llm
  val model = llm.effectiveModel.takeIf { it.isNotBlank() }?.let(::verbatim)
  return listOfNotNull(llm.provider.shortLabel, model).joinText()
}

/** Below this width, the website chip and Find sit under the search field. */
private val WideWidth: Dp = 600.dp

