package com.linroid.ketch.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.linroid.ketch.app.components.KetchButton
import com.linroid.ketch.app.components.KetchButtonSize
import com.linroid.ketch.app.components.KetchButtonVariant
import com.linroid.ketch.app.components.KetchCard
import com.linroid.ketch.app.icons.KetchIcon
import com.linroid.ketch.app.state.AiDiscoverDraft
import com.linroid.ketch.app.state.AiDiscoverState
import com.linroid.ketch.app.state.AiCandidate
import com.linroid.ketch.app.theme.KetchTheme
import com.linroid.ketch.app.util.formatBytes

@Composable
fun AiDiscoverForm(
  state: AiDiscoverState,
  draft: AiDiscoverDraft,
  available: Boolean,
  onDiscover: (String, String) -> Unit,
  onCancelSearch: () -> Unit,
) {
  val loading = state is AiDiscoverState.Loading
  Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
    Text("What would you like to find?", style = KetchTheme.typography.bodyLarge,
      color = KetchTheme.colors.onBackground)
    OutlinedTextField(
      value = draft.query,
      onValueChange = { draft.query = it },
      placeholder = { Text("Describe a file, app, or resource…") },
      minLines = 3, maxLines = 6,
      enabled = !loading,
      shape = RoundedCornerShape(12.dp),
      modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Search description" },
    )
    if (state is AiDiscoverState.Idle) {
      Text("Try an example", style = KetchTheme.typography.labelSmall,
        color = KetchTheme.colors.onSurfaceDim)
      FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)) {
        listOf("Ubuntu desktop ISO", "Blender for macOS", "Sample audio files").forEach { query ->
          KetchButton(query, onClick = { draft.query = query },
            variant = KetchButtonVariant.Secondary, size = KetchButtonSize.Small)
        }
      }
    }
    KetchButton(
      text = if (draft.showSites) "Hide website filter" else if (draft.sites.isNotBlank()) "Website filter applied" else "Limit to websites",
      leadingIcon = KetchIcon.Filter,
      variant = KetchButtonVariant.Ghost,
      size = KetchButtonSize.Small,
      onClick = { draft.showSites = !draft.showSites },
    )
    AnimatedVisibility(draft.showSites) {
      OutlinedTextField(
        value = draft.sites,
        onValueChange = { draft.sites = it },
        label = { Text("Websites (optional)") },
        placeholder = { Text("ubuntu.com, blender.org") },
        supportingText = { Text("Separate website domains with commas.") },
        singleLine = true, enabled = !loading,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth(),
      )
    }
    FlowRow(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
      verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      if (loading) KetchButton("Cancel search", onClick = onCancelSearch,
        variant = KetchButtonVariant.Ghost)
      KetchButton(
        text = if (loading) "Finding links…" else "Find links",
        leadingIcon = KetchIcon.Ai,
        enabled = available && draft.query.isNotBlank() && !loading,
        onClick = {
          draft.prepareSearch()
          onDiscover(draft.submittedQuery, draft.sites.trim())
        },
      )
    }
  }
}

@Composable
fun AiDiscoverResults(
  state: AiDiscoverState,
  draft: AiDiscoverDraft,
  onDownloadSelected: (List<AiCandidate>) -> Unit,
) {
  val colors = KetchTheme.colors
  when (state) {
    AiDiscoverState.Idle -> Unit
    AiDiscoverState.Loading -> Row(
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(12.dp),
      modifier = Modifier.padding(vertical = 12.dp),
    ) {
      CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
      Text("Looking for downloadable links…", style = KetchTheme.typography.bodyMedium,
        color = colors.onSurfaceVariant)
    }
    is AiDiscoverState.Error -> KetchCard {
      Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Couldn't find links", style = KetchTheme.typography.bodyLarge, color = colors.error)
        Text(state.message, style = KetchTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
        Text("Your search is saved. Try again or adjust the description.",
          style = KetchTheme.typography.bodySmall, color = colors.onSurfaceDim)
      }
    }
    is AiDiscoverState.Results -> {
      val candidates = state.candidates.distinctBy { it.url }
      val selected = draft.selectedCandidates(state)
      Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(if (candidates.isEmpty()) "No links found" else "${candidates.size} links found",
          style = KetchTheme.typography.displaySmall, color = colors.onBackground)
        Text(if (candidates.isEmpty()) "Try a more specific file name or remove the website filter."
          else "Results for “${draft.submittedQuery}”",
          style = KetchTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
        if (candidates.isNotEmpty()) {
          FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
          ) {
            KetchButton(
              if (selected.size == candidates.size) "Clear selection" else "Select all",
              onClick = { draft.selected = if (selected.size == candidates.size) emptySet()
                else candidates.map { it.url }.toSet() },
              variant = KetchButtonVariant.Secondary,
            )
            KetchButton(
              "Download selected (${selected.size})",
              onClick = { onDownloadSelected(selected) }, enabled = selected.isNotEmpty(),
              leadingIcon = KetchIcon.Active,
            )
          }
          candidates.forEach { candidate ->
            CandidateItem(candidate, candidate.url in draft.selected) {
              draft.selected = if (candidate.url in draft.selected) draft.selected - candidate.url
                else draft.selected + candidate.url
            }
          }
        }
      }
    }
  }
}

@Composable
private fun CandidateItem(candidate: AiCandidate, selected: Boolean, onToggle: () -> Unit) {
  val colors = KetchTheme.colors
  KetchCard(padding = 0.dp) {
    Row(
      verticalAlignment = Alignment.Top,
      modifier = Modifier.fillMaxWidth()
        .background(if (selected) colors.primaryContainer else colors.surface)
        .toggleable(value = selected, role = Role.Checkbox, onValueChange = { onToggle() })
        .padding(12.dp),
    ) {
      Checkbox(checked = selected, onCheckedChange = null, modifier = Modifier.padding(end = 12.dp, top = 2.dp))
      Column(verticalArrangement = Arrangement.spacedBy(5.dp), modifier = Modifier.weight(1f)) {
        Text(candidate.title.ifBlank { candidate.fileName ?: candidate.url },
          style = KetchTheme.typography.bodyLarge, color = colors.onBackground)
        Text(candidate.url, style = KetchTheme.typography.bodySmall,
          color = colors.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (candidate.description.isNotBlank()) Text(candidate.description,
          style = KetchTheme.typography.bodySmall, color = colors.onSurfaceVariant,
          maxLines = 3, overflow = TextOverflow.Ellipsis)
        candidate.fileSize?.takeIf { it > 0 }?.let {
          Text(formatBytes(it), style = KetchTheme.typography.labelSmall, color = colors.onSurfaceDim)
        }
      }
    }
  }
}
