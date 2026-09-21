package com.linroid.ketch.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.linroid.ketch.app.components.KetchCard
import com.linroid.ketch.app.state.AiCandidate
import com.linroid.ketch.app.theme.KetchTheme
import com.linroid.ketch.app.state.AiDiscoverDraft
import com.linroid.ketch.app.state.AiDiscoverState

@Composable
fun AiDiscoveryPage(
  state: AiDiscoverState,
  draft: AiDiscoverDraft,
  onCancelSearch: () -> Unit,
  onDiscover: (String, String) -> Unit,
  onDownloadSelected: (List<AiCandidate>) -> Unit,
) {
  BoxWithConstraints(Modifier.fillMaxSize()) {
    val compact = maxWidth < 600.dp
    val inset = if (compact) 16.dp else 32.dp
    Column(
      modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(inset),
      horizontalAlignment = Alignment.CenterHorizontally,
    ) {
      Column(
        modifier = Modifier.widthIn(max = 720.dp).fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(20.dp),
      ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
          Text("AI discovery", style = KetchTheme.typography.displaySmall,
            color = KetchTheme.colors.onBackground)
          Text("Find the files you need, in your own words.",
            style = KetchTheme.typography.bodyMedium, color = KetchTheme.colors.onSurfaceVariant)
        }
        KetchCard(modifier = Modifier.fillMaxWidth(), padding = if (compact) 16.dp else 24.dp) {
          AiDiscoverForm(state, draft, onDiscover, onCancelSearch)
        }
        AiDiscoverResults(state, draft, onDownloadSelected)
      }
    }
  }
}
