package com.linroid.ketch.app.ui.discover

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.input.ImeAction
import com.linroid.ketch.app.components.KetchButton
import com.linroid.ketch.app.components.KetchButtonVariant
import com.linroid.ketch.app.components.KetchChip
import com.linroid.ketch.app.components.KetchTextField
import com.linroid.ketch.app.icons.KetchIcon
import com.linroid.ketch.app.state.AiDiscoverDraft
import com.linroid.ketch.app.theme.KetchTheme
import ketch.app.shared.generated.resources.Res
import ketch.app.shared.generated.resources.discover_find
import ketch.app.shared.generated.resources.discover_query_placeholder
import ketch.app.shared.generated.resources.discover_sites_count
import ketch.app.shared.generated.resources.discover_sites_hint
import ketch.app.shared.generated.resources.discover_sites_limit
import ketch.app.shared.generated.resources.discover_stop
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/**
 * The search: a 48 dp field for what to find, the "Limit to websites" chip and Find, which
 * turns into Stop while a search runs. [wide] keeps the chip and the button inside the field;
 * otherwise they sit on a row below it. The website field opens under it from the chip.
 */
@Composable
internal fun DiscoverQueryBar(
  draft: AiDiscoverDraft,
  searching: Boolean,
  wide: Boolean,
  focusRequester: FocusRequester,
  onSearch: () -> Unit,
  onStop: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val spacing = KetchTheme.spacing
  val focusManager = LocalFocusManager.current
  val canSearch = draft.query.isNotBlank()
  val submit = { if (canSearch) onSearch() }
  // The keyboard's Search key also puts the keyboard away, so the results have the screen.
  val keyboardSearch = KeyboardActions(
    onSearch = {
      submit()
      focusManager.clearFocus()
    },
  )
  Column(modifier, verticalArrangement = Arrangement.spacedBy(spacing.s2)) {
    KetchTextField(
      value = draft.query,
      onValueChange = { draft.query = it },
      placeholder = stringResource(Res.string.discover_query_placeholder),
      leadingIcon = KetchIcon.Discover,
      textStyle = KetchTheme.typography.body,
      keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
      keyboardActions = keyboardSearch,
      trailing = if (wide) {
        {
          Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(spacing.s2),
            // Pads the field to 48 dp around the 32 dp button.
            modifier = Modifier.padding(vertical = spacing.s2).padding(end = spacing.s1),
          ) {
            SitesChip(draft)
            SearchButton(searching, canSearch, submit, onStop)
          }
        }
      } else {
        null
      },
      modifier = Modifier
        .fillMaxWidth()
        .focusRequester(focusRequester)
        .submitOnEnter(submit),
    )
    if (!wide) {
      Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        SitesChip(draft)
        Spacer(Modifier.weight(1f))
        SearchButton(searching, canSearch, submit, onStop)
      }
    }
    AnimatedVisibility(draft.showSites) {
      SitesField(draft, submit, keyboardSearch)
    }
  }
}

@Composable
private fun SearchButton(
  searching: Boolean,
  canSearch: Boolean,
  onSearch: () -> Unit,
  onStop: () -> Unit,
) {
  if (searching) {
    KetchButton(
      text = stringResource(Res.string.discover_stop),
      onClick = onStop,
      variant = KetchButtonVariant.Secondary,
      leadingIcon = KetchIcon.Stop,
    )
  } else {
    KetchButton(
      text = stringResource(Res.string.discover_find),
      onClick = onSearch,
      enabled = canSearch,
      shortcut = "↩",
    )
  }
}

/** Opens the website field; it reads as selected while websites limit the search. */
@Composable
private fun SitesChip(draft: AiDiscoverDraft) {
  val sites = draft.siteList()
  KetchChip(
    label = when (sites.size) {
      0 -> stringResource(Res.string.discover_sites_limit)
      1 -> sites.single()
      else -> pluralStringResource(Res.plurals.discover_sites_count, sites.size, sites.size)
    },
    selected = sites.isNotEmpty(),
    onClick = { draft.showSites = !draft.showSites },
    leadingIcon = KetchIcon.Filter,
    trailingIcon = if (draft.showSites) KetchIcon.ChevronUp else KetchIcon.ChevronDown,
  )
}

@Composable
private fun SitesField(
  draft: AiDiscoverDraft,
  onSearch: () -> Unit,
  keyboardActions: KeyboardActions,
) {
  Column(verticalArrangement = Arrangement.spacedBy(KetchTheme.spacing.s1)) {
    KetchTextField(
      value = draft.sites,
      onValueChange = { draft.sites = it },
      placeholder = ExampleSites.joinToString(", "),
      leadingIcon = KetchIcon.Link,
      keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
      keyboardActions = keyboardActions,
      modifier = Modifier.fillMaxWidth().submitOnEnter(onSearch),
    )
    Text(
      text = stringResource(Res.string.discover_sites_hint),
      style = KetchTheme.typography.caption,
      color = KetchTheme.colors.textTertiary,
    )
  }
}

// Websites the website field shows as an example, in the comma-separated form it reads.
private val ExampleSites = listOf("ubuntu.com", "blender.org")

/** Runs [onSubmit] on Enter from a hardware keyboard, instead of the field taking it. */
private fun Modifier.submitOnEnter(onSubmit: () -> Unit): Modifier = onPreviewKeyEvent { event ->
  val enter = event.key == Key.Enter || event.key == Key.NumPadEnter
  if (event.type == KeyEventType.KeyDown && enter) {
    onSubmit()
    true
  } else {
    false
  }
}
