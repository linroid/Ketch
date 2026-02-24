package com.linroid.ketch.app.ui.dialog

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.linroid.ketch.endpoints.model.ResourceCandidate

/**
 * State for the AI discovery dialog.
 */
sealed interface AiDiscoverState {
  data object Idle : AiDiscoverState
  data object Loading : AiDiscoverState
  data class Results(
    val candidates: List<ResourceCandidate>,
  ) : AiDiscoverState
  data class Error(val message: String) : AiDiscoverState
}

/**
 * Dialog for discovering downloadable resources via AI.
 *
 * Users enter a natural language query and optionally restrict
 * to specific domains. Results are shown as a selectable list
 * of candidates.
 */
@Composable
fun AiDiscoverDialog(
  state: AiDiscoverState,
  onDiscover: (query: String, sites: String) -> Unit,
  onDownloadSelected: (List<ResourceCandidate>) -> Unit,
  onDismiss: () -> Unit,
) {
  var query by remember { mutableStateOf("") }
  var sites by remember { mutableStateOf("") }
  var selected by remember {
    mutableStateOf(setOf<String>())
  }

  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text("AI Resource Discovery") },
    text = {
      Column(modifier = Modifier.fillMaxWidth()) {
        OutlinedTextField(
          value = query,
          onValueChange = { query = it },
          label = { Text("What are you looking for?") },
          placeholder = {
            Text("e.g. latest Ubuntu 24.04 ISO")
          },
          singleLine = true,
          modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
          value = sites,
          onValueChange = { sites = it },
          label = { Text("Restrict to domains (optional)") },
          placeholder = {
            Text("e.g. ubuntu.com, releases.ubuntu.com")
          },
          singleLine = true,
          modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(12.dp))

        when (state) {
          is AiDiscoverState.Idle -> {
            // Nothing to show yet
          }
          is AiDiscoverState.Loading -> {
            Column(
              horizontalAlignment = Alignment.CenterHorizontally,
              modifier = Modifier.fillMaxWidth()
                .padding(vertical = 16.dp),
            ) {
              CircularProgressIndicator(
                modifier = Modifier.size(32.dp),
              )
              Spacer(Modifier.height(8.dp))
              Text(
                "Discovering resources...",
                style = MaterialTheme.typography.bodyMedium,
              )
            }
          }
          is AiDiscoverState.Results -> {
            if (state.candidates.isEmpty()) {
              Text(
                "No downloadable resources found.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
            } else {
              Text(
                "${state.candidates.size} candidate(s) found:",
                style = MaterialTheme.typography.labelMedium,
              )
              Spacer(Modifier.height(4.dp))
              LazyColumn(
                modifier = Modifier.height(240.dp),
              ) {
                items(state.candidates) { candidate ->
                  CandidateItem(
                    candidate = candidate,
                    isSelected = candidate.url in selected,
                    onToggle = {
                      selected = if (candidate.url in selected) {
                        selected - candidate.url
                      } else {
                        selected + candidate.url
                      }
                    },
                  )
                }
              }
            }
          }
          is AiDiscoverState.Error -> {
            Surface(
              color = MaterialTheme.colorScheme.errorContainer,
              shape = RoundedCornerShape(8.dp),
              modifier = Modifier.fillMaxWidth(),
            ) {
              Text(
                state.message,
                color = MaterialTheme.colorScheme
                  .onErrorContainer,
                modifier = Modifier.padding(12.dp),
                style = MaterialTheme.typography.bodySmall,
              )
            }
          }
        }
      }
    },
    confirmButton = {
      val results = state as? AiDiscoverState.Results
      if (results != null && selected.isNotEmpty()) {
        Button(
          onClick = {
            val selectedCandidates =
              results.candidates.filter {
                it.url in selected
              }
            onDownloadSelected(selectedCandidates)
          },
        ) {
          Text("Download ${selected.size} selected")
        }
      } else {
        Button(
          onClick = { onDiscover(query, sites) },
          enabled = query.isNotBlank() &&
            state !is AiDiscoverState.Loading,
        ) {
          Text("Discover")
        }
      }
    },
    dismissButton = {
      TextButton(onClick = onDismiss) {
        Text("Cancel")
      }
    },
  )
}

@Composable
private fun CandidateItem(
  candidate: ResourceCandidate,
  isSelected: Boolean,
  onToggle: () -> Unit,
) {
  Row(
    verticalAlignment = Alignment.CenterVertically,
    modifier = Modifier.fillMaxWidth()
      .clip(RoundedCornerShape(8.dp))
      .padding(vertical = 2.dp),
  ) {
    Checkbox(
      checked = isSelected,
      onCheckedChange = { onToggle() },
    )
    Column(
      modifier = Modifier.weight(1f),
    ) {
      Text(
        candidate.title,
        style = MaterialTheme.typography.bodyMedium,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
      Text(
        candidate.url,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
      if (candidate.description.isNotEmpty()) {
        Text(
          candidate.description,
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          maxLines = 2,
          overflow = TextOverflow.Ellipsis,
        )
      }
    }
    ConfidenceBadge(candidate.confidence)
  }
}

@Composable
private fun ConfidenceBadge(confidence: Float) {
  val pct = (confidence * 100).toInt()
  val color = when {
    confidence >= 0.8f -> MaterialTheme.colorScheme.primary
    confidence >= 0.5f ->
      MaterialTheme.colorScheme.tertiary
    else -> MaterialTheme.colorScheme.error
  }
  Surface(
    color = color.copy(alpha = 0.15f),
    shape = RoundedCornerShape(4.dp),
    modifier = Modifier.padding(start = 4.dp),
  ) {
    Text(
      "$pct%",
      style = MaterialTheme.typography.labelSmall,
      color = color,
      modifier = Modifier.padding(
        horizontal = 6.dp,
        vertical = 2.dp,
      ),
    )
  }
}
