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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.linroid.kdown.DownloadConfig
import com.linroid.kdown.DownloadRequest
import com.linroid.kdown.DownloadState
import com.linroid.kdown.KDown
import com.linroid.kdown.SpeedLimit
import com.linroid.kdown.engine.KtorHttpEngine
import com.linroid.kdown.log.Logger
import com.linroid.kdown.task.DownloadTask
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.io.files.Path

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun App() {
  val config = remember {
    DownloadConfig(
      maxConnections = 4,
      retryCount = 3,
      retryDelayMs = 1000,
      progressUpdateIntervalMs = 200
    )
  }
  val kdown = remember {
    KDown(
      httpEngine = KtorHttpEngine(),
      config = config,
      logger = Logger.console()
    )
  }
  val scope = rememberCoroutineScope()
  val tasks by kdown.tasks.collectAsState()
  var showAddDialog by remember { mutableStateOf(false) }
  var errorMessage by remember { mutableStateOf<String?>(null) }

  DisposableEffect(Unit) {
    onDispose { kdown.close() }
  }

  LaunchedEffect(Unit) {
    kdown.loadTasks()
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
              Text(
                text = "v${KDown.VERSION} \u00b7 Downloader",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
              )
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
        onDownload = { url, fileName, speedLimit ->
          showAddDialog = false
          errorMessage = null
          startDownload(
            scope = scope,
            kdown = kdown,
            url = url,
            directory = Path("downloads"),
            fileName = fileName.ifBlank { null },
            speedLimit = speedLimit,
            onError = { errorMessage = it }
          )
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
  onDownload: (url: String, fileName: String, SpeedLimit) -> Unit
) {
  var url by remember { mutableStateOf("") }
  var fileName by remember { mutableStateOf("") }
  var selectedSpeed by remember { mutableStateOf(SpeedLimit.Unlimited) }
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
          supportingText = if (fileName.isBlank() && url.isNotBlank()) {
            { Text("Will be extracted from URL") }
          } else {
            null
          }
        )
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
            onDownload(trimmed, fileName.trim(), selectedSpeed)
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
          Text(
            text = fileName,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
          )
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
              append(" \u00b7 ${formatBytes(progress.downloadedBytes)}")
              append(" / ${formatBytes(progress.totalBytes)}")
              if (progress.bytesPerSecond > 0) {
                append(
                  " \u00b7 ${formatBytes(progress.bytesPerSecond)}/s"
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
        is DownloadState.Queued -> {
          Text(
            text = "Queued \u2014 waiting for download slot\u2026",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
          )
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
              trackColor = MaterialTheme.colorScheme.surfaceVariant
            )
            Text(
              text = "Paused \u00b7 ${pausedPct.toInt()}% \u00b7 " +
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
private fun SpeedLimitSlider(
  task: DownloadTask,
  scope: CoroutineScope
) {
  // Slider steps: 0=Unlimited, 1=512KB, 2=1MB, 3=2MB, 4=5MB, 5=10MB
  val steps = listOf(0L, 512 * 1024L, 1_048_576L, 2_097_152L,
    5_242_880L, 10_485_760L)
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
  kdown: KDown,
  url: String,
  directory: Path,
  fileName: String?,
  speedLimit: SpeedLimit = SpeedLimit.Unlimited,
  onError: (String) -> Unit = {}
) {
  scope.launch {
    runCatching {
      val request = DownloadRequest(
        url = url,
        directory = directory,
        fileName = fileName,
        connections = 4,
        speedLimit = speedLimit
      )
      kdown.download(request)
    }.onFailure { e ->
      onError(e.message ?: "Failed to start download")
    }
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
      "${hundredths / 100}.${(hundredths % 100)
        .toString().padStart(2, '0')} GB"
    }
  }
}
