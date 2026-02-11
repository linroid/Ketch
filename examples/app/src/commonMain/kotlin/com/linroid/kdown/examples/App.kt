package com.linroid.kdown.examples

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.linroid.kdown.api.DownloadPriority
import com.linroid.kdown.api.DownloadRequest
import com.linroid.kdown.api.DownloadState
import com.linroid.kdown.api.DownloadTask
import com.linroid.kdown.api.KDownApi
import com.linroid.kdown.api.SpeedLimit
import com.linroid.kdown.examples.backend.BackendConfig
import com.linroid.kdown.examples.backend.BackendEntry
import com.linroid.kdown.remote.ConnectionState
import com.linroid.kdown.examples.backend.BackendManager
import com.linroid.kdown.examples.backend.ServerState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun App(backendManager: BackendManager) {
  val activeApi by backendManager.activeApi.collectAsState()
  val activeBackend by backendManager.activeBackend.collectAsState()
  val backends by backendManager.backends.collectAsState()
  val connectionState by (
    activeBackend?.connectionState
      ?: MutableStateFlow(ConnectionState.Disconnected())
    ).collectAsState()
  val scope = rememberCoroutineScope()
  val tasks by activeApi.tasks.collectAsState()
  val version by activeApi.version.collectAsState()
  var showAddDialog by remember { mutableStateOf(false) }
  var showBackendSelector by remember { mutableStateOf(false) }
  var showAddRemoteDialog by remember { mutableStateOf(false) }
  var errorMessage by remember { mutableStateOf<String?>(null) }
  var switchingBackendId by remember { mutableStateOf<String?>(null) }

  DisposableEffect(Unit) {
    onDispose { backendManager.close() }
  }

  val sortedTasks = remember(tasks) {
    tasks.sortedByDescending { it.createdAt }
  }

  MaterialTheme {
    Scaffold(
      topBar = {
        TopAppBar(
          title = {
            Column {
              Text(
                text = "KDown",
                fontWeight = FontWeight.SemiBold
              )
              Row(
                modifier = Modifier.clickable {
                  showBackendSelector = true
                },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
              ) {
                Text(
                  text = "v${version.backend} \u00b7 " +
                    (activeBackend?.label ?: "Not connected"),
                  style = MaterialTheme.typography.bodySmall,
                  color =
                    MaterialTheme.colorScheme.onSurfaceVariant
                )
                ConnectionStatusDot(connectionState)
              }
            }
          }
        )
      },
      floatingActionButton = {
        FloatingActionButton(onClick = { showAddDialog = true }) {
          Icon(Icons.Filled.Add, contentDescription = "Add download")
        }
      }
    ) { paddingValues ->
      if (sortedTasks.isEmpty() && errorMessage == null) {
        EmptyState(
          modifier = Modifier
            .fillMaxSize()
            .padding(paddingValues),
          onAddClick = { showAddDialog = true }
        )
      } else {
        LazyColumn(
          modifier = Modifier
            .fillMaxSize()
            .padding(paddingValues),
          contentPadding = PaddingValues(16.dp),
          verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
          if (errorMessage != null) {
            item(key = "error-banner") {
              Card(
                colors = CardDefaults.cardColors(
                  containerColor =
                    MaterialTheme.colorScheme.errorContainer
                ),
                modifier = Modifier.fillMaxWidth()
              ) {
                Row(
                  modifier = Modifier.padding(16.dp),
                  verticalAlignment = Alignment.CenterVertically,
                  horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                  Text(
                    text = errorMessage ?: "",
                    style = MaterialTheme.typography.bodySmall,
                    color =
                      MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.weight(1f)
                  )
                  TextButton(
                    onClick = { errorMessage = null }
                  ) {
                    Text("Dismiss")
                  }
                }
              }
            }
          }
          items(
            items = sortedTasks,
            key = { it.taskId }
          ) { task ->
            DownloadTaskItem(
              task = task,
              scope = scope,
              onPause = { scope.launch { task.pause() } },
              onResume = { scope.launch { task.resume() } },
              onCancel = { scope.launch { task.cancel() } },
              onRetry = { scope.launch { task.resume() } },
              onRemove = { scope.launch { task.remove() } }
            )
          }
        }
      }
    }

    if (showAddDialog) {
      AddDownloadDialog(
        onDismiss = { showAddDialog = false },
        onDownload = { url, fileName, speedLimit, priority ->
          showAddDialog = false
          errorMessage = null
          startDownload(
            scope = scope,
            kdown = activeApi,
            url = url,
            directory = "downloads",
            fileName = fileName.ifBlank { null },
            speedLimit = speedLimit,
            priority = priority,
            onError = { errorMessage = it }
          )
        }
      )
    }

    if (showBackendSelector) {
      BackendSelectorSheet(
        backendManager = backendManager,
        activeBackendId = activeBackend?.id,
        switchingBackendId = switchingBackendId,
        onSelectBackend = { entry ->
          if (entry.id != activeBackend?.id &&
            switchingBackendId == null
          ) {
            switchingBackendId = entry.id
            scope.launch {
              try {
                backendManager.switchTo(entry.id)
                showBackendSelector = false
              } catch (e: Exception) {
                errorMessage =
                  "Failed to switch backend: ${e.message}"
              } finally {
                switchingBackendId = null
              }
            }
          }
        },
        onRemoveBackend = { entry ->
          scope.launch {
            try {
              backendManager.removeBackend(entry.id)
            } catch (e: Exception) {
              errorMessage =
                "Failed to remove backend: ${e.message}"
            }
          }
        },
        onAddRemoteServer = {
          showAddRemoteDialog = true
        },
        onDismiss = { showBackendSelector = false }
      )
    }

    if (showAddRemoteDialog) {
      AddRemoteServerDialog(
        onDismiss = { showAddRemoteDialog = false },
        onAdd = { host, port, token ->
          showAddRemoteDialog = false
          try {
            backendManager.addRemote(host, port, token)
          } catch (e: Exception) {
            errorMessage =
              "Failed to add remote server: ${e.message}"
          }
        }
      )
    }

  }
}

