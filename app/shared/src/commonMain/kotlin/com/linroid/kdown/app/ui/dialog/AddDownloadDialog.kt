package com.linroid.kdown.app.ui.dialog

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import com.linroid.kdown.api.DownloadPriority
import com.linroid.kdown.api.DownloadSchedule
import com.linroid.kdown.api.SpeedLimit
import com.linroid.kdown.app.ui.common.PriorityIcon
import com.linroid.kdown.app.ui.common.PrioritySelector
import com.linroid.kdown.app.ui.common.ScheduleIcon
import com.linroid.kdown.app.ui.common.ScheduleSelector
import com.linroid.kdown.app.ui.common.SpeedLimitIcon
import com.linroid.kdown.app.ui.common.SpeedLimitSelector
import com.linroid.kdown.app.util.extractFilename

private enum class DialogPanel {
  None, SpeedLimit, Priority, Schedule
}

@Composable
fun AddDownloadDialog(
  onDismiss: () -> Unit,
  onDownload: (
    url: String,
    fileName: String,
    SpeedLimit,
    DownloadPriority,
    DownloadSchedule
  ) -> Unit
) {
  var url by remember { mutableStateOf("") }
  var fileName by remember { mutableStateOf("") }
  var selectedSpeed by remember {
    mutableStateOf(SpeedLimit.Unlimited)
  }
  var selectedPriority by remember {
    mutableStateOf(DownloadPriority.NORMAL)
  }
  var selectedSchedule by remember {
    mutableStateOf<DownloadSchedule>(
      DownloadSchedule.Immediate
    )
  }
  var expanded by remember {
    mutableStateOf(DialogPanel.None)
  }
  val urlFocusRequester = remember {
    FocusRequester()
  }
  val isValidUrl = url.isBlank() ||
    url.trim().startsWith("http://") ||
    url.trim().startsWith("https://")

  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text("Add download") },
    text = {
      Column(
        verticalArrangement =
          Arrangement.spacedBy(12.dp)
      ) {
        LaunchedEffect(Unit) {
          urlFocusRequester.requestFocus()
        }
        OutlinedTextField(
          value = url,
          onValueChange = {
            url = it
            fileName = extractFilename(it)
          },
          modifier = Modifier.fillMaxWidth()
            .focusRequester(urlFocusRequester),
          label = { Text("URL") },
          singleLine = true,
          placeholder = {
            Text("https://example.com/file.zip")
          },
          isError = !isValidUrl,
          supportingText = if (!isValidUrl) {
            {
              Text(
                "URL must start with " +
                  "http:// or https://"
              )
            }
          } else {
            null
          }
        )
        OutlinedTextField(
          value = fileName,
          onValueChange = { fileName = it },
          modifier = Modifier.fillMaxWidth(),
          label = { Text("Save as") },
          singleLine = true,
          placeholder = {
            Text("Auto-detected from URL")
          },
          supportingText = if (fileName.isBlank() &&
            url.isNotBlank()
          ) {
            { Text("Will be extracted from URL") }
          } else {
            null
          }
        )

        // Toggle icon row
        Row(
          horizontalArrangement =
            Arrangement.spacedBy(4.dp),
          verticalAlignment =
            Alignment.CenterVertically
        ) {
          SpeedLimitIcon(
            active =
              !selectedSpeed.isUnlimited,
            selected =
              expanded == DialogPanel.SpeedLimit,
            onClick = {
              expanded = if (expanded ==
                DialogPanel.SpeedLimit
              ) {
                DialogPanel.None
              } else {
                DialogPanel.SpeedLimit
              }
            }
          )
          PriorityIcon(
            active = selectedPriority !=
              DownloadPriority.NORMAL,
            selected =
              expanded == DialogPanel.Priority,
            onClick = {
              expanded = if (expanded ==
                DialogPanel.Priority
              ) {
                DialogPanel.None
              } else {
                DialogPanel.Priority
              }
            }
          )
          ScheduleIcon(
            selected =
              expanded == DialogPanel.Schedule,
            onClick = {
              expanded = if (expanded ==
                DialogPanel.Schedule
              ) {
                DialogPanel.None
              } else {
                DialogPanel.Schedule
              }
            }
          )
        }

        // Expanded panel
        AnimatedContent(
          targetState = expanded,
          transitionSpec = {
            expandVertically() togetherWith
              shrinkVertically()
          }
        ) { panel ->
          when (panel) {
            DialogPanel.SpeedLimit ->
              SpeedLimitSelector(
                value = selectedSpeed,
                onValueChange = {
                  selectedSpeed = it
                }
              )
            DialogPanel.Priority ->
              PrioritySelector(
                value = selectedPriority,
                onValueChange = {
                  selectedPriority = it
                }
              )
            DialogPanel.Schedule ->
              ScheduleSelector(
                value = selectedSchedule,
                onValueChange = {
                  selectedSchedule = it
                }
              )
            DialogPanel.None -> {}
          }
        }
      }
    },
    confirmButton = {
      Button(
        onClick = {
          val trimmed = url.trim()
          if (trimmed.isNotEmpty()) {
            onDownload(
              trimmed, fileName.trim(),
              selectedSpeed, selectedPriority,
              selectedSchedule
            )
          }
        },
        enabled = url.isNotBlank() && isValidUrl,
      ) {
        Text("Download")
      }
    },
    dismissButton = {
      TextButton(onClick = onDismiss) {
        Text("Cancel")
      }
    }
  )
}