@Composable
private fun EmptyState(
  modifier: Modifier = Modifier,
  onAddClick: () -> Unit
) {
  Box(
    modifier = modifier,
    contentAlignment = Alignment.Center
  ) {
    Column(
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
      Text(
        text = "No downloads yet",
        style = MaterialTheme.typography.headlineSmall,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurface
      )
      Text(
        text = "Add a URL to start downloading",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
      )
      Spacer(modifier = Modifier.height(8.dp))
      Button(onClick = onAddClick) {
        Text("Add download")
      }
    }
  }
}

private data class SpeedOption(
  val label: String,
  val limit: SpeedLimit
)

private val speedOptions = listOf(
  SpeedOption("Unlimited", SpeedLimit.Unlimited),
  SpeedOption("1 MB/s", SpeedLimit.mbps(1)),
  SpeedOption("5 MB/s", SpeedLimit.mbps(5)),
  SpeedOption("10 MB/s", SpeedLimit.mbps(10))
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddDownloadDialog(
  onDismiss: () -> Unit,
  onDownload: (
    url: String,
    fileName: String,
    SpeedLimit,
    DownloadPriority
  ) -> Unit
) {
  var url by remember { mutableStateOf("") }
  var fileName by remember { mutableStateOf("") }
  var selectedSpeed by remember { mutableStateOf(SpeedLimit.Unlimited) }
  var selectedPriority by remember {
    mutableStateOf(DownloadPriority.NORMAL)
  }
  val isValidUrl = url.isBlank() ||
    url.trim().startsWith("http://") ||
    url.trim().startsWith("https://")

  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text("Add download") },
    text = {
      Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedTextField(
          value = url,
          onValueChange = {
            url = it
            fileName = extractFilename(it)
          },
          modifier = Modifier.fillMaxWidth(),
          label = { Text("URL") },
          singleLine = true,
          placeholder = {
            Text("https://example.com/file.zip")
          },
          isError = !isValidUrl,
          supportingText = if (!isValidUrl) {
            { Text("URL must start with http:// or https://") }
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
          placeholder = { Text("Auto-detected from URL") },
          supportingText = if (fileName.isBlank() &&
            url.isNotBlank()
          ) {
            { Text("Will be extracted from URL") }
          } else {
            null
          }
        )
        Text(
          text = "Priority",
          style = MaterialTheme.typography.labelMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Row(
          horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
          DownloadPriority.entries.forEach { priority ->
            FilterChip(
              selected = selectedPriority == priority,
              onClick = { selectedPriority = priority },
              label = { Text(priorityLabel(priority)) }
            )
          }
        }
        Text(
          text = "Speed limit",
          style = MaterialTheme.typography.labelMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Row(
          horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
          speedOptions.forEach { option ->
            FilterChip(
              selected = selectedSpeed == option.limit,
              onClick = { selectedSpeed = option.limit },
              label = { Text(option.label) }
            )
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
              selectedSpeed, selectedPriority
            )
          }
        },
        enabled = url.isNotBlank() && isValidUrl
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

@Composable
private fun DownloadTaskItem(
  task: DownloadTask,
  scope: CoroutineScope,
  onPause: () -> Unit,
  onResume: () -> Unit,
  onCancel: () -> Unit,
  onRetry: () -> Unit,
  onRemove: () -> Unit
) {
  val state by task.state.collectAsState()
  val fileName = task.request.fileName
    ?: extractFilename(task.request.url).ifBlank { "download" }
  val isDownloading = state is DownloadState.Downloading ||
    state is DownloadState.Pending
  val isPaused = state is DownloadState.Paused
  val speedLimit = task.request.speedLimit
  val priority = task.request.priority

  Card(
    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    modifier = Modifier
      .fillMaxWidth()
      .then(
        if (isDownloading || isPaused) {
          Modifier.clickable {
            if (isDownloading) onPause() else onResume()
          }
        } else {
          Modifier
        }
      )
  ) {
    Column(
      modifier = Modifier.padding(16.dp),
      verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
      Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
      ) {
        StatusIndicator(state)
        Column(modifier = Modifier.weight(1f)) {
          Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
          ) {
            Text(
              text = fileName,
              style = MaterialTheme.typography.titleMedium,
              fontWeight = FontWeight.SemiBold,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis,
              modifier = Modifier.weight(1f, fill = false)
            )
            if (priority != DownloadPriority.NORMAL) {
              PriorityBadge(priority)
            }
          }
          Text(
            text = task.request.url,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
          )
        }
      }

      when (val s = state) {
        is DownloadState.Downloading -> {
          val progress = s.progress
          val pct = (progress.percent * 100).coerceIn(0f, 100f)
          LinearProgressIndicator(
            progress = { progress.percent },
            modifier = Modifier.fillMaxWidth()
          )
          Text(
            text = buildString {
              append("${pct.toInt()}%")
              append(
                " \u00b7 ${formatBytes(progress.downloadedBytes)}"
              )
              append(" / ${formatBytes(progress.totalBytes)}")
              if (progress.bytesPerSecond > 0) {
                append(
                  " \u00b7 " +
                    "${formatBytes(progress.bytesPerSecond)}/s"
                )
              }
              if (!speedLimit.isUnlimited) {
                append(
                  " (limit: " +
                    "${formatBytes(speedLimit.bytesPerSecond)}/s)"
                )
              }
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
          )
          SpeedLimitSlider(task = task, scope = scope)
        }

        is DownloadState.Scheduled -> {
          Text(
            text = "Scheduled \u2014 waiting for start time\u2026",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
          )
        }

        is DownloadState.Queued -> {
          Text(
            text = "Queued \u2014 waiting for download slot\u2026",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
          )
          PrioritySelector(task = task, scope = scope)
        }

        is DownloadState.Pending -> {
          LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
          Text(
            text = "Preparing download\u2026",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
          )
        }

        is DownloadState.Paused -> {
          val progress = s.progress
          if (progress.totalBytes > 0) {
            val pausedPct =
              (progress.percent * 100).coerceIn(0f, 100f)
            LinearProgressIndicator(
              progress = { progress.percent },
              modifier = Modifier.fillMaxWidth(),
              trackColor =
                MaterialTheme.colorScheme.surfaceVariant
            )
            Text(
              text = "Paused \u00b7 ${pausedPct.toInt()}%" +
                " \u00b7 " +
                "${formatBytes(progress.downloadedBytes)} / " +
                formatBytes(progress.totalBytes),
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant
            )
          } else {
            Text(
              text = "Paused",
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant
            )
          }
        }

        is DownloadState.Completed -> {
          Text(
            text = "Download complete",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.tertiary
          )
        }

        is DownloadState.Failed -> {
          Text(
            text = "Failed: ${s.error.message}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
          )
        }

        is DownloadState.Canceled -> {
          Text(
            text = "Canceled",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
          )
        }

        is DownloadState.Idle -> {
          Text(
            text = "Waiting",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
          )
        }
      }

      TaskActionButtons(
        state = state,
        onPause = onPause,
        onResume = onResume,
        onCancel = onCancel,
        onRetry = onRetry,
        onRemove = onRemove
      )
    }
  }
}

@Composable
private fun PriorityBadge(priority: DownloadPriority) {
  val color = when (priority) {
    DownloadPriority.LOW ->
      MaterialTheme.colorScheme.surfaceVariant

    DownloadPriority.NORMAL ->
      MaterialTheme.colorScheme.secondaryContainer

    DownloadPriority.HIGH ->
      MaterialTheme.colorScheme.tertiaryContainer

    DownloadPriority.URGENT ->
      MaterialTheme.colorScheme.errorContainer
  }
  val textColor = when (priority) {
    DownloadPriority.LOW ->
      MaterialTheme.colorScheme.onSurfaceVariant

    DownloadPriority.NORMAL ->
      MaterialTheme.colorScheme.onSecondaryContainer

    DownloadPriority.HIGH ->
      MaterialTheme.colorScheme.onTertiaryContainer

    DownloadPriority.URGENT ->
      MaterialTheme.colorScheme.onErrorContainer
  }
  Box(
    modifier = Modifier
      .background(
        color = color,
        shape = MaterialTheme.shapes.small
      )
      .padding(horizontal = 6.dp, vertical = 2.dp)
  ) {
    Text(
      text = priorityLabel(priority),
      style = MaterialTheme.typography.labelSmall,
      color = textColor
    )
  }
}

@Composable
private fun PrioritySelector(
  task: DownloadTask,
  scope: CoroutineScope
) {
  var currentPriority by remember {
    mutableStateOf(task.request.priority)
  }
  Column {
    Text(
      text = "Change priority:",
      style = MaterialTheme.typography.labelSmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Spacer(modifier = Modifier.height(4.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
      DownloadPriority.entries.forEach { priority ->
        FilterChip(
          selected = currentPriority == priority,
          onClick = {
            currentPriority = priority
            scope.launch { task.setPriority(priority) }
          },
          label = {
            Text(
              text = priorityLabel(priority),
              style = MaterialTheme.typography.labelSmall
            )
          }
        )
      }
    }
  }
}

@Composable
private fun SpeedLimitSlider(
  task: DownloadTask,
  scope: CoroutineScope
) {
  // Slider steps: 0=Unlimited, 1=512KB, 2=1MB, 3=2MB, 4=5MB, 5=10MB
  val steps = listOf(
    0L, 512 * 1024L, 1_048_576L, 2_097_152L,
    5_242_880L, 10_485_760L
  )
  val initial = task.request.speedLimit.bytesPerSecond
  val initialIndex = steps.indexOfLast { it <= initial }
    .coerceAtLeast(0).toFloat()
  var sliderValue by remember { mutableStateOf(initialIndex) }

  Column {
    Row(
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
      Text(
        text = "Limit:",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
      )
      Slider(
        value = sliderValue,
        onValueChange = { sliderValue = it },
        onValueChangeFinished = {
          val idx = sliderValue.toInt().coerceIn(0, steps.lastIndex)
          val bps = steps[idx]
          val limit = if (bps == 0L) SpeedLimit.Unlimited
          else SpeedLimit.of(bps)
          scope.launch { task.setSpeedLimit(limit) }
        },
        valueRange = 0f..steps.lastIndex.toFloat(),
        steps = steps.size - 2,
        modifier = Modifier.weight(1f)
      )
      val idx = sliderValue.toInt().coerceIn(0, steps.lastIndex)
      Text(
        text = if (steps[idx] == 0L) "Unlimited"
        else "${formatBytes(steps[idx])}/s",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
      )
    }
  }
}

@Composable
private fun StatusIndicator(state: DownloadState) {
  val bgColor = when (state) {
    is DownloadState.Downloading,
    is DownloadState.Pending -> MaterialTheme.colorScheme.primaryContainer

    is DownloadState.Completed -> MaterialTheme.colorScheme.tertiaryContainer
    is DownloadState.Failed -> MaterialTheme.colorScheme.errorContainer
    is DownloadState.Paused -> MaterialTheme.colorScheme.secondaryContainer
    else -> MaterialTheme.colorScheme.surfaceVariant
  }
  val fgColor = when (state) {
    is DownloadState.Downloading,
    is DownloadState.Pending -> MaterialTheme.colorScheme.onPrimaryContainer

    is DownloadState.Completed ->
      MaterialTheme.colorScheme.onTertiaryContainer

    is DownloadState.Failed -> MaterialTheme.colorScheme.onErrorContainer
    is DownloadState.Paused ->
      MaterialTheme.colorScheme.onSecondaryContainer

    else -> MaterialTheme.colorScheme.onSurfaceVariant
  }
  val label = when (state) {
    is DownloadState.Idle -> "--"
    is DownloadState.Scheduled -> "SC"
    is DownloadState.Queued -> "Q"
    is DownloadState.Pending -> ".."
    is DownloadState.Downloading -> "DL"
    is DownloadState.Paused -> "||"
    is DownloadState.Completed -> "OK"
    is DownloadState.Failed -> "!!"
    is DownloadState.Canceled -> "X"
  }
  Box(
    modifier = Modifier
      .size(40.dp)
      .clip(CircleShape)
      .background(bgColor),
    contentAlignment = Alignment.Center
  ) {
    Text(
      text = label,
      style = MaterialTheme.typography.labelMedium,
      fontWeight = FontWeight.Bold,
      color = fgColor
    )
  }
}

@Composable
private fun TaskActionButtons(
  state: DownloadState,
  onPause: () -> Unit,
  onResume: () -> Unit,
  onCancel: () -> Unit,
  onRetry: () -> Unit,
  onRemove: () -> Unit
) {
  Row(
    horizontalArrangement = Arrangement.spacedBy(8.dp),
    verticalAlignment = Alignment.CenterVertically
  ) {
    when (state) {
      is DownloadState.Downloading,
      is DownloadState.Pending -> {
        FilledTonalIconButton(onClick = onPause) {
          Icon(Icons.Filled.Pause, contentDescription = "Pause")
        }
        IconButton(
          onClick = onCancel,
          colors = IconButtonDefaults.iconButtonColors(
            contentColor = MaterialTheme.colorScheme.error
          )
        ) {
          Icon(Icons.Filled.Close, contentDescription = "Cancel")
        }
      }

      is DownloadState.Paused -> {
        FilledTonalIconButton(onClick = onResume) {
          Icon(
            Icons.Filled.PlayArrow,
            contentDescription = "Resume"
          )
        }
        IconButton(
          onClick = onCancel,
          colors = IconButtonDefaults.iconButtonColors(
            contentColor = MaterialTheme.colorScheme.error
          )
        ) {
          Icon(Icons.Filled.Close, contentDescription = "Cancel")
        }
        IconButton(
          onClick = onRemove,
          colors = IconButtonDefaults.iconButtonColors(
            contentColor = MaterialTheme.colorScheme.error
          )
        ) {
          Icon(Icons.Filled.Delete, contentDescription = "Remove")
        }
      }

      is DownloadState.Completed -> {
        IconButton(
          onClick = onRemove,
          colors = IconButtonDefaults.iconButtonColors(
            contentColor = MaterialTheme.colorScheme.error
          )
        ) {
          Icon(Icons.Filled.Delete, contentDescription = "Remove")
        }
      }

      is DownloadState.Failed,
      is DownloadState.Canceled -> {
        FilledTonalIconButton(onClick = onRetry) {
          Icon(Icons.Filled.Refresh, contentDescription = "Retry")
        }
        IconButton(
          onClick = onRemove,
          colors = IconButtonDefaults.iconButtonColors(
            contentColor = MaterialTheme.colorScheme.error
          )
        ) {
          Icon(Icons.Filled.Delete, contentDescription = "Remove")
        }
      }

      is DownloadState.Scheduled,
      is DownloadState.Queued -> {
        IconButton(
          onClick = onCancel,
          colors = IconButtonDefaults.iconButtonColors(
            contentColor = MaterialTheme.colorScheme.error
          )
        ) {
          Icon(Icons.Filled.Close, contentDescription = "Cancel")
        }
      }

      is DownloadState.Idle -> {}
    }
  }
}

// -- Backend selector UI --

@Composable
private fun ConnectionStatusDot(state: ConnectionState) {
  val color = when (state) {
    is ConnectionState.Connected ->
      MaterialTheme.colorScheme.tertiary
    is ConnectionState.Connecting ->
      MaterialTheme.colorScheme.secondary
    is ConnectionState.Disconnected ->
      MaterialTheme.colorScheme.error
  }
  Box(
    modifier = Modifier
      .size(8.dp)
      .clip(CircleShape)
      .background(color)
  )
}

@Composable
private fun ConnectionStatusChip(
  state: ConnectionState,
  isActive: Boolean = false
) {
  val (label, bgColor, textColor) = when (state) {
    is ConnectionState.Connected -> Triple(
      "Connected",
      MaterialTheme.colorScheme.tertiaryContainer,
      MaterialTheme.colorScheme.onTertiaryContainer
    )
    is ConnectionState.Connecting -> Triple(
      "Connecting",
      MaterialTheme.colorScheme.secondaryContainer,
      MaterialTheme.colorScheme.onSecondaryContainer
    )
    is ConnectionState.Disconnected -> if (isActive) {
      Triple(
        "Disconnected",
        MaterialTheme.colorScheme.errorContainer,
        MaterialTheme.colorScheme.onErrorContainer
      )
    } else {
      Triple(
        "Not connected",
        MaterialTheme.colorScheme.surfaceVariant,
        MaterialTheme.colorScheme.onSurfaceVariant
      )
    }
  }
  Box(
    modifier = Modifier
      .background(
        color = bgColor,
        shape = MaterialTheme.shapes.small
      )
      .padding(horizontal = 6.dp, vertical = 2.dp)
  ) {
    Text(
      text = label,
      style = MaterialTheme.typography.labelSmall,
      color = textColor
    )
  }
}

private fun backendConfigIcon(config: BackendConfig): ImageVector {
  return when (config) {
    is BackendConfig.Embedded -> Icons.Filled.PhoneAndroid
    is BackendConfig.Remote -> Icons.Filled.Cloud
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BackendSelectorSheet(
  backendManager: BackendManager,
  activeBackendId: String?,
  switchingBackendId: String?,
  onSelectBackend: (BackendEntry) -> Unit,
  onRemoveBackend: (BackendEntry) -> Unit,
  onAddRemoteServer: () -> Unit,
  onDismiss: () -> Unit
) {
  val sheetState = rememberModalBottomSheetState()
  val backends by backendManager.backends.collectAsState()
  val serverState by backendManager.serverState.collectAsState()

  ModalBottomSheet(
    onDismissRequest = onDismiss,
    sheetState = sheetState
  ) {
    Column(
      modifier = Modifier
        .fillMaxWidth()
        .padding(bottom = 24.dp)
    ) {
      Text(
        text = "Select Backend",
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(
          horizontal = 24.dp, vertical = 8.dp
        )
      )

      backends.forEach { entry ->
        val entryConnectionState by
          entry.connectionState.collectAsState()
        val isActive = entry.id == activeBackendId
        val isSwitching = entry.id == switchingBackendId

        ListItem(
          modifier = Modifier.clickable(
            enabled = !isSwitching && switchingBackendId == null
          ) {
            onSelectBackend(entry)
          },
          headlineContent = {
            Text(
              text = entry.label,
              fontWeight = if (isActive) {
                FontWeight.SemiBold
              } else {
                FontWeight.Normal
              }
            )
          },
          leadingContent = {
            Icon(
              imageVector = backendConfigIcon(entry.config),
              contentDescription = entry.label,
              tint = if (isActive) {
                MaterialTheme.colorScheme.primary
              } else {
                MaterialTheme.colorScheme.onSurfaceVariant
              }
            )
          },
          supportingContent = {
            Column {
              ConnectionStatusChip(
                state = entryConnectionState,
                isActive = isActive
              )
              // Server controls inside the Embedded entry
              if (entry.isEmbedded &&
                backendManager.isLocalServerSupported
              ) {
                EmbeddedServerControls(
                  serverState = serverState,
                  onStartServer = { port, token ->
                    backendManager.startServer(port, token)
                  },
                  onStopServer = { backendManager.stopServer() }
                )
              }
            }
          },
          trailingContent = {
            Row(
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
              if (isSwitching) {
                CircularProgressIndicator(
                  modifier = Modifier.size(20.dp),
                  strokeWidth = 2.dp
                )
              } else if (isActive) {
                Icon(
                  imageVector = Icons.Filled.Check,
                  contentDescription = "Active",
                  tint = MaterialTheme.colorScheme.primary,
                  modifier = Modifier.size(20.dp)
                )
              }
              if (!entry.isEmbedded) {
                IconButton(
                  onClick = { onRemoveBackend(entry) }
                ) {
                  Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = "Remove",
                    tint =
                      MaterialTheme.colorScheme.onSurfaceVariant
                  )
                }
              }
            }
          }
        )
      }

      HorizontalDivider(
        modifier = Modifier.padding(vertical = 8.dp)
      )

      ListItem(
        modifier = Modifier.clickable { onAddRemoteServer() },
        headlineContent = { Text("Add Remote Server") },
        leadingContent = {
          Icon(
            imageVector = Icons.Filled.Add,
            contentDescription = "Add remote server",
            tint = MaterialTheme.colorScheme.primary
          )
        }
      )
    }
  }
}

@Composable
private fun EmbeddedServerControls(
  serverState: ServerState,
  onStartServer: (port: Int, token: String?) -> Unit,
  onStopServer: () -> Unit
) {
  when (serverState) {
    is ServerState.Running -> {
      Row(
        modifier = Modifier.padding(top = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
      ) {
        Text(
          text = "Server on :${serverState.port}",
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.primary
        )
        FilledTonalIconButton(
          onClick = onStopServer,
          modifier = Modifier.size(24.dp),
          colors = IconButtonDefaults.filledTonalIconButtonColors(
            containerColor =
              MaterialTheme.colorScheme.errorContainer,
            contentColor =
              MaterialTheme.colorScheme.onErrorContainer
          )
        ) {
          Icon(
            imageVector = Icons.Filled.Close,
            contentDescription = "Stop server",
            modifier = Modifier.size(14.dp)
          )
        }
      }
    }
    is ServerState.Stopped -> {
      TextButton(
        onClick = { onStartServer(8642, null) },
        modifier = Modifier.padding(top = 2.dp),
        contentPadding = PaddingValues(
          horizontal = 8.dp, vertical = 0.dp
        )
      ) {
        Icon(
          imageVector = Icons.Filled.Computer,
          contentDescription = null,
          modifier = Modifier.size(14.dp)
        )
        Spacer(Modifier.size(4.dp))
        Text(
          text = "Start Server",
          style = MaterialTheme.typography.labelSmall
        )
      }
    }
  }
}

@Composable
private fun AddRemoteServerDialog(
  onDismiss: () -> Unit,
  onAdd: (host: String, port: Int, token: String?) -> Unit
) {
  var host by remember { mutableStateOf("") }
  var port by remember { mutableStateOf("8642") }
  var token by remember { mutableStateOf("") }
  val isValidHost = host.isNotBlank()
  val isValidPort = port.toIntOrNull()?.let {
    it in 1..65535
  } ?: false

  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text("Add Remote Server") },
    text = {
      Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedTextField(
          value = host,
          onValueChange = { host = it },
          modifier = Modifier.fillMaxWidth(),
          label = { Text("Host") },
          singleLine = true,
          placeholder = { Text("192.168.1.5") }
        )
        OutlinedTextField(
          value = port,
          onValueChange = { port = it },
          modifier = Modifier.fillMaxWidth(),
          label = { Text("Port") },
          singleLine = true,
          placeholder = { Text("8642") },
          isError = port.isNotBlank() && !isValidPort,
          supportingText = if (port.isNotBlank() &&
            !isValidPort
          ) {
            { Text("Port must be 1-65535") }
          } else {
            null
          }
        )
        OutlinedTextField(
          value = token,
          onValueChange = { token = it },
          modifier = Modifier.fillMaxWidth(),
          label = { Text("API Token") },
          singleLine = true,
          placeholder = { Text("Optional") }
        )
      }
    },
    confirmButton = {
      Button(
        onClick = {
          onAdd(
            host.trim(),
            port.toIntOrNull() ?: 8642,
            token.trim().ifBlank { null }
          )
        },
        enabled = isValidHost && isValidPort
      ) {
        Text("Add")
      }
    },
    dismissButton = {
      TextButton(onClick = onDismiss) {
        Text("Cancel")
      }
    }
  )
}

private fun extractFilename(url: String): String {
  val path = url.trim()
    .substringBefore("?")
    .substringBefore("#")
    .trimEnd('/')
    .substringAfterLast("/")
  return path.ifBlank { "" }
}

private fun startDownload(
  scope: CoroutineScope,
  kdown: KDownApi,
  url: String,
  directory: String,
  fileName: String?,
  speedLimit: SpeedLimit = SpeedLimit.Unlimited,
  priority: DownloadPriority = DownloadPriority.NORMAL,
  onError: (String) -> Unit = {}
) {
  scope.launch {
    runCatching {
      val request = DownloadRequest(
        url = url,
        directory = directory,
        fileName = fileName,
        connections = 4,
        speedLimit = speedLimit,
        priority = priority
      )
      kdown.download(request)
    }.onFailure { e ->
      onError(e.message ?: "Failed to start download")
    }
  }
}

private fun priorityLabel(priority: DownloadPriority): String {
  return when (priority) {
    DownloadPriority.LOW -> "Low"
    DownloadPriority.NORMAL -> "Normal"
    DownloadPriority.HIGH -> "High"
    DownloadPriority.URGENT -> "Urgent"
  }
}

private fun formatBytes(bytes: Long): String {
  if (bytes < 0) return "--"
  val kb = 1024L
  val mb = kb * 1024
  val gb = mb * 1024
  return when {
    bytes < kb -> "$bytes B"
    bytes < mb -> {
      val tenths = (bytes * 10 + kb / 2) / kb
      "${tenths / 10}.${tenths % 10} KB"
    }

    bytes < gb -> {
      val tenths = (bytes * 10 + mb / 2) / mb
      "${tenths / 10}.${tenths % 10} MB"
    }

    else -> {
      val hundredths = (bytes * 100 + gb / 2) / gb
      "${hundredths / 100}.${
        (hundredths % 100)
          .toString().padStart(2, '0')
      } GB"
    }
  }
}
